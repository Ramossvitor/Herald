package io.github.ramossvitor.herald.whatsapp;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PhoneNumbersTest {

	@Test
	void stripsTheSeparatorsAHumanWouldType() {
		assertThat(PhoneNumbers.canonicalize("+55 (11) 99999-0000")).isEqualTo("5511999990000");
		assertThat(PhoneNumbers.canonicalize("+55.11.99999.0000")).isEqualTo("5511999990000");
		assertThat(PhoneNumbers.canonicalize("  +5511999990000  ")).isEqualTo("5511999990000");
	}

	@Test
	void spellingsOfOneNumberShareAQuotaWindow() {
		assertThat(PhoneNumbers.canonicalize("+55 11 99999-0000"))
				.isEqualTo(PhoneNumbers.canonicalize("+5511999990000"));
	}

	@Test
	void refusesToGuessACountryCode() {
		// The dangerous case: a bare national number does not fail on send, it
		// delivers to whoever holds that number under some other country code.
		assertThat(PhoneNumbers.canonicalize("11999990000")).isNull();
		assertThat(PhoneNumbers.canonicalize("(11) 99999-0000")).isNull();
	}

	@Test
	void refusesANationalTrunkPrefixThatSurvived() {
		// "+0..." means someone prefixed a domestic dialling digit; no country
		// code starts at zero.
		assertThat(PhoneNumbers.canonicalize("+011999990000")).isNull();
	}

	@Test
	void enforcesE164Length() {
		assertThat(PhoneNumbers.canonicalize("+1234567")).isNull();
		assertThat(PhoneNumbers.canonicalize("+1234567890123456")).isNull();
		assertThat(PhoneNumbers.canonicalize("+12345678")).isEqualTo("12345678");
		assertThat(PhoneNumbers.canonicalize("+123456789012345")).isEqualTo("123456789012345");
	}

	@Test
	void refusesAnythingThatIsNotANumber() {
		assertThat(PhoneNumbers.canonicalize(null)).isNull();
		assertThat(PhoneNumbers.canonicalize("")).isNull();
		assertThat(PhoneNumbers.canonicalize("+")).isNull();
		assertThat(PhoneNumbers.canonicalize("+55119999a0000")).isNull();
		assertThat(PhoneNumbers.canonicalize("+55;11;99999")).isNull();
	}
}
