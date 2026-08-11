package io.github.ramossvitor.herald.email.resend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Resend's status contract, isolated for testing. Error bodies look like
 * {@code {"name": "...", "message": "...", "statusCode": ...}}.
 */
public final class ResendResponseClassifier {

	public enum Classification {
		SUCCESS,
		/** 429 for requests/second — transient by definition, retry quickly. */
		BURST_LIMIT,
		/** 429 for the provider's daily/monthly quota — retry much later. */
		DAILY_LIMIT,
		/** Other 4xx: bad payload or config. Retrying cannot fix it. */
		REJECTED,
		/** 5xx, timeouts, network failures — and 3xx, which after redirects
		 * were followed means the endpoint itself moved. */
		UNAVAILABLE
	}

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
