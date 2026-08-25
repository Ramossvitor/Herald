package io.github.ramossvitor.herald.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.ramossvitor.herald.outbox.DeliveryState;
import io.github.ramossvitor.herald.suppression.SuppressionReason;

/**
 * Resend's webhook payload contract, isolated for testing — the same shape as
 * {@code ResendDomainPayloads} does for the domains API.
 *
 * <pre>
 * {"type":"email.bounced","created_at":"...","data":{
 *    "email_id":"...","to":["a@b"],
 *    "bounce":{"type":"Permanent","subType":"Suppressed","message":"..."}}}
 * </pre>
 */
public final class ResendEventPayloads {

	private static final ObjectMapper JSON = new ObjectMapper();

	/** Long enough to explain the event, short enough not to fill the row. */
	private static final int MAX_DETAIL_LENGTH = 500;

	private ResendEventPayloads() {
	}

	/**
	 * What one event means to Herald.
	 *
	 * @param state       the delivery state it records, null when the event is
	 *                    one we do not track
	 * @param suppression the reason to stop mailing the address, null when the
	 *                    event is not grounds for that
	 */
	public record Event(String type, String providerMessageId, DeliveryState state, SuppressionReason suppression,
			String detail) {

		public boolean tracked() {
			return state != null;
		}
	}

	public static Event parse(String body) {
		JsonNode root = read(body);
		if (root == null) {
			return null;
		}
		String type = text(root, "type");
		if (type == null) {
			return null;
		}
		JsonNode data = root.get("data");
		String emailId = data == null ? null : text(data, "email_id");

		return switch (type) {
			case "email.delivered" -> new Event(type, emailId, DeliveryState.DELIVERED, null, null);
			case "email.bounced" -> bounced(type, emailId, data);
			case "email.complained" -> new Event(type, emailId, DeliveryState.COMPLAINED, SuppressionReason.COMPLAINED,
					"recipient marked the message as spam");
			case "email.delivery_delayed" -> new Event(type, emailId, DeliveryState.DELAYED, null,
					detailOf(data, "reason"));
			case "email.failed" -> new Event(type, emailId, DeliveryState.FAILED_AT_PROVIDER, null,
					detailOf(data, "reason"));
			// Everything else — sent, opened, clicked, scheduled, contact
			// events — is recognised as a valid delivery and ignored.
			default -> new Event(type, emailId, null, null, null);
		};
	}

	/**
	 * Only a permanent bounce suppresses. A transient one is a full mailbox or
	 * a greylisting server — exactly the case the outbox already retries — and
	 * an undetermined one is the provider saying it does not know, which is not
	 * evidence enough to cut an address off for good.
	 */
	private static Event bounced(String type, String emailId, JsonNode data) {
		JsonNode bounce = data == null ? null : data.get("bounce");
		String bounceType = bounce == null ? null : text(bounce, "type");
		String detail = truncate(describeBounce(bounce, bounceType));
		SuppressionReason suppression = "Permanent".equalsIgnoreCase(bounceType) ? SuppressionReason.BOUNCED : null;
		return new Event(type, emailId, DeliveryState.BOUNCED, suppression, detail);
	}

	private static String describeBounce(JsonNode bounce, String bounceType) {
		if (bounce == null) {
			return null;
		}
		String message = text(bounce, "message");
		String subType = text(bounce, "subType");
		StringBuilder detail = new StringBuilder();
		if (bounceType != null) {
			detail.append(bounceType);
			if (subType != null) {
				detail.append('/').append(subType);
			}
		}
		if (message != null) {
			if (!detail.isEmpty()) {
				detail.append(": ");
			}
			detail.append(message);
		}
		return detail.isEmpty() ? null : detail.toString();
	}

	private static String detailOf(JsonNode data, String field) {
		return data == null ? null : truncate(text(data, field));
	}

	private static String text(JsonNode node, String name) {
		JsonNode value = node.get(name);
		return value != null && value.isTextual() ? value.asText() : null;
	}

	private static String truncate(String value) {
		if (value == null) {
			return null;
		}
		return value.length() <= MAX_DETAIL_LENGTH ? value : value.substring(0, MAX_DETAIL_LENGTH);
	}

	private static JsonNode read(String body) {
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
