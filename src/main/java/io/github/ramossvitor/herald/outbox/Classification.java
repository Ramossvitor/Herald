package io.github.ramossvitor.herald.outbox;

/**
 * What a provider's answer means to the retry policy. Every channel maps its
 * own status vocabulary onto this one, so {@link RetryPolicy} never learns a
 * second set of error codes.
 */
public enum Classification {
	SUCCESS,
	/** Requests-per-second throttling — transient by definition, retry quickly. */
	BURST_LIMIT,
	/** The provider's daily/monthly quota — retry much later. */
	DAILY_LIMIT,
	/** Bad payload or config. Retrying cannot fix it. */
	REJECTED,
	/** Outage, timeout, network failure. */
	UNAVAILABLE
}
