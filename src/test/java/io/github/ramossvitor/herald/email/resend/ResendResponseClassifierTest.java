package io.github.ramossvitor.herald.email.resend;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.github.ramossvitor.herald.outbox.Classification;

class ResendResponseClassifierTest {

	@Test
	void successOnAny2xx() {
		assertThat(ResendResponseClassifier.classify(200, "{\"id\":\"abc\"}")).isEqualTo(Classification.SUCCESS);
		assertThat(ResendResponseClassifier.classify(201, "")).isEqualTo(Classification.SUCCESS);
	}

	@Test
	void splits429ByErrorName() {
		assertThat(ResendResponseClassifier.classify(429, "{\"name\":\"rate_limit_exceeded\"}"))
				.isEqualTo(Classification.BURST_LIMIT);
		assertThat(ResendResponseClassifier.classify(429, "{\"name\":\"daily_quota_exceeded\"}"))
				.isEqualTo(Classification.DAILY_LIMIT);
	}

	@Test
	void unreadable429BodyIsTreatedAsQuotaNotBurst() {
		// Backing off a quota is cheap; hammering one as if it were a burst is not.
		assertThat(ResendResponseClassifier.classify(429, "not json")).isEqualTo(Classification.DAILY_LIMIT);
		assertThat(ResendResponseClassifier.classify(429, "")).isEqualTo(Classification.DAILY_LIMIT);
		assertThat(ResendResponseClassifier.classify(429, "{\"name\":42}")).isEqualTo(Classification.DAILY_LIMIT);
	}

	@Test
	void other4xxIsRejected() {
		assertThat(ResendResponseClassifier.classify(401, "{\"name\":\"invalid_api_key\"}"))
				.isEqualTo(Classification.REJECTED);
		assertThat(ResendResponseClassifier.classify(422, "{\"name\":\"validation_error\"}"))
				.isEqualTo(Classification.REJECTED);
	}

	@Test
	void serverErrorsAndRedirectsAreUnavailable() {
		assertThat(ResendResponseClassifier.classify(500, "")).isEqualTo(Classification.UNAVAILABLE);
		assertThat(ResendResponseClassifier.classify(503, "")).isEqualTo(Classification.UNAVAILABLE);
		// fetch-style clients follow redirects; one arriving here means the
		// endpoint itself moved.
		assertThat(ResendResponseClassifier.classify(301, "")).isEqualTo(Classification.UNAVAILABLE);
	}

	@Test
	void extractsProviderMessageId() {
		assertThat(ResendResponseClassifier.providerMessageId("{\"id\":\"re_123\"}")).isEqualTo("re_123");
		assertThat(ResendResponseClassifier.providerMessageId("not json")).isNull();
		assertThat(ResendResponseClassifier.providerMessageId(null)).isNull();
	}
}
