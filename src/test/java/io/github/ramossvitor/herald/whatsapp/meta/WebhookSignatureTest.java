package io.github.ramossvitor.herald.whatsapp.meta;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;

class WebhookSignatureTest {

	private static final String SECRET = "app-secret-of-the-tenant";
	private static final byte[] BODY = "{\"entry\":[{\"id\":\"waba-1\"}]}".getBytes(StandardCharsets.UTF_8);

	private static String sign(byte[] body, String secret) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
			return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
		}
		catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	@Test
	void acceptsWhatMetaWouldSend() {
		assertThat(WebhookSignature.matches(sign(BODY, SECRET), BODY, SECRET)).isTrue();
	}

	@Test
	void uppercaseHexIsStillTheSameSignature() {
		assertThat(WebhookSignature.matches(sign(BODY, SECRET).toUpperCase().replace("SHA256=", "sha256="),
				BODY, SECRET)).isTrue();
	}

	@Test
	void rejectsABodyChangedAfterSigning() {
		// The whole point: a payload edited in flight must not verify.
		byte[] tampered = "{\"entry\":[{\"id\":\"waba-2\"}]}".getBytes(StandardCharsets.UTF_8);
		assertThat(WebhookSignature.matches(sign(BODY, SECRET), tampered, SECRET)).isFalse();
	}

	@Test
	void rejectsAnotherTenantsSecret() {
		// One callback URL serves every tenant, so a payload signed by one app
		// must never verify against another's secret.
		assertThat(WebhookSignature.matches(sign(BODY, "another-tenants-secret"), BODY, SECRET)).isFalse();
	}

	@Test
	void rejectsAnythingMalformed() {
		String valid = sign(BODY, SECRET);
		assertThat(WebhookSignature.matches(null, BODY, SECRET)).isFalse();
		assertThat(WebhookSignature.matches("", BODY, SECRET)).isFalse();
		// No prefix, so nothing says which algorithm was claimed.
		assertThat(WebhookSignature.matches(valid.substring("sha256=".length()), BODY, SECRET)).isFalse();
		assertThat(WebhookSignature.matches("sha256=", BODY, SECRET)).isFalse();
		assertThat(WebhookSignature.matches("sha256=nothex", BODY, SECRET)).isFalse();
		// Odd length cannot be bytes.
		assertThat(WebhookSignature.matches("sha256=abc", BODY, SECRET)).isFalse();
		// A truncated but otherwise correct prefix must not pass either.
		assertThat(WebhookSignature.matches(valid.substring(0, valid.length() - 2), BODY, SECRET)).isFalse();
	}

	@Test
	void rejectsWhenThereIsNoSecretToVerifyWith() {
		assertThat(WebhookSignature.matches(sign(BODY, SECRET), BODY, null)).isFalse();
		assertThat(WebhookSignature.matches(sign(BODY, SECRET), BODY, "")).isFalse();
	}
}
