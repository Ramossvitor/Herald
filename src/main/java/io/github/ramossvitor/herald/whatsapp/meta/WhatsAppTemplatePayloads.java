package io.github.ramossvitor.herald.whatsapp.meta;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Meta's {@code message_templates} payload, isolated for testing.
 */
public final class WhatsAppTemplatePayloads {

	/** Positional placeholders, as Meta writes them: {@code {{1}}}, {@code {{2}}}. */
	private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*(\\d{1,2})\\s*}}");

	/** Meta sends this when a template was never rejected. */
	private static final String NO_REJECTION = "NONE";

	private static final ObjectMapper JSON = new ObjectMapper();

	private WhatsAppTemplatePayloads() {
	}

	/**
	 * @param bodyParamCount the highest placeholder index in the body, which is
	 *        how many arguments a send must carry
	 * @param unsupportedParams the template wants a value somewhere Herald does
	 *        not supply one — a header, or a button URL
	 */
	public record Template(String name, String language, String category, String status, int bodyParamCount,
			boolean unsupportedParams, String providerRef, String rejectedReason) {
	}

	public static List<Template> parse(String body) {
		JsonNode root = read(body);
		if (root == null) {
			return List.of();
		}
		JsonNode data = root.path("data");
		if (!data.isArray()) {
			return List.of();
		}
		List<Template> templates = new ArrayList<>(data.size());
		for (JsonNode node : data) {
			String name = text(node, "name");
			String language = text(node, "language");
			String status = text(node, "status");
			// A template without these three cannot be addressed or judged, so
			// there is nothing useful to mirror.
			if (name == null || language == null || status == null) {
				continue;
			}
			templates.add(new Template(name, language, text(node, "category"), status,
					bodyParamCount(node), unsupportedParams(node), text(node, "id"), rejectedReason(node)));
		}
		return templates;
	}

	/** The URL of the next page, or null when this was the last one. */
	public static String nextPage(String body) {
		JsonNode root = read(body);
		if (root == null) {
			return null;
		}
		JsonNode next = root.path("paging").path("next");
		return next.isTextual() ? next.asText() : null;
	}

	/**
	 * The count is the highest index, not the number of matches: a body may use
	 * {@code {{1}}} twice and still take one argument.
	 *
	 * Templates authored with named parameters rather than positional ones count
	 * as zero here. That is deliberate — Herald sends positional arguments, so a
	 * named template would be refused by the arity check rather than sent with
	 * values Meta cannot place.
	 */
	static int bodyParamCount(JsonNode template) {
		JsonNode components = template.path("components");
		if (!components.isArray()) {
			return 0;
		}
		for (JsonNode component : components) {
			if (!"BODY".equalsIgnoreCase(component.path("type").asText(""))) {
				continue;
			}
			String text = component.path("text").asText("");
			int highest = 0;
			Matcher matcher = PLACEHOLDER.matcher(text);
			while (matcher.find()) {
				highest = Math.max(highest, Integer.parseInt(matcher.group(1)));
			}
			return highest;
		}
		return 0;
	}

	/**
	 * Whether the template expects a value Herald has no way to send.
	 *
	 * {@link WhatsAppClient} only ever emits a {@code body} component, so a
	 * variable anywhere else — a text header, a media header, a dynamic button
	 * URL — is a parameter Meta will find missing. Counting only the body would
	 * let such a template pass the arity check and be rejected on delivery, which
	 * is the FAILED row the gate exists to prevent. Recognising it here turns the
	 * same template into a 422 at submission, with a reason.
	 */
	static boolean unsupportedParams(JsonNode template) {
		JsonNode components = template.path("components");
		if (!components.isArray()) {
			return false;
		}
		for (JsonNode component : components) {
			String type = component.path("type").asText("").toUpperCase();
			if ("HEADER".equals(type)) {
				// A non-text header is always a media parameter; a text one is
				// only a parameter when it carries a placeholder.
				String format = component.path("format").asText("TEXT").toUpperCase();
				if (!"TEXT".equals(format) || hasPlaceholder(component.path("text").asText(""))) {
					return true;
				}
			}
			else if ("BUTTONS".equals(type)) {
				for (JsonNode button : component.path("buttons")) {
					if (hasPlaceholder(button.path("url").asText(""))) {
						return true;
					}
				}
			}
		}
		return false;
	}

	private static boolean hasPlaceholder(String text) {
		return PLACEHOLDER.matcher(text).find();
	}

	private static String rejectedReason(JsonNode template) {
		String reason = text(template, "rejected_reason");
		return reason == null || NO_REJECTION.equalsIgnoreCase(reason) ? null : reason;
	}

	private static String text(JsonNode node, String field) {
		JsonNode value = node.path(field);
		return value.isTextual() && !value.asText().isBlank() ? value.asText() : null;
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
