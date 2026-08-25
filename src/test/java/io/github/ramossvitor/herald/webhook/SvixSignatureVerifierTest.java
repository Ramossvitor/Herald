package io.github.ramossvitor.herald.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import io.github.ramossvitor.herald.common.HeraldProperties;

/**
 * The signature is the only authentication this endpoint has, so the happy path
 * is pinned to a fixed vector published by Svix rather than to a signature this
 * test computes: generating the expected value with the same code under test
 * would pass just as happily if the algorithm were wrong in both places.
 */
class SvixSignatureVerifierTest {

	// The vector itself, verbatim from Svix's published documentation and so
	// nobody's credential. Secret scanners flag the whsec_ prefix on sight —
	// Stripe signs its webhooks with the same one — but changing any of these
	// five values to appease one would defeat the point of pinning them.
	private static final String SECRET = "whsec_MfKQ9r8GKYqrTwjUPD8ILPZIo2LaLaSw";
	private static final String ID = "msg_p5jXN8AQM9LWM0D4loKWxJek";
	private static final String TIMESTAMP = "1614265330";
	private static final String BODY = "{\"test\": 2432232314}";
	private static final String SIGNATURE = "v1,g0hM9SsE+OTPJTGt/tmIKtSyZlE3uFJELVlNIOLJ1OE=";

	private static SvixSignatureVerifier verifierAt(Instant now, String secret) {
		HeraldProperties properties = new HeraldProperties("", new HeraldProperties.Email(""),
				new HeraldProperties.Resend("https://api.resend.com", "", secret, Duration.ofSeconds(5),
						Duration.ofSeconds(10)),
				new HeraldProperties.Outbox(Duration.ofSeconds(3), 10, 8, Duration.ofMillis(600)));
		return new SvixSignatureVerifier(properties, Clock.fixed(now, ZoneOffset.UTC));
	}

	private static SvixSignatureVerifier verifier() {
		return verifierAt(Instant.ofEpochSecond(Long.parseLong(TIMESTAMP)), SECRET);
	}

	@Test
	void acceptsTheReferenceVector() {
		assertThat(verifier().verify(ID, TIMESTAMP, SIGNATURE, BODY))
				.isEqualTo(SvixSignatureVerifier.Result.VALID);
	}

	@Test
	void acceptsASecretWithoutItsPrefix() {
		SvixSignatureVerifier verifier = verifierAt(Instant.ofEpochSecond(Long.parseLong(TIMESTAMP)),
				"MfKQ9r8GKYqrTwjUPD8ILPZIo2LaLaSw");

		assertThat(verifier.verify(ID, TIMESTAMP, SIGNATURE, BODY))
				.isEqualTo(SvixSignatureVerifier.Result.VALID);
	}

	@Test
	void anyOneOfSeveralSignaturesIsEnough() {
		// What a secret rotation looks like on the wire.
		String header = "v1,aW52YWxpZHNpZ25hdHVyZXZhbHVlaGVyZWZvcnRlc3Rpbmc= " + SIGNATURE;

		assertThat(verifier().verify(ID, TIMESTAMP, header, BODY))
				.isEqualTo(SvixSignatureVerifier.Result.VALID);
	}

	@Test
	void unknownVersionsAreSkippedNotTrusted() {
		String onlyFutureVersion = "v2,g0hM9SsE+OTPJTGt/tmIKtSyZlE3uFJELVlNIOLJ1OE=";

		assertThat(verifier().verify(ID, TIMESTAMP, onlyFutureVersion, BODY))
				.isEqualTo(SvixSignatureVerifier.Result.BAD_SIGNATURE);
	}

	@Test
	void aChangedBodyBreaksTheSignature() {
		assertThat(verifier().verify(ID, TIMESTAMP, SIGNATURE, "{\"test\": 2432232315}"))
				.isEqualTo(SvixSignatureVerifier.Result.BAD_SIGNATURE);
	}

	@Test
	void theIdAndTimestampAreCoveredToo() {
		assertThat(verifier().verify("msg_other", TIMESTAMP, SIGNATURE, BODY))
				.isEqualTo(SvixSignatureVerifier.Result.BAD_SIGNATURE);
		assertThat(verifier().verify(ID, "1614265331", SIGNATURE, BODY))
				.isEqualTo(SvixSignatureVerifier.Result.BAD_SIGNATURE);
	}

	@Test
	void anotherSecretDoesNotVerify() {
		// base64("nottherightsecretatallfortesting") — invented, and invented to
		// fail: the assertion below is that it verifies nothing.
		SvixSignatureVerifier verifier = verifierAt(Instant.ofEpochSecond(Long.parseLong(TIMESTAMP)),
				"whsec_bm90dGhlcmlnaHRzZWNyZXRhdGFsbGZvcnRlc3Rpbmc=");

		assertThat(verifier.verify(ID, TIMESTAMP, SIGNATURE, BODY))
				.isEqualTo(SvixSignatureVerifier.Result.BAD_SIGNATURE);
	}

	@Test
	void anAuthenticButOldRequestIsRefused() {
		Instant wellAfter = Instant.ofEpochSecond(Long.parseLong(TIMESTAMP))
				.plus(SvixSignatureVerifier.TOLERANCE).plusSeconds(1);

		assertThat(verifierAt(wellAfter, SECRET).verify(ID, TIMESTAMP, SIGNATURE, BODY))
				.isEqualTo(SvixSignatureVerifier.Result.STALE_TIMESTAMP);
	}

	@Test
	void aTimestampFarInTheFutureIsRefusedToo() {
		Instant wellBefore = Instant.ofEpochSecond(Long.parseLong(TIMESTAMP))
				.minus(SvixSignatureVerifier.TOLERANCE).minusSeconds(1);

		assertThat(verifierAt(wellBefore, SECRET).verify(ID, TIMESTAMP, SIGNATURE, BODY))
				.isEqualTo(SvixSignatureVerifier.Result.STALE_TIMESTAMP);
	}

	@Test
	void driftInsideTheWindowIsTolerated() {
		Instant justInside = Instant.ofEpochSecond(Long.parseLong(TIMESTAMP))
				.plus(SvixSignatureVerifier.TOLERANCE).minus(Duration.ofSeconds(1));

		assertThat(verifierAt(justInside, SECRET).verify(ID, TIMESTAMP, SIGNATURE, BODY))
				.isEqualTo(SvixSignatureVerifier.Result.VALID);
	}

	@Test
	void missingHeadersNeverReachTheCryptography() {
		assertThat(verifier().verify(null, TIMESTAMP, SIGNATURE, BODY))
				.isEqualTo(SvixSignatureVerifier.Result.MALFORMED);
		assertThat(verifier().verify(ID, TIMESTAMP, null, BODY))
				.isEqualTo(SvixSignatureVerifier.Result.MALFORMED);
		assertThat(verifier().verify(ID, TIMESTAMP, SIGNATURE, null))
				.isEqualTo(SvixSignatureVerifier.Result.MALFORMED);
	}

	@Test
	void aGarbledSignatureHeaderIsJustABadSignature() {
		assertThat(verifier().verify(ID, TIMESTAMP, "not-even-a-versioned-signature", BODY))
				.isEqualTo(SvixSignatureVerifier.Result.BAD_SIGNATURE);
	}

	@Test
	void withoutASecretNothingIsVerifiable() {
		SvixSignatureVerifier verifier = verifierAt(Instant.ofEpochSecond(Long.parseLong(TIMESTAMP)), "");

		assertThat(verifier.configured()).isFalse();
		assertThat(verifier.verify(ID, TIMESTAMP, SIGNATURE, BODY))
				.isEqualTo(SvixSignatureVerifier.Result.NOT_CONFIGURED);
	}

	@Test
	void anUndecodableSecretIsAConfigurationFaultNotAForgery() {
		SvixSignatureVerifier verifier = verifierAt(Instant.ofEpochSecond(Long.parseLong(TIMESTAMP)),
				"whsec_this is not base64 !!");

		assertThat(verifier.verify(ID, TIMESTAMP, SIGNATURE, BODY))
				.isEqualTo(SvixSignatureVerifier.Result.NOT_CONFIGURED);
	}
}
