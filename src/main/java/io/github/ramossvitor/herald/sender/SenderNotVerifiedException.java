package io.github.ramossvitor.herald.sender;

public class SenderNotVerifiedException extends RuntimeException {

	private final String from;

	public SenderNotVerifiedException(String from) {
		super("sender is not a verified identity of this tenant: " + from);
		this.from = from;
	}

	public String from() {
		return from;
	}
}
