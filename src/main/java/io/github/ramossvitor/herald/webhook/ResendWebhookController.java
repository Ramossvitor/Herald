package io.github.ramossvitor.herald.webhook;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Where the provider tells Herald what happened to a message after it was
 * accepted.
 *
 * Unauthenticated by bearer token — the provider has no API key — so the
 * signature is the whole of the authentication. Nothing before
 * {@link SvixSignatureVerifier} returns VALID may touch the database.
 *
 * Answering non-2xx makes the provider redeliver, so the only failures worth
 * reporting are the ones a redelivery could fix. A payload that is authentic
 * but unusable is not one of them: it would come back identical, forever.
 */
@RestController
@RequestMapping("/v1/webhooks/resend")
public class ResendWebhookController {

	private static final Logger log = LoggerFactory.getLogger(ResendWebhookController.class);

	/**
	 * Real events are a few kilobytes. The cap matters because this is the one
	 * endpoint that answers without a key: whoever posts here has already made
	 * the service buy the memory by the time the signature is looked at, so the
	 * body has to be bounded before it is read rather than after.
	 */
	private static final int MAX_BODY_BYTES = 256 * 1024;

	private final SvixSignatureVerifier verifier;
	private final WebhookService service;
	private final MeterRegistry metrics;

	public ResendWebhookController(SvixSignatureVerifier verifier, WebhookService service, MeterRegistry metrics) {
		this.verifier = verifier;
		this.service = service;
		this.metrics = metrics;
	}

	/**
	 * The body is read as raw bytes, not a mapped object: the signature covers
	 * the exact bytes sent, and letting Jackson parse and re-serialize would
	 * change them. Parsing happens after verification.
	 *
	 * It is read here rather than bound with {@code @RequestBody} because Spring
	 * resolves that argument before the method runs, which is too late to refuse
	 * one that is too big — the reading is the cost.
	 */
	@PostMapping
	public ResponseEntity<Void> receive(
			@RequestHeader(name = "svix-id", required = false) String svixId,
			@RequestHeader(name = "svix-timestamp", required = false) String svixTimestamp,
			@RequestHeader(name = "svix-signature", required = false) String svixSignature,
			HttpServletRequest request) throws IOException {

		String rawBody = readBounded(request);
		if (rawBody == null) {
			return tooLarge();
		}

		SvixSignatureVerifier.Result verification = verifier.verify(svixId, svixTimestamp, svixSignature, rawBody);
		if (verification != SvixSignatureVerifier.Result.VALID) {
			return reject(verification);
		}

		WebhookService.Outcome outcome;
		try {
			outcome = service.process(svixId, rawBody);
		}
		catch (DataIntegrityViolationException ex) {
			// Two deliveries of the same event racing each other: the loser's
			// insert on the svix-id primary key lost. That is the guarantee
			// working, not an error.
			outcome = WebhookService.Outcome.DUPLICATE;
		}

		metrics.counter("herald.webhooks.received", "outcome", tag(outcome)).increment();
		return ResponseEntity.ok().build();
	}

	/**
	 * Reads the body, refusing anything past the cap. A declared length over it
	 * is turned away without reading a byte; the streaming check behind that
	 * covers a chunked request, which declares no length at all, and a
	 * {@code Content-Length} that simply lied.
	 *
	 * @return the body, or null when it is too large to accept
	 */
	private static String readBounded(HttpServletRequest request) throws IOException {
		if (request.getContentLengthLong() > MAX_BODY_BYTES) {
			return null;
		}
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		byte[] chunk = new byte[8192];
		// Not closed: the container owns the stream for the life of the request.
		InputStream in = request.getInputStream();
		int read;
		while ((read = in.read(chunk)) != -1) {
			if (body.size() + read > MAX_BODY_BYTES) {
				return null;
			}
			body.write(chunk, 0, read);
		}
		// UTF-8 explicitly, because the signature is over the bytes and the
		// round trip through a String has to give them back unchanged.
		return body.toString(StandardCharsets.UTF_8);
	}

	private ResponseEntity<Void> tooLarge() {
		metrics.counter("herald.webhooks.rejected", "reason", "too_large").increment();
		log.warn("rejected webhook: body exceeds {} bytes", MAX_BODY_BYTES);
		// 413, not 401: the request was never authentic or otherwise, because
		// nothing about it was examined.
		return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).build();
	}

	private ResponseEntity<Void> reject(SvixSignatureVerifier.Result verification) {
		metrics.counter("herald.webhooks.rejected", "reason", tag(verification)).increment();
		if (verification == SvixSignatureVerifier.Result.NOT_CONFIGURED) {
			// Fail closed, like the admin surface without its key. 503 rather
			// than 401 because the fault is the operator's, and it tells the
			// provider to try again once the secret is in place.
			log.warn("webhook received but no webhook secret is configured — ignoring");
			return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
		}
		log.warn("rejected webhook: {}", verification);
		return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
	}

	private static String tag(Enum<?> value) {
		return value.name().toLowerCase(Locale.ROOT);
	}
}
