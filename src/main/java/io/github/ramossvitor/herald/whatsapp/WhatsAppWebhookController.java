package io.github.ramossvitor.herald.whatsapp;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.JsonNode;

import io.github.ramossvitor.herald.common.HeraldProperties;
import io.github.ramossvitor.herald.whatsapp.meta.WebhookPayloads;
import io.github.ramossvitor.herald.whatsapp.meta.WebhookSignature;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Where Meta reports what happened to a message after it was accepted.
 *
 * This endpoint carries no API key — Meta has none to send. It authenticates
 * every request by HMAC instead, and the order that takes is the delicate part:
 * one callback URL serves every tenant, the app secret that verifies a payload
 * is the tenant's, and the only thing naming the tenant is inside the very
 * payload that has not been verified yet.
 *
 * So the body is parsed before it is trusted, and nothing is acted on until the
 * signature computed with that tenant's secret matches. Parsing to find out who
 * to ask is safe; doing anything with what was parsed, before the answer, is
 * not.
 */
@RestController
@RequestMapping("/webhooks/whatsapp")
public class WhatsAppWebhookController {

	private static final Logger log = LoggerFactory.getLogger(WhatsAppWebhookController.class);
	private static final String SUBSCRIBE = "subscribe";

	/** Orders of magnitude above any real receipt batch; this is a ceiling on
	 * what a stranger can make the process allocate, not a protocol limit. */
	private static final int MAX_BODY_BYTES = 1024 * 1024;

	private final HeraldProperties.WhatsApp properties;
	private final WhatsAppDeliveryReceipts receipts;

	public WhatsAppWebhookController(HeraldProperties properties, WhatsAppDeliveryReceipts receipts) {
		this.properties = properties.whatsapp();
		this.receipts = receipts;
	}

	/**
	 * Meta's subscription handshake. Echoing the challenge is what confirms the
	 * URL is ours; the token is the shared value that stops anyone else from
	 * pointing their app at this instance and having it agree.
	 */
	@GetMapping
	public ResponseEntity<String> verify(
			@RequestParam(name = "hub.mode", required = false) String mode,
			@RequestParam(name = "hub.verify_token", required = false) String token,
			@RequestParam(name = "hub.challenge", required = false) String challenge) {
		String expected = properties.webhookVerifyToken();
		if (expected.isBlank()) {
			// Fail closed: with no token configured, any handshake would pass.
			log.warn("webhook handshake refused: HERALD_WHATSAPP_VERIFY_TOKEN is not set");
			return ResponseEntity.status(403).build();
		}
		if (!SUBSCRIBE.equals(mode) || token == null || challenge == null || !constantTimeEquals(expected, token)) {
			return ResponseEntity.status(403).build();
		}
		return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body(challenge);
	}

	/**
	 * The body arrives as bytes and stays that way through verification: the
	 * signature is over what Meta sent, and any re-serialization would change
	 * escaping or key order enough to never match.
	 *
	 * It is read with a cap rather than bound as a parameter. This is the only
	 * unauthenticated endpoint that takes a body, so letting the container buffer
	 * whatever arrives would hand any stranger a way to fill the heap and take
	 * every channel down with it.
	 */
	@PostMapping
	public ResponseEntity<Void> receive(HttpServletRequest request,
			@RequestHeader(name = "X-Hub-Signature-256", required = false) String signature) throws IOException {
		byte[] body = readBounded(request);
		if (body == null) {
			log.warn("webhook rejected: body exceeds {} bytes", MAX_BODY_BYTES);
			return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).build();
		}
		if (body.length == 0) {
			return ResponseEntity.badRequest().build();
		}

		// Parsed once and reused: the tree is built from unauthenticated input.
		JsonNode payload = WebhookPayloads.parse(body);
		Set<String> wabaIds = WebhookPayloads.wabaIds(payload);
		if (wabaIds.size() != 1) {
			// Zero: not a payload we understand. More than one: it would have to
			// be verified against several secrets and acted on across tenants.
			log.warn("webhook rejected: payload names {} business accounts", wabaIds.size());
			return ResponseEntity.badRequest().build();
		}
		String wabaId = wabaIds.iterator().next();

		WhatsAppDeliveryReceipts.Recipient recipient = receipts.resolve(wabaId);
		if (recipient == null) {
			// 200, not 404, for two reasons. Meta retries anything that is not
			// 2xx, and this URL is shared by every tenant — receipts for an
			// account someone deregistered would otherwise retry forever and can
			// get the whole subscription throttled. And answering differently
			// here than for a bad signature would tell an unauthenticated caller
			// which businesses are customers.
			log.warn("webhook for an unknown WhatsApp Business Account");
			return ResponseEntity.ok().build();
		}
		if (!WebhookSignature.matches(signature, body, recipient.appSecret())) {
			// Nothing has been touched at this point, and nothing will be.
			log.warn("webhook signature did not verify for tenant {}", recipient.tenantId());
			return ResponseEntity.status(403).build();
		}

		receipts.apply(recipient, WebhookPayloads.statuses(payload, wabaId));
		return ResponseEntity.ok().build();
	}

	/** @return the body, or null when it is larger than {@link #MAX_BODY_BYTES} */
	private static byte[] readBounded(HttpServletRequest request) throws IOException {
		if (request.getContentLengthLong() > MAX_BODY_BYTES) {
			return null;
		}
		try (InputStream in = request.getInputStream()) {
			// One byte past the cap, so a chunked body that declares no length is
			// caught by what it actually sent rather than by what it claimed.
			byte[] body = in.readNBytes(MAX_BODY_BYTES + 1);
			return body.length > MAX_BODY_BYTES ? null : body;
		}
	}

	private static boolean constantTimeEquals(String expected, String presented) {
		return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
				presented.getBytes(StandardCharsets.UTF_8));
	}
}
