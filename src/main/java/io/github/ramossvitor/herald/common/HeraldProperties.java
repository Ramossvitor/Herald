package io.github.ramossvitor.herald.common;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "herald")
public record HeraldProperties(
		@DefaultValue("") String adminApiKey,
		/** Base64 256-bit key encrypting the provider credentials tenants hand
		 * over. Empty leaves every channel that needs them switched off. */
		@DefaultValue("") String secretKey,
		Email email,
		Resend resend,
		WhatsApp whatsapp,
		Outbox outbox) {

	public record Email(
			/** Operator-owned domain, verified once, that every tenant gets an
			 * address on. Empty disables the shared tier. */
			@DefaultValue("") String sharedRootDomain) {
	}

	public record Resend(
			@DefaultValue("https://api.resend.com") String baseUrl,
			@DefaultValue("") String apiKey,
			@DefaultValue("5s") Duration connectTimeout,
			@DefaultValue("10s") Duration readTimeout) {
	}

	/**
	 * There is no API key here on purpose: WhatsApp is bring-your-own, so the
	 * credential belongs to the tenant and lives encrypted in its settings row.
	 * What is operator-wide is only where to reach Meta and how long to wait.
	 */
	public record WhatsApp(
			@DefaultValue("https://graph.facebook.com") String baseUrl,
			/** Graph API versions are supported for roughly two years, so this is
			 * a value that must be bumped deliberately, not a constant. */
			@DefaultValue("v23.0") String apiVersion,
			@DefaultValue("5s") Duration connectTimeout,
			@DefaultValue("10s") Duration readTimeout,
			/** Meta's ceiling is far above Resend's; pacing is per channel so the
			 * slower one does not set the pace for both. */
			@DefaultValue("50ms") Duration sendInterval,
			/** How often approved-template state is re-read from Meta. */
			@DefaultValue("PT30M") Duration templateSyncInterval,
			/**
			 * Answers Meta's subscription handshake. Operator-wide, not per
			 * tenant, because that GET carries only the token and a challenge —
			 * nothing that says which tenant is subscribing — so a per-tenant
			 * value could not be looked up. It guards the handshake only; every
			 * actual notification is authenticated by the tenant's app secret.
			 * Empty refuses the handshake rather than accepting any token.
			 */
			@DefaultValue("") String webhookVerifyToken) {
	}

	public record Outbox(
			@DefaultValue("3s") Duration pollInterval,
			@DefaultValue("10") int batchSize,
			@DefaultValue("8") int maxAttempts,
			@DefaultValue("600ms") Duration sendInterval) {
	}
}
