package io.github.ramossvitor.herald.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class RetryPolicyTest {

	private static final long JITTER_MS = 250;

	private final RetryPolicy policy = new RetryPolicy(8);

	@Test
	void successIsTerminal() {
		RetryPolicy.Decision decision = policy.decide(Classification.SUCCESS, 1, null);
		assertThat(decision.status()).isEqualTo(MessageStatus.SENT);
		assertThat(decision.delay()).isNull();
	}

	@Test
	void rejectionIsTerminalOnFirstAttempt() {
		RetryPolicy.Decision decision = policy.decide(Classification.REJECTED, 1, null);
		assertThat(decision.status()).isEqualTo(MessageStatus.FAILED);
	}

	@Test
	void burstRetriesQuicklyHonoringRetryAfter() {
		RetryPolicy.Decision decision = policy.decide(Classification.BURST_LIMIT, 1, 1);
		assertThat(decision.status()).isEqualTo(MessageStatus.PENDING);
		assertThat(decision.delay()).isBetween(Duration.ofSeconds(1), Duration.ofSeconds(1).plusMillis(JITTER_MS));
	}

	@Test
	void burstCapsLargeRetryAfter() {
		// A large Retry-After on a "burst" signals quota mislabeled — cap it.
		RetryPolicy.Decision decision = policy.decide(Classification.BURST_LIMIT, 1, 3600);
		assertThat(decision.delay()).isBetween(Duration.ofSeconds(5), Duration.ofSeconds(5).plusMillis(JITTER_MS));
	}

	@Test
	void burstWithoutRetryAfterUsesDefault() {
		RetryPolicy.Decision decision = policy.decide(Classification.BURST_LIMIT, 1, null);
		assertThat(decision.delay()).isBetween(Duration.ofSeconds(2), Duration.ofSeconds(2).plusMillis(JITTER_MS));
	}

	@Test
	void providerDailyLimitWaitsHours() {
		RetryPolicy.Decision decision = policy.decide(Classification.DAILY_LIMIT, 1, null);
		assertThat(decision.status()).isEqualTo(MessageStatus.PENDING);
		assertThat(decision.delay()).isBetween(Duration.ofHours(4), Duration.ofHours(4).plusMillis(JITTER_MS));
	}

	@Test
	void unavailableBacksOffExponentiallyWithCap() {
		// A patient policy, so high attempt numbers exercise the delay curve
		// instead of tripping the attempt ceiling.
		RetryPolicy patient = new RetryPolicy(99);
		assertThat(patient.decide(Classification.UNAVAILABLE, 1, null).delay())
				.isBetween(Duration.ofSeconds(30), Duration.ofSeconds(30).plusMillis(JITTER_MS));
		assertThat(patient.decide(Classification.UNAVAILABLE, 3, null).delay())
				.isBetween(Duration.ofSeconds(120), Duration.ofSeconds(120).plusMillis(JITTER_MS));
		// 30s * 2^7 would pass the hour — capped.
		assertThat(patient.decide(Classification.UNAVAILABLE, 8, null).delay())
				.isBetween(Duration.ofHours(1), Duration.ofHours(1).plusMillis(JITTER_MS));
	}

	@Test
	void attemptsExhaustedIsTerminalForRetryableFailures() {
		assertThat(policy.decide(Classification.BURST_LIMIT, 8, null).status()).isEqualTo(MessageStatus.FAILED);
		assertThat(policy.decide(Classification.DAILY_LIMIT, 8, null).status()).isEqualTo(MessageStatus.FAILED);
		assertThat(policy.decide(Classification.UNAVAILABLE, 8, null).status()).isEqualTo(MessageStatus.FAILED);
	}

	@Test
	void successOnLastAttemptStillCounts() {
		assertThat(policy.decide(Classification.SUCCESS, 8, null).status()).isEqualTo(MessageStatus.SENT);
	}
}
