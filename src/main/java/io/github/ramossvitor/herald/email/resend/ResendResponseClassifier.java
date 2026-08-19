package io.github.ramossvitor.herald.email.resend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.ramossvitor.herald.outbox.Classification;

/**
 * Resend's status contract, isolated for testing. Error bodies look like
 * {@code {"name": "...", "message": "...", "statusCode": ...}}.
 */
public final class ResendResponseClassifier {

	private static final ObjectMapper JSON = new ObjectMapper();

	private ResendResponseClassifier() {
	}

	public static Classification classify(int status, String body) {
		if (status >= 200 && status < 300) {
			return Classification.SUCCESS;
		}
		if (status == 429) {
			// An unreadable body classifies as the terminal-ish case: better to
			// back off a daily quota than to hammer it as if it were a burst.
			return "rate_limit_exceeded".equals(field(body, "name"))
					? Classification.BURST_LIMIT
					: Classification.DAILY_LIMIT;
		}
		if (status >= 400 && status < 500) {
			return Classification.REJECTED;
		}
		// 5xx, and 3xx, which after redirects were followed means the endpoint
		// itself moved.
		return Classification.UNAVAILABLE;
	}

	public static String providerMessageId(String body) {
		return field(body, "id");
	}

	private static String field(String body, String name) {
		if (body == null || body.isEmpty()) {
			return null;
		}
		try {
			JsonNode node = JSON.readTree(body).get(name);
			return node != null && node.isTextual() ? node.asText() : null;
		}
		catch (Exception ex) {
			return null;
		}
	}
}
