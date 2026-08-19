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

	@Test
	void addrSpecHandlesBareAndDisplayNameForms() {
		assertThat(EmailAddresses.addrSpec("mail@acme.example")).isEqualTo("mail@acme.example");
		assertThat(EmailAddresses.addrSpec("Acme <Mail@Acme.example>")).isEqualTo("mail@acme.example");
		assertThat(EmailAddresses.addrSpec("  spaced@acme.example  ")).isEqualTo("spaced@acme.example");
		assertThat(EmailAddresses.addrSpec("no-at-sign")).isNull();
		assertThat(EmailAddresses.addrSpec("Acme <broken@>")).isNull();
		assertThat(EmailAddresses.addrSpec(null)).isNull();
	}

	@Test
	void domainOfTakesEverythingAfterTheLastAt() {
		assertThat(EmailAddresses.domainOf("mail@acme.example")).isEqualTo("acme.example");
		assertThat(EmailAddresses.domainOf(null)).isNull();
	}

	@Test
	void normalizeFromCanonicalizesTheAcceptedForms() {
		assertThat(EmailAddresses.normalizeFrom("mail@acme.example")).isEqualTo("mail@acme.example");
		assertThat(EmailAddresses.normalizeFrom("  Mail@Acme.example ")).isEqualTo("mail@acme.example");
		assertThat(EmailAddresses.normalizeFrom("Acme <Mail@Acme.example>")).isEqualTo("Acme <mail@acme.example>");
		assertThat(EmailAddresses.normalizeFrom("<mail@acme.example>")).isEqualTo("mail@acme.example");
	}

	@Test
	void normalizeFromRefusesEverythingWithTwoReadings() {
		// The whole point: addrSpec() reads the last angle-addr, a mail parser
		// may take the first, and the difference is a sender-verification
		// bypass — the tenant is verified on one address and mails from another.
		assertThat(EmailAddresses.normalizeFrom("evil@attacker.example <mail@acme.example>"))
				.isNotEqualTo("evil@attacker.example <mail@acme.example>");
		assertThat(EmailAddresses.normalizeFrom("A <evil@attacker.example> <mail@acme.example>")).isNull();
		assertThat(EmailAddresses.normalizeFrom("Acme <mail@acme.example> trailing")).isNull();
		assertThat(EmailAddresses.normalizeFrom("mail@acme.example, other@acme.example")).isNull();
		assertThat(EmailAddresses.normalizeFrom("Acme\r\nBcc: victim@x.example <mail@acme.example>")).isNull();
		assertThat(EmailAddresses.normalizeFrom("no-at-sign")).isNull();
		assertThat(EmailAddresses.normalizeFrom("")).isNull();
		assertThat(EmailAddresses.normalizeFrom(null)).isNull();
	}

	@Test
	void normalizeFromQuotesADisplayNameThatNeedsIt() {
		assertThat(EmailAddresses.normalizeFrom("Acme, Inc. <mail@acme.example>"))
				.isEqualTo("\"Acme, Inc.\" <mail@acme.example>");
		// An address in the display name is neutralised, not trusted: quoted, it
		// can no longer be read as the address the mail is from.
		assertThat(EmailAddresses.normalizeFrom("evil@attacker.example <mail@acme.example>"))
				.isEqualTo("\"evil@attacker.example\" <mail@acme.example>");
		// Already-quoted names are not quoted a second time.
		assertThat(EmailAddresses.normalizeFrom("\"Acme, Inc.\" <mail@acme.example>"))
				.isEqualTo("\"Acme, Inc.\" <mail@acme.example>");
	}

	@Test
	void normalizeFromIsIdempotent() {
		// Stored addresses are re-checked on every send, so a second pass has to
		// agree with the first or a tenant's own from address turns unusable.
		for (String input : new String[] { "mail@acme.example", "Acme <mail@acme.example>",
				"Acme, Inc. <mail@acme.example>", "Sharey <shared-1@send.test.example>" }) {
			String once = EmailAddresses.normalizeFrom(input);
			assertThat(EmailAddresses.normalizeFrom(once)).as(input).isEqualTo(once);
		}
	}

	@Test
	void formatFromDropsBracketsFromTheDisplayName() {
		// A tenant name is free text; it must not be able to smuggle a second
		// address into the from line Herald builds for the shared tier.
		assertThat(EmailAddresses.formatFrom("Acme <evil@attacker.example>", "acme@send.example"))
				.isEqualTo("\"Acme evil@attacker.example\" <acme@send.example>");
		assertThat(EmailAddresses.formatFrom("Acme App", "acme@send.example"))
				.isEqualTo("Acme App <acme@send.example>");
		assertThat(EmailAddresses.formatFrom(null, "acme@send.example")).isEqualTo("acme@send.example");
		assertThat(EmailAddresses.formatFrom("  ", "acme@send.example")).isEqualTo("acme@send.example");
	}
}
