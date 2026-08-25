package io.github.ramossvitor.herald.suppression;

/**
 * The recipient is on this tenant's suppression list. A caller mistake in the
 * same family as an unverified sender — not a quota, which is why it is
 * reported separately and checked before the quota gates.
 */
public class SuppressedRecipientException extends RuntimeException {

	private final String recipient;
	private final SuppressionReason reason;

	public SuppressedRecipientException(String recipient, SuppressionReason reason) {
		super("recipient is suppressed: " + recipient);
		this.recipient = recipient;
		this.reason = reason;
	}

	public String recipient() {
		return recipient;
	}

	public SuppressionReason reason() {
		return reason;
	}
}
