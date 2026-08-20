package io.github.ramossvitor.herald.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;

import org.junit.jupiter.api.Test;

class SecretsTest {

	private static final String KEY = "aGVyYWxkLXRlc3Qta2V5LTAxMjM0NTY3ODlhYmNkZWY=";
	private static final String OTHER_KEY = Base64.getEncoder()
			.encodeToString("a-completely-different-32-byte-k".getBytes());

	private static final String CONTEXT = "3f1b0c22-0000-4000-8000-000000000001:access_token";

	private final Secrets secrets = new Secrets(KEY);

	@Test
	void roundTripsAValue() {
		String token = "EAAG1234567890abcdefghijklmnop";
		assertThat(secrets.decrypt(secrets.encrypt(token, CONTEXT), CONTEXT)).isEqualTo(token);
	}

	@Test
	void theSameValueEncryptsDifferentlyEveryTime() {
		// A fresh nonce per value. Without it, equal ciphertexts would tell an
		// onlooker with database access which tenants share a token — and GCM
		// with a reused nonce is broken outright, not merely leaky.
		String first = secrets.encrypt("same-token", CONTEXT);
		String second = secrets.encrypt("same-token", CONTEXT);
		assertThat(first).isNotEqualTo(second);
		assertThat(secrets.decrypt(first, CONTEXT)).isEqualTo(secrets.decrypt(second, CONTEXT));
	}

	@Test
	void anotherKeyCannotRead() {
		String sealed = secrets.encrypt("tenant-token", CONTEXT);
		assertThatThrownBy(() -> new Secrets(OTHER_KEY).decrypt(sealed, CONTEXT))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageNotContaining("tenant-token");
	}

	@Test
	void tamperingIsDetected() {
		// GCM authenticates as well as encrypts: a flipped byte in a row someone
		// edited must fail, not decrypt to something else.
		String sealed = secrets.encrypt("tenant-token", CONTEXT);
		byte[] raw = Base64.getDecoder().decode(sealed.substring("v1:".length()));
		raw[raw.length - 1] ^= 0x01;
		String tampered = "v1:" + Base64.getEncoder().encodeToString(raw);

		assertThatThrownBy(() -> secrets.decrypt(tampered, CONTEXT)).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void aValueCannotBeMovedSomewhereItDoesNotBelong() {
		// The threat this closes: one key protects every tenant and both kinds of
		// credential, so without binding, anyone able to write the table could
		// copy tenant A's access token into tenant B's row and have Herald
		// dispatch under it — no decryption needed, just a swap.
		String tenantA = "3f1b0c22-0000-4000-8000-00000000000a";
		String tenantB = "3f1b0c22-0000-4000-8000-00000000000b";
		String sealed = secrets.encrypt("tenant-a-token", tenantA + ":access_token");

		assertThat(secrets.decrypt(sealed, tenantA + ":access_token")).isEqualTo("tenant-a-token");
		// ...in another tenant's row.
		assertThatThrownBy(() -> secrets.decrypt(sealed, tenantB + ":access_token"))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageNotContaining("tenant-a-token");
		// ...or in the other column of its own row, where it would become the
		// secret that verifies webhooks.
		assertThatThrownBy(() -> secrets.decrypt(sealed, tenantA + ":app_secret"))
				.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void aSecretHasToSayWhatItIs() {
		// No silent empty default: an unbound value is exactly the case above.
		assertThatThrownBy(() -> secrets.encrypt("token", null))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> secrets.encrypt("token", "  "))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void failuresNeverEchoTheSecret() {
		// The message ends up in logs. It must describe the failure, not the
		// value that failed.
		assertThatThrownBy(() -> secrets.decrypt("v1:not-base64!!", CONTEXT))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageNotContaining("not-base64");
		assertThatThrownBy(() -> secrets.decrypt("plaintext-token", CONTEXT))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageNotContaining("plaintext-token");
	}

	@Test
	void withoutAKeyNothingIsStored() {
		Secrets none = new Secrets("");
		assertThat(none.available()).isFalse();
		// Refusing beats silently writing a token in the clear.
		assertThatThrownBy(() -> none.encrypt("token", CONTEXT)).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void aKeyOfTheWrongSizeIsRefusedAtStartup() {
		// Loudly, at boot, rather than on the first tenant that registers.
		assertThatThrownBy(() -> new Secrets(Base64.getEncoder().encodeToString("too-short".getBytes())))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("32 bytes");
		assertThatThrownBy(() -> new Secrets("this is not base64 at all"))
				.isInstanceOf(IllegalStateException.class);
	}
}
