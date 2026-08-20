package io.github.ramossvitor.herald.whatsapp.meta;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Meta signs every webhook POST with HMAC-SHA256 over the raw body, keyed by
 * the app secret, in an {@code X-Hub-Signature-256: sha256=...} header.
 *
 * Two things here are load-bearing rather than stylistic. The digest is taken
 * over the bytes as they arrived — re-serializing parsed JSON changes escaping
 * and whitespace, and the signature would never match. And the comparison is
 * constant-time, because a byte-at-a-time one leaks, over enough attempts,
 * exactly how much of a forged signature was right.
 */
public final class WebhookSignature {

	private static final String ALGORITHM = "HmacSHA256";
	private static final String PREFIX = "sha256=";

	private WebhookSignature() {
	}

	/**
	 * @param header the raw {@code X-Hub-Signature-256} value, or null
	 * @param body the request body exactly as received
	 */
	public static boolean matches(String header, byte[] body, String appSecret) {
		if (header == null || body == null || appSecret == null || appSecret.isEmpty()) {
			return false;
		}
		String hex = header.trim();
		if (!hex.startsWith(PREFIX)) {
			return false;
		}
		byte[] presented = decodeHex(hex.substring(PREFIX.length()));
		if (presented == null) {
			return false;
		}
		byte[] expected;
		try {
			Mac mac = Mac.getInstance(ALGORITHM);
			mac.init(new SecretKeySpec(appSecret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
			expected = mac.doFinal(body);
		}
		catch (Exception ex) {
			return false;
		}
		// MessageDigest.isEqual is the constant-time comparison in the JDK.
		return MessageDigest.isEqual(expected, presented);
	}

	private static byte[] decodeHex(String hex) {
		int length = hex.length();
		if (length == 0 || length % 2 != 0) {
			return null;
		}
		byte[] decoded = new byte[length / 2];
		for (int i = 0; i < length; i += 2) {
			int high = Character.digit(hex.charAt(i), 16);
			int low = Character.digit(hex.charAt(i + 1), 16);
			if (high < 0 || low < 0) {
				return null;
			}
			decoded[i / 2] = (byte) ((high << 4) | low);
		}
		return decoded;
	}
}
