package io.github.ramossvitor.herald.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Key format: {@code hrl_live_} + 256 bits of randomness, base64url. The
 * database stores only the SHA-256 of the full plaintext, plus a short prefix
 * so a human can tell keys apart in listings.
 */
public final class ApiKeys {

	public static final String LIVE_PREFIX = "hrl_live_";
	private static final int RANDOM_BYTES = 32;
	private static final int DISPLAY_PREFIX_LENGTH = LIVE_PREFIX.length() + 4;

	private static final SecureRandom RANDOM = new SecureRandom();

	private ApiKeys() {
	}

	public record Generated(String plaintext, String hash, String displayPrefix) {
	}

	public static Generated generate() {
		byte[] bytes = new byte[RANDOM_BYTES];
		RANDOM.nextBytes(bytes);
		String plaintext = LIVE_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
		return new Generated(plaintext, sha256Hex(plaintext), plaintext.substring(0, DISPLAY_PREFIX_LENGTH));
	}

	public static String sha256Hex(String value) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 unavailable", ex);
		}
	}

	/**
	 * Constant-time comparison. The database lookup by hash already defeats
	 * timing probes; this keeps the admin-key path equally careful.
	 */
	public static boolean hashesMatch(String left, String right) {
		return MessageDigest.isEqual(
				left.getBytes(StandardCharsets.UTF_8),
				right.getBytes(StandardCharsets.UTF_8));
	}
}
