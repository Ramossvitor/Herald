package io.github.ramossvitor.herald.whatsapp.meta;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.github.ramossvitor.herald.outbox.Classification;

class WhatsAppResponseClassifierTest {

	private static String error(int code, String message) {
		return "{\"error\":{\"message\":\"" + message + "\",\"code\":" + code + ",\"fbtrace_id\":\"Axyz\"}}";
	}

	@Test
	void successOnAny2xx() {
		assertThat(WhatsAppResponseClassifier.classify(200, "{\"messages\":[{\"id\":\"wamid.1\"}]}"))
				.isEqualTo(Classification.SUCCESS);
	}

	@Test
	void throughputThrottlingRetriesQuickly() {
		assertThat(WhatsAppResponseClassifier.classify(429, error(130429, "Rate limit hit")))
				.isEqualTo(Classification.BURST_LIMIT);
		assertThat(WhatsAppResponseClassifier.classify(429, error(4, "Application request limit reached")))
				.isEqualTo(Classification.BURST_LIMIT);
	}

	@Test
	void theSpamRateLimitTakesThePatientPath() {
		// Not a burst: Meta is saying recipients are rejecting this number's
		// traffic. Coming straight back would deepen the hole.
		assertThat(WhatsAppResponseClassifier.classify(429, error(131048, "Spam rate limit hit")))
				.isEqualTo(Classification.DAILY_LIMIT);
	}

	@Test
	void ordinary4xxIsTheCallersProblem() {
		assertThat(WhatsAppResponseClassifier.classify(400, error(132001, "Template does not exist")))
				.isEqualTo(Classification.REJECTED);
		assertThat(WhatsAppResponseClassifier.classify(400, error(131026, "Message undeliverable")))
				.isEqualTo(Classification.REJECTED);
	}

	@Test
	void anExpiredTokenPausesTheBacklogRatherThanKillingIt() {
		// A tenant's token expires on its own schedule and a re-registration
		// fixes it in a minute. Rejecting would walk the whole backlog at
		// sendInterval and terminally fail every message on the way.
		assertThat(WhatsAppResponseClassifier.classify(401, error(190, "Access token expired")))
				.isEqualTo(Classification.UNAVAILABLE);
		assertThat(WhatsAppResponseClassifier.classify(403, error(200, "Permission denied")))
				.isEqualTo(Classification.UNAVAILABLE);
		// The code alone is enough; Meta does not always pair it with a 401.
		assertThat(WhatsAppResponseClassifier.classify(400, error(190, "Session expired")))
				.isEqualTo(Classification.UNAVAILABLE);

		assertThat(WhatsAppResponseClassifier.isAuthFailure(401, "")).isTrue();
		assertThat(WhatsAppResponseClassifier.isAuthFailure(400, error(132001, "Template does not exist")))
				.isFalse();
	}

	@Test
	void someFourHundredsAreReallyOutages() {
		// Meta returns these with a client-error status even though nothing
		// about the request is wrong.
		assertThat(WhatsAppResponseClassifier.classify(400, error(131000, "Something went wrong")))
				.isEqualTo(Classification.UNAVAILABLE);
		assertThat(WhatsAppResponseClassifier.classify(400, error(133016, "Temporarily unavailable")))
				.isEqualTo(Classification.UNAVAILABLE);
	}

	@Test
	void serverErrorsAreUnavailable() {
		assertThat(WhatsAppResponseClassifier.classify(500, "")).isEqualTo(Classification.UNAVAILABLE);
		assertThat(WhatsAppResponseClassifier.classify(503, "")).isEqualTo(Classification.UNAVAILABLE);
	}

	@Test
	void anUnreadableBodyStillClassifiesByStatus() {
		assertThat(WhatsAppResponseClassifier.classify(400, "not json")).isEqualTo(Classification.REJECTED);
		assertThat(WhatsAppResponseClassifier.classify(429, "")).isEqualTo(Classification.BURST_LIMIT);
	}

	@Test
	void extractsTheWamid() {
		assertThat(WhatsAppResponseClassifier.providerMessageId(
				"{\"messaging_product\":\"whatsapp\",\"messages\":[{\"id\":\"wamid.ABC\"}]}"))
				.isEqualTo("wamid.ABC");
		assertThat(WhatsAppResponseClassifier.providerMessageId("{\"messages\":[]}")).isNull();
		assertThat(WhatsAppResponseClassifier.providerMessageId("not json")).isNull();
		assertThat(WhatsAppResponseClassifier.providerMessageId(null)).isNull();
	}

	@Test
	void describesWithMetasOwnFieldsAndNothingElse() {
		// last_error is persisted and handed back by the status endpoint, so it
		// is built from named fields rather than by echoing the body.
		String described = WhatsAppResponseClassifier.describe(400, error(132001, "Template does not exist"));
		assertThat(described).isEqualTo("http 400 code 132001: Template does not exist");
		assertThat(described).doesNotContain("fbtrace_id");

		assertThat(WhatsAppResponseClassifier.describe(503, "")).isEqualTo("http 503");
	}
}
