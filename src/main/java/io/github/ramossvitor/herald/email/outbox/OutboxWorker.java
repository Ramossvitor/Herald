package io.github.ramossvitor.herald.email.outbox;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import io.github.ramossvitor.herald.common.HeraldProperties;
import io.github.ramossvitor.herald.email.EmailMessage;
import io.github.ramossvitor.herald.email.resend.ResendClient;
import io.github.ramossvitor.herald.email.resend.ResendResponseClassifier;
import io.github.ramossvitor.herald.email.resend.ResendResponseClassifier.Classification;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Polls the outbox and pushes due messages through the provider, one at a
 * time, paced under the provider's requests-per-second limit.
 *
 * Delivery is at-least-once from the outbox's point of view; the provider
 * idempotency key collapses that to effectively-once at the mailbox.
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

	private final OutboxStore store;
	private final ResendClient resend;
	private final HeraldProperties.Outbox properties;
	private final RetryPolicy retryPolicy;
	private final MeterRegistry metrics;

	private final AtomicBoolean nudged = new AtomicBoolean(false);
	private int idleTicks = 0;
	private int ticksSinceLastPoll = 0;
	private boolean warnedNotConfigured = false;

	public OutboxWorker(OutboxStore store, ResendClient resend, HeraldProperties properties, MeterRegistry metrics) {
		this.store = store;
		this.resend = resend;
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
		int processed = runOnce();
		idleTicks = processed > 0 ? 0 : idleTicks + 1;
	}

	/** One full pass over everything currently due. Public for tests. */
	public int runOnce() {
		if (!resend.configured()) {
			if (!warnedNotConfigured) {
				log.warn("RESEND_API_KEY is not set — dispatch is paused, messages will queue as PENDING");
				warnedNotConfigured = true;
			}
			return 0;
		}
		int processed = 0;
		while (true) {
			List<EmailMessage> batch = store.claimDueBatch(properties.batchSize());
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
	}

	private void processOne(EmailMessage message) {
		MDC.put("messageId", message.getId().toString());
		try {
			ResendClient.Outcome outcome = resend.send(message);
			Classification classification = outcome.transportFailed()
					? Classification.UNAVAILABLE
					: ResendResponseClassifier.classify(outcome.httpStatus(), outcome.body());

			int attemptNumber = message.getAttemptCount() + 1;
			RetryPolicy.Decision decision = retryPolicy.decide(classification, attemptNumber,
					outcome.retryAfterSeconds());
			String providerMessageId = classification == Classification.SUCCESS
					? ResendResponseClassifier.providerMessageId(outcome.body())
					: null;
			store.recordOutcome(message.getId(), decision, providerMessageId, describeError(classification, outcome));

			switch (decision.status()) {
				case SENT -> metrics.counter("herald.emails.sent").increment();
				case FAILED -> {
					metrics.counter("herald.emails.failed").increment();
					log.error("message failed after attempt {}: {} (http {})", attemptNumber, classification,
							outcome.httpStatus());
				}
				case PENDING -> log.warn("attempt {} got {} (http {}), retrying in {}", attemptNumber, classification,
						outcome.httpStatus(), decision.delay());
				case SENDING -> throw new IllegalStateException("unreachable");
			}
		}
		finally {
			MDC.remove("messageId");
		}
	}

	private static String describeError(Classification classification, ResendClient.Outcome outcome) {
		if (classification == Classification.SUCCESS) {
			return null;
		}
		if (outcome.transportFailed()) {
			return truncate("transport: " + outcome.transportError());
		}
		return truncate("http " + outcome.httpStatus() + ": " + outcome.body());
	}

	private static String truncate(String value) {
		return value.length() <= 500 ? value : value.substring(0, 500);
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
