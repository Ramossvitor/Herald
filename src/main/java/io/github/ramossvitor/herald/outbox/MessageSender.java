package io.github.ramossvitor.herald.outbox;

/**
 * The one thing the dispatch loop needs from the provider: turn a queued
 * message into an attempt it can classify. Transport, authentication and the
 * provider's own status vocabulary stay behind this line — the worker never
 * sees an HTTP status.
 *
 * A seam rather than a direct call, so the dependency runs one way: the
 * provider package knows about the outbox, and the outbox knows only this.
 */
public interface MessageSender {

	/**
	 * A sender without credentials pauses dispatch (messages stay PENDING)
	 * instead of failing them against something that was never called.
	 */
	boolean configured();

	/**
	 * May throw: the worker treats it as an aborted pass and lets the message's
	 * SENDING row wait for {@link OutboxRecovery}. Returning a failed
	 * {@link Attempt} is still the better answer — it records why.
	 */
	Attempt send(Message message);
}
