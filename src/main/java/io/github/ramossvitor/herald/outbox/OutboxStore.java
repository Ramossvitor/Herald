package io.github.ramossvitor.herald.outbox;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import io.github.ramossvitor.herald.sender.Channel;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * The transactional edges of the outbox. Claiming and recording are two short
 * transactions with the network call between them, never inside either.
 */
@Component
public class OutboxStore {

	@PersistenceContext
	private EntityManager entityManager;

	private final Clock clock;

	public OutboxStore(Clock clock) {
		this.clock = clock;
	}

	/**
	 * {@code FOR UPDATE SKIP LOCKED}: concurrent claimers never block on each
	 * other and never pick the same row. Claimed rows leave as SENDING, so a
	 * crash before recording leaves evidence for {@link OutboxRecovery}.
	 *
	 * Claiming one channel at a time is half of what keeps a stalled provider
	 * from starving the others: nobody else's batch is filled with rows only it
	 * can send. The other half is the worker's per-channel batch ceiling, which
	 * bounds how long one channel can hold the pass.
	 */
	@Transactional
	public List<Message> claimDueBatch(Channel channel, int batchSize) {
		@SuppressWarnings("unchecked")
		List<UUID> ids = entityManager.createNativeQuery("""
				select id from messages
				where channel = :channel and status = 'PENDING' and next_attempt_at <= now()
				order by next_attempt_at
				limit :batchSize
				for update skip locked
				""", UUID.class)
				.setParameter("channel", channel.name())
				.setParameter("batchSize", batchSize)
				.getResultList();
		if (ids.isEmpty()) {
			return List.of();
		}

		List<Message> claimed = entityManager
				.createQuery("select m from Message m where m.id in :ids", Message.class)
				.setParameter("ids", ids)
				.getResultList();
		Instant now = clock.instant();
		claimed.forEach(message -> message.markSending(now));
		return claimed;
	}

	@Transactional
	public void recordOutcome(UUID messageId, RetryPolicy.Decision decision, String providerMessageId, String error) {
		Message message = entityManager.find(Message.class, messageId);
		if (message == null) {
			return;
		}
		Instant now = clock.instant();
		message.recordAttempt();
		switch (decision.status()) {
			case SENT -> message.recordSuccess(providerMessageId, now);
			case FAILED -> message.recordFailure(error, now);
			case PENDING -> message.scheduleRetry(error, now.plus(decision.delay()), now);
			case SENDING -> throw new IllegalArgumentException("SENDING is not an outcome");
		}
	}

	/**
	 * A row stuck in SENDING means a worker died between claim and record. The
	 * provider idempotency key makes re-sending it safe. Channel-agnostic on
	 * purpose: a crash abandons rows on whatever channel was in flight.
	 */
	@Transactional
	public int releaseStuckSending(Duration olderThan) {
		Instant now = clock.instant();
		return entityManager.createQuery("""
				update Message m
				set m.status = :pending, m.nextAttemptAt = :now, m.updatedAt = :now
				where m.status = :sending and m.updatedAt < :cutoff
				""")
				.setParameter("pending", MessageStatus.PENDING)
				.setParameter("sending", MessageStatus.SENDING)
				.setParameter("now", now)
				.setParameter("cutoff", now.minus(olderThan))
				.executeUpdate();
	}
}
