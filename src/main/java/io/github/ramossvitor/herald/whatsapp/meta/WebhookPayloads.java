package io.github.ramossvitor.herald.whatsapp.meta;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Meta's webhook payload, isolated for testing.
 *
 * Everything read here comes from an unauthenticated request: the WABA id is
 * pulled out precisely so the right app secret can be found to authenticate the
 * rest. So nothing in this class may have a side effect, and the caller must
 * treat what it returns as a claim until the signature checks out.
 */
public final class WebhookPayloads {

	/** Meta's terminal delivery failure. */
	public static final String FAILED = "failed";

	private static final ObjectMapper JSON = new ObjectMapper();

	private WebhookPayloads() {
	}

	public record DeliveryStatus(String providerMessageId, String status, Integer errorCode, String errorTitle) {

		public boolean isFailure() {
			return FAILED.equalsIgnoreCase(status);
		}

		/** What lands in {@code last_error}: Meta's code and title, never the
		 * raw payload. */
		public String describe() {
			StringBuilder description = new StringBuilder("delivery failed");
			if (errorCode != null) {
				description.append(" code ").append(errorCode);
			}
			if (errorTitle != null) {
				description.append(": ").append(errorTitle);
			}
			return description.toString();
		}
	}

	/**
	 * The tree, or null when the bytes are not JSON.
	 *
	 * Parsed once and handed to both readers below: the body is unauthenticated
	 * and arbitrarily large, and building the tree twice would double what an
	 * attacker gets for one request.
	 */
	public static JsonNode parse(byte[] body) {
		if (body == null || body.length == 0) {
			return null;
		}
		try {
			return JSON.readTree(body);
		}
		catch (Exception ex) {
			return null;
		}
	}

	/**
	 * The WhatsApp Business Account ids named by the payload.
	 *
	 * Returned as a set rather than the first one, so the caller can refuse a
	 * body that names more than one. Authenticating against one tenant's secret
	 * and then acting on another's messages is exactly the confusion the single
	 * shared callback URL invites.
	 */
	public static Set<String> wabaIds(JsonNode root) {
		if (root == null) {
			return Set.of();
		}
		Set<String> ids = new LinkedHashSet<>();
		for (JsonNode entry : root.path("entry")) {
			JsonNode id = entry.path("id");
			if (id.isTextual() && !id.asText().isBlank()) {
				ids.add(id.asText());
			}
		}
		return ids;
	}

	/**
	 * The receipts belonging to {@code wabaId}, and only those.
	 *
	 * The account id is required rather than implied: {@link #wabaIds} ignores an
	 * entry that carries no id, so a body pairing one named entry with several
	 * anonymous ones would pass a "names exactly one account" check and still
	 * have the anonymous receipts acted on. Filtering here is what makes the
	 * caller's single-tenant invariant true of the data, not just of the routing
	 * decision.
	 */
	public static List<DeliveryStatus> statuses(JsonNode root, String wabaId) {
		if (root == null || wabaId == null) {
			return List.of();
		}
		List<DeliveryStatus> statuses = new ArrayList<>();
		for (JsonNode entry : root.path("entry")) {
			if (!wabaId.equals(text(entry, "id"))) {
				continue;
			}
			for (JsonNode change : entry.path("changes")) {
				for (JsonNode status : change.path("value").path("statuses")) {
					String id = text(status, "id");
					String state = text(status, "status");
					if (id == null || state == null) {
						continue;
					}
					JsonNode error = status.path("errors").path(0);
					Integer code = error.path("code").isInt() ? error.path("code").asInt() : null;
					statuses.add(new DeliveryStatus(id, state, code, text(error, "title")));
				}
			}
		}
		return statuses;
	}

	private static String text(JsonNode node, String field) {
		JsonNode value = node.path(field);
		return value.isTextual() && !value.asText().isBlank() ? value.asText() : null;
	}
}
