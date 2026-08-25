package io.github.ramossvitor.herald.outbox;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

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
	 */
	@Transactional
	public List<Message> claimDueBatch(int batchSize) {
		@SuppressWarnings("unchecked")
		List<UUID> ids = entityManager.createNativeQuery("""
				select id from messages
				where status = 'PENDING' and next_attempt_at <= now()
				order by next_attempt_at
				limit :batchSize
				for update skip locked
				""", UUID.class)
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
	 * A row stuck in SENDING means a worker died between claim and record.
	 * Re-sending is the lesser risk: the alternative is failing a message that
	 * may never have been sent at all, and a notification nobody receives is
	 * worse than one received twice.
	 *
	 * Here it costs nothing either way — the message id is the provider's
	 * idempotency key, so a second send collapses into the first.
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
