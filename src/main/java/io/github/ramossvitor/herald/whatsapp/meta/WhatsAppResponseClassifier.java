package io.github.ramossvitor.herald.whatsapp.meta;

import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.ramossvitor.herald.outbox.Classification;

/**
 * Meta's status contract, isolated for testing. Errors look like
 * {@code {"error": {"message": "...", "code": 131048, "error_subcode": ...}}},
 * and the HTTP status alone is not enough to decide: the same 4xx covers a
 * throughput ceiling that clears in a second and a template name that will
 * never exist.
 */
public final class WhatsAppResponseClassifier {

	/**
	 * Hitting the spam rate limit is a quality signal, not a burst: Meta is
	 * saying this number is sending more than its recipients tolerate. Retrying
	 * in seconds makes it worse, so it takes the patient path.
	 */
	private static final int SPAM_RATE_LIMIT = 131048;

	/**
	 * 4xx codes that are really outages wearing a client-error status. Anything
	 * outside this set is treated as the caller's problem, because retrying a
	 * genuine rejection burns the tenant's attempts for nothing.
	 */
	private static final Set<Integer> TRANSIENT_4XX = Set.of(
			131000, // "Something went wrong" — Meta's own catch-all
			133016, // temporarily unavailable, retry later
			131056); // pair rate limit between this number and one recipient

	/** Meta's code for an expired, revoked or otherwise unusable access token. */
	private static final int AUTH_ERROR = 190;

	/**
	 * An expired or rotated token is the tenant's to fix, not the message's to
	 * die of. Unlike the email channel — where the credential is operator-owned
	 * and {@code configured()} gates every send before one is spent — a WhatsApp
	 * token belongs to the tenant and expires on its own schedule. Treating that
	 * as a rejection would terminally fail an entire backlog in one pass, for a
	 * condition that a re-registration fixes in a minute.
	 */
	public static boolean isAuthFailure(int status, String body) {
		if (status == 401 || status == 403) {
			return true;
		}
		Integer code = errorCode(body);
		return code != null && code == AUTH_ERROR;
	}

	private static final ObjectMapper JSON = new ObjectMapper();

	private WhatsAppResponseClassifier() {
	}

	public static Classification classify(int status, String body) {
		if (status >= 200 && status < 300) {
			return Classification.SUCCESS;
		}
		Integer code = errorCode(body);
		if (status == 429) {
			return code != null && code == SPAM_RATE_LIMIT ? Classification.DAILY_LIMIT : Classification.BURST_LIMIT;
		}
		if (status >= 400 && status < 500) {
			// Auth failures back off rather than dying: see isAuthFailure.
			return isAuthFailure(status, body) || (code != null && TRANSIENT_4XX.contains(code))
					? Classification.UNAVAILABLE
					: Classification.REJECTED;
		}
		return Classification.UNAVAILABLE;
	}

	/** {@code messages[0].id} — the wamid a delivery receipt will refer to. */
	public static String providerMessageId(String body) {
		JsonNode root = parse(body);
		if (root == null) {
			return null;
		}
		JsonNode id = root.path("messages").path(0).path("id");
		return id.isTextual() ? id.asText() : null;
	}

	/**
	 * A short, safe description for {@code last_error}: Meta's own code and
	 * message, never the raw body. The body is reproduced in no log and no API
	 * response, so nothing that arrives in it can end up somewhere it should
	 * not be.
	 */
	public static String describe(int status, String body) {
		StringBuilder description = new StringBuilder("http ").append(status);
		JsonNode root = parse(body);
		if (root == null) {
			return description.toString();
		}
		JsonNode code = root.path("error").path("code");
		if (code.isInt()) {
			description.append(" code ").append(code.asInt());
		}
		JsonNode message = root.path("error").path("message");
		if (message.isTextual()) {
			description.append(": ").append(message.asText());
		}
		return description.toString();
	}

	private static Integer errorCode(String body) {
		JsonNode root = parse(body);
		if (root == null) {
			return null;
		}
		JsonNode code = root.path("error").path("code");
		return code.isInt() ? code.asInt() : null;
	}

	private static JsonNode parse(String body) {
		if (body == null || body.isEmpty()) {
			return null;
		}
		try {
			return JSON.readTree(body);
		}
		catch (Exception ex) {
			return null;
		}
	}
}
