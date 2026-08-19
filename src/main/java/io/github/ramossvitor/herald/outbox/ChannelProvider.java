package io.github.ramossvitor.herald.outbox;

import java.time.Duration;

import io.github.ramossvitor.herald.sender.Channel;

/**
 * The one thing the dispatch loop needs from a channel: turn a queued message
 * into an attempt it can classify. Transport, authentication and the
 * provider's own status vocabulary stay behind this line — the worker never
 * sees an HTTP status.
 */
public interface ChannelProvider {

	Channel channel();

	/**
	 * A provider without credentials pauses its channel (messages stay PENDING)
	 * instead of failing them against something that was never called. Other
	 * channels keep dispatching.
	 */
	boolean configured();

	/**
	 * Minimum gap between two sends on this channel, when the provider's
	 * requests-per-second ceiling differs from the shared
	 * {@code herald.outbox.send-interval}. Null uses the shared one.
	 */
	default Duration sendInterval() {
		return null;
	}

	/**
	 * May throw: the worker treats it as an aborted pass for this channel
	 * alone, leaves the other channels to their own passes, and lets the
	 * message's SENDING row wait for {@link OutboxRecovery}. Returning a failed
	 * {@link Attempt} is still the better answer — it records why.
	 */
	Attempt send(Message message);

	/**
	 * @param providerMessageId the provider's own id, non-null only on SUCCESS
	 * @param error human-readable cause for {@code last_error}, null on SUCCESS.
	 *        Providers must keep credentials out of this: it is persisted and
	 *        returned by the status endpoint.
	 * @param retryAfterSeconds the provider's Retry-After, when it sent one
	 */
	record Attempt(Classification classification, String providerMessageId, String error, Integer retryAfterSeconds) {

		public static Attempt success(String providerMessageId) {
			return new Attempt(Classification.SUCCESS, providerMessageId, null, null);
		}

		public static Attempt failed(Classification classification, String error, Integer retryAfterSeconds) {
			return new Attempt(classification, null, error, retryAfterSeconds);
		}
	}
}
