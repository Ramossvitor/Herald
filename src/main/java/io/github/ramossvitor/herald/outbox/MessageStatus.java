package io.github.ramossvitor.herald.outbox;

/** Lifecycle of an outbox row. */
public enum MessageStatus {
	PENDING,
	SENDING,
	SENT,
	FAILED
}
