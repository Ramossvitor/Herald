package io.github.ramossvitor.herald.outbox;

/**
 * One call to the provider, reduced to what the retry policy needs. Lives here
 * rather than on the sender because the worker is what consumes it.
 *
 * @param providerMessageId the provider's own id, non-null only on SUCCESS
 * @param error human-readable cause for {@code last_error}, null on SUCCESS.
 *        Senders must keep credentials out of this: it is persisted and
 *        returned by the status endpoint.
 * @param retryAfterSeconds the provider's Retry-After, when it sent one
 */
public record Attempt(Classification classification, String providerMessageId, String error,
		Integer retryAfterSeconds) {

	public static Attempt success(String providerMessageId) {
		return new Attempt(Classification.SUCCESS, providerMessageId, null, null);
	}

	public static Attempt failed(Classification classification, String error, Integer retryAfterSeconds) {
		return new Attempt(classification, null, error, retryAfterSeconds);
	}
}
