package io.github.ramossvitor.herald.webhook;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;

import io.github.ramossvitor.herald.common.HeraldProperties;

/**
 * Verifies Svix-signed webhook requests, which is the scheme Resend uses.
 *
 * This is the only thing standing between a public endpoint and a suppression
 * list anyone could write to: forging an {@code email.bounced} for an address
 * would let an attacker stop a tenant mailing it. So the whole check is here,
 * self-contained and testable against fixed vectors, rather than spread across
 * the controller.
 *
 * The scheme: HMAC-SHA256 over {@code {svix-id}.{svix-timestamp}.{raw body}},
 * keyed by the base64-decoded secret, encoded base64. The signature header
 * carries space-delimited, version-prefixed signatures ({@code v1,<sig>}), of
 * which any one matching is enough — that is what lets a secret be rotated
 * without dropping deliveries.
 */
@Component
public class SvixSignatureVerifier {

	private static final String HMAC = "HmacSHA256";
	private static final String SECRET_PREFIX = "whsec_";
	private static final String SUPPORTED_VERSION = "v1";

	/**
	 * How far the provider's timestamp may be from ours. Bounds replay of a
	 * captured request to this window; five minutes is Svix's own default and
	 * absorbs ordinary clock skew between two hosts.
	 */
	static final Duration TOLERANCE = Duration.ofMinutes(5);

	public enum Result {
		VALID,
		/** No signature matched — wrong secret, or a forgery. */
		BAD_SIGNATURE,
		/** Correctly signed, but too old or too far in the future to accept. */
		STALE_TIMESTAMP,
		/** Headers missing or unparseable; never reached the cryptography. */
		MALFORMED,
		/** No secret configured — the endpoint cannot verify anything. */
		NOT_CONFIGURED
	}

	private final HeraldProperties.Resend properties;
	private final Clock clock;

	public SvixSignatureVerifier(HeraldProperties properties, Clock clock) {
		this.properties = properties.resend();
		this.clock = clock;
	}

	public boolean configured() {
		return !properties.webhookSecret().isBlank();
	}

	public Result verify(String svixId, String svixTimestamp, String svixSignature, String rawBody) {
		if (!configured()) {
			return Result.NOT_CONFIGURED;
		}
		if (svixId == null || svixTimestamp == null || svixSignature == null || rawBody == null) {
			return Result.MALFORMED;
		}

		byte[] key;
		try {
			key = decodeSecret(properties.webhookSecret());
		}
		catch (IllegalArgumentException ex) {
			// A misconfigured secret must not read as a forged request: the
			// operator has to be able to tell those two apart.
			return Result.NOT_CONFIGURED;
		}

		// The signature is checked before the clock. A stale-but-authentic
		// request and an unsigned one are different problems, and reporting the
		// timestamp verdict on unverified input would answer questions about
		// data we have no reason to trust.
		String expected = sign(key, svixId + "." + svixTimestamp + "." + rawBody);
		if (!anyMatches(svixSignature, expected)) {
			return Result.BAD_SIGNATURE;
		}
		if (!withinTolerance(svixTimestamp)) {
			return Result.STALE_TIMESTAMP;
		}
		return Result.VALID;
	}

	/**
	 * The key is the base64 payload of the secret, not its characters. The
	 * prefix is optional here so a secret pasted without it still works.
	 */
	private static byte[] decodeSecret(String secret) {
		String encoded = secret.startsWith(SECRET_PREFIX) ? secret.substring(SECRET_PREFIX.length()) : secret;
		return Base64.getDecoder().decode(encoded);
	}

	private static String sign(byte[] key, String signedContent) {
		try {
			Mac mac = Mac.getInstance(HMAC);
			mac.init(new SecretKeySpec(key, HMAC));
			return Base64.getEncoder()
					.encodeToString(mac.doFinal(signedContent.getBytes(StandardCharsets.UTF_8)));
		}
		catch (java.security.GeneralSecurityException ex) {
			throw new IllegalStateException("HMAC-SHA256 unavailable", ex);
		}
	}

	/**
	 * The header holds every signature the provider considers current, so a
	 * match on any one is a pass. Versions other than v1 are skipped rather
	 * than failed: a future scheme we cannot check is not a bad signature.
	 */
	private static boolean anyMatches(String header, String expected) {
		byte[] expectedBytes = expected.getBytes(StandardCharsets.UTF_8);
		boolean matched = false;
		for (String entry : header.split(" ")) {
			int comma = entry.indexOf(',');
			if (comma < 0 || !SUPPORTED_VERSION.equals(entry.substring(0, comma))) {
				continue;
			}
			// No early return: comparing every candidate keeps the work done
			// independent of which one matches, alongside the constant-time
			// comparison itself.
			if (MessageDigest.isEqual(entry.substring(comma + 1).getBytes(StandardCharsets.UTF_8), expectedBytes)) {
				matched = true;
			}
		}
		return matched;
	}

	private boolean withinTolerance(String svixTimestamp) {
		long epochSeconds;
		try {
			epochSeconds = Long.parseLong(svixTimestamp.trim());
		}
		catch (NumberFormatException ex) {
			return false;
		}
		Duration drift = Duration.between(Instant.ofEpochSecond(epochSeconds), clock.instant());
		return drift.abs().compareTo(TOLERANCE) <= 0;
	}
}
