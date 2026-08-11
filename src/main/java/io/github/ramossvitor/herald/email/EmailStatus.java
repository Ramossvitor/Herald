package io.github.ramossvitor.herald.email;

public enum EmailStatus {
	/** Accepted, waiting for the worker (first attempt or a scheduled retry). */
	PENDING,
	/** Claimed by a worker pass; recovery releases rows stuck here after a crash. */
	SENDING,
	/** The provider accepted it. Terminal. */
	SENT,
	/** Rejected by the provider or out of attempts. Terminal (dead letter). */
	FAILED
}
