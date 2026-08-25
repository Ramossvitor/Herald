package io.github.ramossvitor.herald.outbox;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import io.github.ramossvitor.herald.common.HeraldProperties;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Polls the outbox and pushes due messages through the provider, one at a time,
 * paced under the provider's requests-per-second limit.
 *
 * Delivery is at-least-once from the outbox's point of view; the provider
 * idempotency key collapses that to effectively-once at the destination.
 */
@Component
@Lazy(false) // the app runs with global lazy-init, which would otherwise never register @Scheduled beans
public class OutboxWorker {

	private static final Logger log = LoggerFactory.getLogger(OutboxWorker.class);

	/** Ticks without work before the worker considers itself idle. */
	private static final int IDLE_AFTER_TICKS = 3;
	/** While idle, only every Nth tick touches the database (serverless
	 * Postgres bills compute time; a hot poll would keep it awake for nothing). */
	private static final int IDLE_POLL_EVERY_TICKS = 10;
	/**
	 * Batches one pass may take before it yields. Every @Scheduled job in this
	 * application shares Spring's default single-threaded scheduler, and this
	 * loop sleeps in-thread to pace sends — so an unbounded pass over a deep
	 * backlog would hold that one thread for backlog × send-interval. During it
	 * {@link OutboxRecovery} would never run, and the rows a crashed worker
	 * abandoned would never be released: the worker would starve its own safety
	 * net. Five batches bounds a pass at roughly thirty seconds; the remainder
	 * stays due and the next tick continues it.
	 */
	private static final int MAX_BATCHES_PER_TICK = 5;

	private final OutboxStore store;
	private final MessageSender sender;
	private final HeraldProperties.Outbox properties;
	private final RetryPolicy retryPolicy;
	private final MeterRegistry metrics;

	private final AtomicBoolean nudged = new AtomicBoolean(false);
	private boolean warnedNotConfigured;
	private int idleTicks = 0;
	private int ticksSinceLastPoll = 0;

	public OutboxWorker(OutboxStore store, MessageSender sender, HeraldProperties properties, MeterRegistry metrics) {
		this.store = store;
		this.sender = sender;
		this.properties = properties.outbox();
		this.retryPolicy = new RetryPolicy(this.properties.maxAttempts());
		this.metrics = metrics;
	}

	/** Called after a submission commits so the next tick polls immediately. */
	public void nudge() {
		nudged.set(true);
	}

	@Scheduled(fixedDelayString = "${herald.outbox.poll-interval}")
	void tick() {
		ticksSinceLastPoll++;
		boolean idle = idleTicks >= IDLE_AFTER_TICKS;
		if (idle && !nudged.get() && ticksSinceLastPoll < IDLE_POLL_EVERY_TICKS) {
			return;
		}
		nudged.set(false);
		ticksSinceLastPoll = 0;
		int processed;
		try {
			processed = runOnce();
		}
		catch (RuntimeException ex) {
			// Rows already claimed stay SENDING; OutboxRecovery releases them.
			// Swallowed here rather than in runOnce so the scheduled path
			// survives while the test entry point still fails loudly.
			log.error("outbox pass aborted", ex);
			processed = 0;
		}
		idleTicks = processed > 0 ? 0 : idleTicks + 1;
	}

	/** Everything currently due. Public for tests. */
	public int runOnce() {
		if (!sender.configured()) {
			if (!warnedNotConfigured) {
				warnedNotConfigured = true;
				log.warn("no provider credentials — dispatch is paused, messages will queue as PENDING");
			}
			return 0;
		}
		int processed = 0;
		for (int batches = 0; batches < MAX_BATCHES_PER_TICK; batches++) {
			List<Message> batch = store.claimDueBatch(properties.batchSize());
			if (batch.isEmpty()) {
				return processed;
			}
			for (int i = 0; i < batch.size(); i++) {
				if (i > 0) {
					pace();
				}
				processOne(batch.get(i));
				processed++;
			}
			if (batch.size() < properties.batchSize()) {
				return processed;
			}
			pace();
		}
		log.info("messages still due after {} batches — resuming next tick", MAX_BATCHES_PER_TICK);
		return processed;
	}

	private void processOne(Message message) {
		MDC.put("messageId", message.getId().toString());
		try {
			Attempt attempt = sender.send(message);

			int attemptNumber = message.getAttemptCount() + 1;
			RetryPolicy.Decision decision = retryPolicy.decide(attempt.classification(), attemptNumber,
					attempt.retryAfterSeconds());
			store.recordOutcome(message.getId(), decision, attempt.providerMessageId(), attempt.error());

			switch (decision.status()) {
				case SENT -> metrics.counter("herald.messages.sent").increment();
				case FAILED -> {
					metrics.counter("herald.messages.failed").increment();
					log.error("message failed after attempt {}: {}", attemptNumber, attempt.classification());
				}
				case PENDING -> log.warn("attempt {} got {}, retrying in {}", attemptNumber, attempt.classification(),
						decision.delay());
				case SENDING -> throw new IllegalStateException("unreachable");
			}
		}
		finally {
			MDC.remove("messageId");
		}
	}

	private void pace() {
		try {
			Thread.sleep(properties.sendInterval().toMillis());
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}
}
