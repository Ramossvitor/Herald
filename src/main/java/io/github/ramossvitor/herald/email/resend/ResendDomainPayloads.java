package io.github.ramossvitor.herald.email.resend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Resend's /domains payload contract, isolated for testing. Success bodies
 * look like {@code {"id":"...","status":"pending","records":[...]}}.
 */
public final class ResendDomainPayloads {

	/** Anything the provider has not decided yet counts as still pending. */
	public enum DomainStatus {
		VERIFIED, FAILED, PENDING
	}

	private static final ObjectMapper JSON = new ObjectMapper();

	private ResendDomainPayloads() {
	}

	public static String domainId(String body) {
		return textField(body, "id");
	}

	public static DomainStatus status(String body) {
		String status = textField(body, "status");
		if ("verified".equals(status)) {
			return DomainStatus.VERIFIED;
		}
		if ("failed".equals(status)) {
			return DomainStatus.FAILED;
		}
		return DomainStatus.PENDING;
	}

	/** The provider's DNS records array, verbatim JSON — null when absent. */
	public static String records(String body) {
		JsonNode node = node(body, "records");
		return node != null && node.isArray() ? node.toString() : null;
	}

	private static String textField(String body, String name) {
		JsonNode node = node(body, name);
		return node != null && node.isTextual() ? node.asText() : null;
	}

	private static JsonNode node(String body, String name) {
		if (body == null || body.isEmpty()) {
			return null;
		}
		try {
			return JSON.readTree(body).get(name);
		}
		catch (Exception ex) {
			return null;
		}
	}
}
