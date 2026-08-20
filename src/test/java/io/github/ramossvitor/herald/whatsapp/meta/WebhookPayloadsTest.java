package io.github.ramossvitor.herald.whatsapp.meta;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Everything this class reads arrives unauthenticated — the WABA id is pulled
 * out precisely so the right app secret can be found to authenticate the rest.
 * So the cases that matter are the malformed and the hostile ones.
 */
class WebhookPayloadsTest {

	private static final String WABA = "1234567890";

	private static final String FAILURE = """
			{"object":"whatsapp_business_account","entry":[{"id":"%s","changes":[{"field":"messages",
			 "value":{"messaging_product":"whatsapp","statuses":[{"id":"wamid.ABC","status":"failed",
			 "errors":[{"code":131026,"title":"Message undeliverable"}]}]}}]}]}
			""".formatted(WABA);

	@Test
	void readsTheAccountAndTheFailure() {
		var root = parse(FAILURE);

		assertThat(WebhookPayloads.wabaIds(root)).containsExactly(WABA);
		List<WebhookPayloads.DeliveryStatus> statuses = WebhookPayloads.statuses(root, WABA);
		assertThat(statuses).hasSize(1);
		assertThat(statuses.get(0).providerMessageId()).isEqualTo("wamid.ABC");
		assertThat(statuses.get(0).isFailure()).isTrue();
		assertThat(statuses.get(0).describe()).isEqualTo("delivery failed code 131026: Message undeliverable");
	}

	@Test
	void onlyFailuresAreFailures() {
		// sent/delivered/read carry no state the outbox models; treating one as a
		// failure would turn a delivered message into a FAILED row.
		for (String state : List.of("sent", "delivered", "read")) {
			var statuses = WebhookPayloads.statuses(parse(receipt(state, "")), WABA);
			assertThat(statuses).hasSize(1);
			assertThat(statuses.get(0).isFailure()).isFalse();
		}
		assertThat(WebhookPayloads.statuses(parse(receipt("FAILED", "")), WABA).get(0).isFailure()).isTrue();
	}

	@Test
	void describesAFailureThatCarriesNoError() {
		var statuses = WebhookPayloads.statuses(parse(receipt("failed", "")), WABA);
		assertThat(statuses.get(0).describe()).isEqualTo("delivery failed");
	}

	@Test
	void skipsStatusesThatCannotBeAddressed() {
		String payload = """
				{"entry":[{"id":"%s","changes":[{"value":{"statuses":[
				  {"status":"failed"},
				  {"id":"wamid.NOSTATE"},
				  {"id":"wamid.OK","status":"failed"}]}}]}]}
				""".formatted(WABA);
		assertThat(WebhookPayloads.statuses(parse(payload), WABA))
				.singleElement()
				.satisfies(status -> assertThat(status.providerMessageId()).isEqualTo("wamid.OK"));
	}

	@Test
	void receiptsOnAnEntryThatNamesNoAccountAreNotOurs() {
		// The controller's guard is "the payload names exactly one account".
		// wabaIds ignores an entry with no id, so pairing one named entry with an
		// anonymous one would pass that guard — the statuses must still be scoped
		// to the entry that was actually named and verified.
		String smuggled = """
				{"entry":[{"id":"%s","changes":[]},
				          {"changes":[{"value":{"statuses":[{"id":"wamid.SMUGGLED","status":"failed"}]}}]}]}
				""".formatted(WABA);
		var root = parse(smuggled);

		assertThat(WebhookPayloads.wabaIds(root)).containsExactly(WABA);
		assertThat(WebhookPayloads.statuses(root, WABA)).isEmpty();
	}

	@Test
	void collectsEveryAccountSoTheCallerCanRefuseMoreThanOne() {
		String twoAccounts = "{\"entry\":[{\"id\":\"111\",\"changes\":[]},{\"id\":\"222\",\"changes\":[]}]}";
		assertThat(WebhookPayloads.wabaIds(parse(twoAccounts))).containsExactly("111", "222");
	}

	@Test
	void ignoresAnAccountIdThatIsNotUsableText() {
		String odd = "{\"entry\":[{\"id\":123,\"changes\":[]},{\"id\":\"  \",\"changes\":[]},{\"changes\":[]}]}";
		assertThat(WebhookPayloads.wabaIds(parse(odd))).isEmpty();
	}

	@Test
	void survivesAnythingUnexpected() {
		assertThat(WebhookPayloads.parse(null)).isNull();
		assertThat(WebhookPayloads.parse(new byte[0])).isNull();
		assertThat(WebhookPayloads.parse("not json".getBytes(StandardCharsets.UTF_8))).isNull();

		assertThat(WebhookPayloads.wabaIds(null)).isEmpty();
		assertThat(WebhookPayloads.statuses(null, WABA)).isEmpty();
		assertThat(WebhookPayloads.statuses(parse("{}"), WABA)).isEmpty();
		assertThat(WebhookPayloads.statuses(parse(FAILURE), null)).isEmpty();
	}

	private static com.fasterxml.jackson.databind.JsonNode parse(String body) {
		return WebhookPayloads.parse(body.getBytes(StandardCharsets.UTF_8));
	}

	private static String receipt(String state, String errors) {
		return """
				{"entry":[{"id":"%s","changes":[{"value":{"statuses":[
				  {"id":"wamid.ABC","status":"%s"%s}]}}]}]}
				""".formatted(WABA, state, errors);
	}
}
