package io.github.ramossvitor.herald.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ApiKeysTest {

	@Test
	void generatesPrefixedKeyWithStableHash() {
		ApiKeys.Generated generated = ApiKeys.generate();
		assertThat(generated.plaintext()).startsWith("hrl_live_");
		// 9-char prefix + 43 chars of base64url for 32 random bytes.
		assertThat(generated.plaintext()).hasSize(52);
		assertThat(generated.displayPrefix()).isEqualTo(generated.plaintext().substring(0, 13));
		assertThat(generated.hash()).isEqualTo(ApiKeys.sha256Hex(generated.plaintext()));
	}

	@Test
	void generatedKeysAreUnique() {
		assertThat(ApiKeys.generate().plaintext()).isNotEqualTo(ApiKeys.generate().plaintext());
	}

	@Test
	void hashIsHexSha256() {
		// echo -n "abc" | sha256sum
		assertThat(ApiKeys.sha256Hex("abc"))
				.isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
	}

	@Test
	void hashesMatchComparesExactly() {
		assertThat(ApiKeys.hashesMatch("abc", "abc")).isTrue();
		assertThat(ApiKeys.hashesMatch("abc", "abd")).isFalse();
		assertThat(ApiKeys.hashesMatch("abc", "ab")).isFalse();
	}
}
