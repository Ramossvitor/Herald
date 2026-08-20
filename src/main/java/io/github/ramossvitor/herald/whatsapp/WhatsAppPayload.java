package io.github.ramossvitor.herald.whatsapp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.ramossvitor.herald.outbox.Message;

/**
 * The shape of {@code messages.payload} on this channel, in one place so that
 * the side that writes it and the side that reads it cannot drift apart.
 *
 * A WhatsApp message is not content, it is a reference: the words were approved
 * by Meta ahead of time and Herald only supplies the blanks.
 */
public final class WhatsAppPayload {

	static final String TEMPLATE = "template";
	static final String LANGUAGE = "language";
	static final String PARAMS = "params";

	private WhatsAppPayload() {
	}

	public static Map<String, Object> of(String template, String language, List<String> params) {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put(TEMPLATE, template);
		payload.put(LANGUAGE, language);
		// Always present, even empty: reading it back should not have to tell
		// "no parameters" apart from "an older payload that did not say".
		payload.put(PARAMS, List.copyOf(params));
		return payload;
	}

	public static String template(Message message) {
		return message.payloadText(TEMPLATE);
	}

	public static String language(Message message) {
		return message.payloadText(LANGUAGE);
	}

	public static List<String> params(Message message) {
		return message.payloadTextList(PARAMS);
	}
}
