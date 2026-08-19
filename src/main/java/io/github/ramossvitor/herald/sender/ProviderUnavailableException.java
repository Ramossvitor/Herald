package io.github.ramossvitor.herald.sender;

/** The email provider could not be reached or refused a management call. */
public class ProviderUnavailableException extends RuntimeException {

	public ProviderUnavailableException(String message) {
		super(message);
	}
}
