package io.github.ramossvitor.herald.outbox;

/** Lifecycle of an outbox row, identical on every channel. */
public enum MessageStatus {
	PENDING,
	SENDING,
	SENT,
	FAILED
}
