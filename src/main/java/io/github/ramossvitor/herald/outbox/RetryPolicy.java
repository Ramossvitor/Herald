package io.github.ramossvitor.herald.outbox;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Pure decision table: (classification, attempt number) → next state. Owning
 * an outbox is what allows the patient cases here — a provider daily limit is
 * retried hours later instead of being reported as a loss.
 */
public final class RetryPolicy {

	private static final Duration BURST_RETRY_CAP = Duration.ofSeconds(5);
	private static final Duration BURST_RETRY_DEFAULT = Duration.ofSeconds(2);
	private static final Duration PROVIDER_DAILY_RETRY = Duration.ofHours(4);
	private static final Duration UNAVAILABLE_BASE = Duration.ofSeconds(30);
	private static final Duration UNAVAILABLE_CAP = Duration.ofHours(1);
	private static final long JITTER_MAX_MS = 250;

	private final int maxAttempts;

	public RetryPolicy(int maxAttempts) {
		this.maxAttempts = maxAttempts;
	}

	/** {@code delay} is null exactly when {@code status} is terminal. */
	public record Decision(MessageStatus status, Duration delay) {
	}

	/**
	 * @param attemptNumber the attempt just made, 1-based
	 * @param retryAfterSeconds the provider's Retry-After, when present
	 */
	public Decision decide(Classification classification, int attemptNumber, Integer retryAfterSeconds) {
		return switch (classification) {
			case SUCCESS -> new Decision(MessageStatus.SENT, null);
			case REJECTED -> new Decision(MessageStatus.FAILED, null);
			case BURST_LIMIT, DAILY_LIMIT, UNAVAILABLE -> attemptNumber >= maxAttempts
					? new Decision(MessageStatus.FAILED, null)
					: new Decision(MessageStatus.PENDING, delayFor(classification, attemptNumber, retryAfterSeconds));
		};
	}

	private static Duration delayFor(Classification classification, int attemptNumber, Integer retryAfterSeconds) {
		Duration base = switch (classification) {
			// A large Retry-After on a burst signals quota, not burst — the cap
			// keeps a mislabeled response from parking the message for hours.
			case BURST_LIMIT -> retryAfterSeconds != null
					? min(Duration.ofSeconds(retryAfterSeconds), BURST_RETRY_CAP)
					: BURST_RETRY_DEFAULT;
			case DAILY_LIMIT -> PROVIDER_DAILY_RETRY;
			case UNAVAILABLE -> min(
					UNAVAILABLE_BASE.multipliedBy(1L << Math.min(attemptNumber - 1, 20)),
					UNAVAILABLE_CAP);
			case SUCCESS, REJECTED -> throw new IllegalArgumentException("terminal classification: " + classification);
		};
		// De-synchronizes retries born from the same burst.
		return base.plusMillis(ThreadLocalRandom.current().nextLong(JITTER_MAX_MS + 1));
	}

	private static Duration min(Duration left, Duration right) {
		return left.compareTo(right) <= 0 ? left : right;
	}
}
