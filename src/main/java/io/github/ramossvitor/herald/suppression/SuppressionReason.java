package io.github.ramossvitor.herald.suppression;

import java.util.Locale;

/**
 * Why an address stopped being mailable. Both values mean the destination
 * itself refused — not that a send failed, which is the outbox's business.
 */
public enum SuppressionReason {

	/** The receiving server rejected it permanently: the mailbox is not real. */
	BOUNCED,

	/** The recipient marked a delivered message as spam. */
	COMPLAINED;

	public String wireName() {
		return name().toLowerCase(Locale.ROOT);
	}
}
