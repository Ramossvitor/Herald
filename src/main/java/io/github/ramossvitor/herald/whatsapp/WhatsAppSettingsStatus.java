package io.github.ramossvitor.herald.whatsapp;

/** Whether the credentials a tenant handed over have been proven against Meta. */
public enum WhatsAppSettingsStatus {
	/** Stored, not yet exercised. */
	PENDING,
	VERIFIED,
	/** Meta refused them — see {@code lastError}. */
	FAILED
}
