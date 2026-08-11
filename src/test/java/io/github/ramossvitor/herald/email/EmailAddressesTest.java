package io.github.ramossvitor.herald.email;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class EmailAddressesTest {

	@Test
	void onGmailIgnoresDotsAndTags() {
		assertThat(EmailAddresses.canonicalize("vitor.ramos@gmail.com")).isEqualTo("vitorramos@gmail.com");
		assertThat(EmailAddresses.canonicalize("vitorramos+match@gmail.com")).isEqualTo("vitorramos@gmail.com");
		assertThat(EmailAddresses.canonicalize("v.i.t.o.r+a.b@googlemail.com")).isEqualTo("vitor@googlemail.com");
	}

	@Test
	void normalizesWhitespaceAndCaseOnAnyDomain() {
		assertThat(EmailAddresses.canonicalize("  Vitor.Ramos@Gmail.com ")).isEqualTo("vitorramos@gmail.com");
		assertThat(EmailAddresses.canonicalize("Vitor.Ramos@Company.com")).isEqualTo("vitor.ramos@company.com");
	}

	@Test
	void outsideGmailPreservesDotsAndTags() {
		// The dot rule is Gmail's, not email's: elsewhere "a.b@" and "ab@" can
		// be two different people.
		assertThat(EmailAddresses.canonicalize("vitor.ramos@outlook.com")).isEqualTo("vitor.ramos@outlook.com");
		assertThat(EmailAddresses.canonicalize("vitor+tag@company.com.br")).isEqualTo("vitor+tag@company.com.br");
	}

	@Test
	void doesNotInventCanonicalFormsForDomainlessInput() {
		assertThat(EmailAddresses.canonicalize("no-at-sign")).isEqualTo("no-at-sign");
		assertThat(EmailAddresses.canonicalize("@gmail.com")).isEqualTo("@gmail.com");
	}

	@Test
	void localPartThatWouldVanishIsLeftAlone() {
		// Reducing these to "" would make distinct addresses collide.
		assertThat(EmailAddresses.canonicalize("...@gmail.com")).isEqualTo("...@gmail.com");
		assertThat(EmailAddresses.canonicalize("+tag@gmail.com")).isEqualTo("+tag@gmail.com");
	}
}
