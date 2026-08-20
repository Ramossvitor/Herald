package io.github.ramossvitor.herald.security;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import io.github.ramossvitor.herald.common.HeraldProperties;

/**
 * Encrypts the credentials tenants hand over for their own provider accounts.
 *
 * Every other secret Herald holds is one it issued and only ever compares, so a
 * SHA-256 hash is enough ({@link ApiKeys}). A tenant's WhatsApp token is the
 * opposite: Herald has to present it to Meta on every send, so it must come
 * back out. That rules hashing out and makes encryption at rest the floor.
 *
 * AES-GCM, fresh 96-bit nonce per value, 128-bit tag. The key lives in the
 * environment, never in the database — a dump of the rows is then worth
 * nothing on its own.
 *
 * Every value is additionally bound to what it is, via AAD: one key protects
 * many tenants and more than one kind of credential, and confidentiality alone
 * would still let someone who can write the table move a ciphertext somewhere
 * it does not belong. See {@link #aad}.
 */
@Component
public class Secrets {

	/** Names the scheme, so a later key rotation or algorithm change can tell
	 * stored values apart instead of guessing at them. */
	private static final String PREFIX = "v1:";
	private static final String TRANSFORMATION = "AES/GCM/NoPadding";
	private static final int NONCE_BYTES = 12;
	private static final int TAG_BITS = 128;
	private static final int KEY_BYTES = 32;

	private final SecureRandom random = new SecureRandom();
	private final SecretKeySpec key;

	// Explicit: there are two constructors, so the container cannot pick by
	// arity alone.
	@Autowired
	public Secrets(HeraldProperties properties) {
		this(properties.secretKey());
	}

	/** Direct form, for tests and anything that holds a key without the whole
	 * configuration around it. */
	public Secrets(String base64Key) {
		this.key = parseKey(base64Key);
	}

	private static SecretKeySpec parseKey(String configured) {
		if (configured == null || configured.isBlank()) {
			return null;
		}
		byte[] raw;
		try {
			raw = Base64.getDecoder().decode(configured.trim());
		}
		catch (IllegalArgumentException ex) {
			throw new IllegalStateException("herald.secret-key is not valid base64");
		}
		if (raw.length != KEY_BYTES) {
			throw new IllegalStateException(
					"herald.secret-key must decode to " + KEY_BYTES + " bytes, got " + raw.length);
		}
		return new SecretKeySpec(raw, "AES");
	}

	/**
	 * Without a key, features that store tenant credentials stay off rather
	 * than storing them in the clear.
	 */
	public boolean available() {
		return key != null;
	}

	/**
	 * @param context what this value is, so that it cannot be anything else —
	 *        see {@link #aad}
	 */
	public String encrypt(String plaintext, String context) {
		requireKey();
		// Outside the try: a missing context is a programming error, and the
		// catch below would disguise it as a crypto failure.
		byte[] aad = aad(context);
		byte[] nonce = new byte[NONCE_BYTES];
		random.nextBytes(nonce);
		try {
			Cipher cipher = Cipher.getInstance(TRANSFORMATION);
			cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
			cipher.updateAAD(aad);
			byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
			byte[] packed = new byte[nonce.length + ciphertext.length];
			System.arraycopy(nonce, 0, packed, 0, nonce.length);
			System.arraycopy(ciphertext, 0, packed, nonce.length, ciphertext.length);
			return PREFIX + Base64.getEncoder().encodeToString(packed);
		}
		catch (Exception ex) {
			// The message could carry fragments of what was being encrypted.
			throw new IllegalStateException("could not encrypt secret");
		}
	}

	/**
	 * @param context the same value {@link #encrypt} was given; anything else
	 *        fails the tag, which is the point
	 */
	public String decrypt(String stored, String context) {
		requireKey();
		byte[] aad = aad(context);
		if (stored == null || !stored.startsWith(PREFIX)) {
			throw new IllegalStateException("stored secret is not in a known format");
		}
		byte[] packed;
		try {
			packed = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
		}
		catch (IllegalArgumentException ex) {
			throw new IllegalStateException("stored secret is not valid base64");
		}
		if (packed.length <= NONCE_BYTES) {
			throw new IllegalStateException("stored secret is too short to hold a nonce");
		}
		try {
			Cipher cipher = Cipher.getInstance(TRANSFORMATION);
			cipher.init(Cipher.DECRYPT_MODE, key,
					new GCMParameterSpec(TAG_BITS, packed, 0, NONCE_BYTES));
			cipher.updateAAD(aad);
			byte[] plaintext = cipher.doFinal(packed, NONCE_BYTES, packed.length - NONCE_BYTES);
			return new String(plaintext, StandardCharsets.UTF_8);
		}
		catch (Exception ex) {
			// Wrong key, a moved value, or a tampered row. The caller learns only
			// that it could not be read.
			throw new IllegalStateException("could not decrypt secret");
		}
	}

	/**
	 * Binds a ciphertext to where it lives.
	 *
	 * Without this every value under the key is interchangeable, and the tag
	 * proves only that the bytes are intact — not that they belong here. Anyone
	 * able to write the table could then move one tenant's access token into
	 * another's row and have Herald dispatch under it, or copy a token into the
	 * app-secret column and make webhook verification key off a value the token
	 * holder already knows. Neither is a decryption; both are just a swap. With
	 * the context authenticated, a relocated value fails the tag instead.
	 */
	private static byte[] aad(String context) {
		if (context == null || context.isBlank()) {
			throw new IllegalArgumentException("a secret must say what it is");
		}
		return context.getBytes(StandardCharsets.UTF_8);
	}

	private void requireKey() {
		if (key == null) {
			throw new IllegalStateException("HERALD_SECRET_KEY is not set");
		}
	}
}
