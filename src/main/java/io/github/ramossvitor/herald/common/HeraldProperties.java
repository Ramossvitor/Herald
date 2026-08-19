package io.github.ramossvitor.herald.common;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "herald")
public record HeraldProperties(
		@DefaultValue("") String adminApiKey,
		Email email,
		Resend resend,
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

	public record Outbox(
			@DefaultValue("3s") Duration pollInterval,
			@DefaultValue("10") int batchSize,
			@DefaultValue("8") int maxAttempts,
			@DefaultValue("600ms") Duration sendInterval) {
	}
}
