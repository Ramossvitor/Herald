package io.github.ramossvitor.herald.whatsapp;

/**
 * The tenant has no usable WhatsApp credentials. A caller mistake, not a
 * transient one — the tenant has to bring its own number before Herald can
 * dispatch under it.
 */
public class WhatsAppNotConfiguredException extends RuntimeException {

	public WhatsAppNotConfiguredException(String reason) {
		super(reason);
	}

	public String reason() {
		return getMessage();
	}
}
