package io.github.ramossvitor.herald.whatsapp.meta;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class WhatsAppTemplatePayloadsTest {

	private static final String TWO_TEMPLATES = """
			{"data":[
			  {"id":"1","name":"order_update","language":"pt_BR","category":"UTILITY","status":"APPROVED",
			   "rejected_reason":"NONE",
			   "components":[{"type":"HEADER","format":"TEXT","text":"Pedido"},
			                 {"type":"BODY","text":"Olá {{1}}, seu pedido {{2}} foi enviado."},
			                 {"type":"FOOTER","text":"Acme"}]},
			  {"id":"2","name":"promo","language":"pt_BR","category":"MARKETING","status":"REJECTED",
			   "rejected_reason":"INVALID_FORMAT",
			   "components":[{"type":"BODY","text":"Aproveite!"}]}
			]}
			""";

	@Test
	void readsWhatMetaReports() {
		List<WhatsAppTemplatePayloads.Template> templates = WhatsAppTemplatePayloads.parse(TWO_TEMPLATES);

		assertThat(templates).hasSize(2);
		WhatsAppTemplatePayloads.Template order = templates.get(0);
		assertThat(order.name()).isEqualTo("order_update");
		assertThat(order.language()).isEqualTo("pt_BR");
		assertThat(order.category()).isEqualTo("UTILITY");
		assertThat(order.status()).isEqualTo("APPROVED");
		assertThat(order.bodyParamCount()).isEqualTo(2);
		assertThat(order.providerRef()).isEqualTo("1");
		// "NONE" is Meta saying there was no rejection, not a reason.
		assertThat(order.rejectedReason()).isNull();

		assertThat(templates.get(1).status()).isEqualTo("REJECTED");
		assertThat(templates.get(1).rejectedReason()).isEqualTo("INVALID_FORMAT");
	}

	@Test
	void countsTheHighestPlaceholderNotTheNumberOfMatches() {
		// A body may use one placeholder twice and still take one argument.
		assertThat(count("Olá {{1}}, confirmamos {{1}} para você.")).isEqualTo(1);
		// ...and may skip nothing, so the highest index is the arity.
		assertThat(count("{{1}} {{2}} {{3}}")).isEqualTo(3);
		assertThat(count("no placeholders here")).isZero();
		assertThat(count("{{ 2 }} with spaces")).isEqualTo(2);
	}

	@Test
	void onlyTheBodyCounts() {
		// Header and footer placeholders are filled by their own components; a
		// body arity that swept them in would refuse every correct send.
		String template = """
				{"data":[{"id":"1","name":"t","language":"en","category":"UTILITY","status":"APPROVED",
				  "components":[{"type":"HEADER","text":"{{1}}"},
				                {"type":"BODY","text":"only {{1}} here"},
				                {"type":"FOOTER","text":"{{1}}"}]}]}
				""";
		assertThat(WhatsAppTemplatePayloads.parse(template).get(0).bodyParamCount()).isEqualTo(1);
	}

	@Test
	void flagsTemplatesWantingAValueHeraldNeverSends() {
		// Herald only ever emits a body component, so a variable anywhere else is
		// a parameter Meta will find missing. Counting only the body would let
		// these pass the arity check and be rejected on delivery instead — the
		// FAILED row the gate exists to prevent.
		assertThat(unsupported("""
				[{"type":"HEADER","format":"TEXT","text":"Pedido {{1}}"},{"type":"BODY","text":"Olá {{1}}."}]
				""")).isTrue();
		assertThat(unsupported("""
				[{"type":"HEADER","format":"IMAGE"},{"type":"BODY","text":"Olá {{1}}."}]
				""")).isTrue();
		assertThat(unsupported("""
				[{"type":"BODY","text":"Olá {{1}}."},
				 {"type":"BUTTONS","buttons":[{"type":"URL","url":"https://acme.example/{{1}}"}]}]
				""")).isTrue();
	}

	@Test
	void aStaticHeaderOrButtonIsFine() {
		// Neither carries a value, so Herald sending only a body is complete.
		assertThat(unsupported("""
				[{"type":"HEADER","format":"TEXT","text":"Pedido"},{"type":"BODY","text":"Olá {{1}}."},
				 {"type":"FOOTER","text":"Acme"},
				 {"type":"BUTTONS","buttons":[{"type":"URL","url":"https://acme.example/orders"}]}]
				""")).isFalse();
		assertThat(unsupported("[{\"type\":\"BODY\",\"text\":\"Olá {{1}}.\"}]")).isFalse();
	}

	@Test
	void namedParametersCountAsZero() {
		// Herald sends positional arguments. Counting a named template as zero
		// makes the arity check refuse it, which beats sending values Meta
		// cannot place.
		assertThat(count("Hello {{name}}, order {{order_id}}")).isZero();
	}

	@Test
	void skipsEntriesThatCannotBeAddressed() {
		String template = """
				{"data":[{"id":"1","language":"en","status":"APPROVED"},
				         {"id":"2","name":"fine","language":"en","status":"APPROVED","components":[]}]}
				""";
		assertThat(WhatsAppTemplatePayloads.parse(template)).hasSize(1);
	}

	@Test
	void survivesAnythingUnexpected() {
		assertThat(WhatsAppTemplatePayloads.parse(null)).isEmpty();
		assertThat(WhatsAppTemplatePayloads.parse("")).isEmpty();
		assertThat(WhatsAppTemplatePayloads.parse("not json")).isEmpty();
		assertThat(WhatsAppTemplatePayloads.parse("{\"data\":{}}")).isEmpty();
	}

	@Test
	void reportsWhetherTheAnswerWasComplete() {
		// Pagination is what decides whether stale rows may be pruned, so a
		// missed "next" would delete a tenant's templates.
		assertThat(WhatsAppTemplatePayloads.nextPage(TWO_TEMPLATES)).isNull();
		assertThat(WhatsAppTemplatePayloads.nextPage(
				"{\"data\":[],\"paging\":{\"next\":\"https://graph.facebook.com/next\"}}"))
				.isEqualTo("https://graph.facebook.com/next");
	}

	private static boolean unsupported(String components) {
		String template = """
				{"data":[{"id":"1","name":"t","language":"en","category":"UTILITY","status":"APPROVED",
				  "components":%s}]}
				""".formatted(components);
		return WhatsAppTemplatePayloads.parse(template).get(0).unsupportedParams();
	}

	private static int count(String bodyText) {
		String template = """
				{"data":[{"id":"1","name":"t","language":"en","category":"UTILITY","status":"APPROVED",
				  "components":[{"type":"BODY","text":"%s"}]}]}
				""".formatted(bodyText);
		return WhatsAppTemplatePayloads.parse(template).get(0).bodyParamCount();
	}
}
