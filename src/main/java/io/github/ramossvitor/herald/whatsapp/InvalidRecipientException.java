package io.github.ramossvitor.herald.whatsapp;

/**
 * The destination is not a number Herald is willing to send to. Separate from
 * the bean-validation path because the shape can pass a regex and still be
 * unusable — a national trunk prefix that survived, or more digits than E.164
 * allows.
 */
public class InvalidRecipientException extends RuntimeException {

	private final String to;

	public InvalidRecipientException(String to, String reason) {
		super(reason);
		this.to = to;
	}

	public String to() {
		return to;
	}
}
