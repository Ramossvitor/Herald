package io.github.ramossvitor.herald.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.github.ramossvitor.herald.outbox.DeliveryState;
import io.github.ramossvitor.herald.suppression.SuppressionReason;

class ResendEventPayloadsTest {

	private static String bounce(String type, String subType) {
		return """
				{"type":"email.bounced","created_at":"2026-08-25T10:00:00.000Z","data":{
				  "email_id":"re_abc","to":["player@example.com"],
				  "bounce":{"message":"The mailbox does not exist.","subType":"%s","type":"%s"}}}
				""".formatted(subType, type);
	}

	@Test
	void aPermanentBounceSuppressesTheAddress() {
		ResendEventPayloads.Event event = ResendEventPayloads.parse(bounce("Permanent", "General"));

		assertThat(event.providerMessageId()).isEqualTo("re_abc");
		assertThat(event.state()).isEqualTo(DeliveryState.BOUNCED);
		assertThat(event.suppression()).isEqualTo(SuppressionReason.BOUNCED);
		assertThat(event.detail()).isEqualTo("Permanent/General: The mailbox does not exist.");
	}

	@Test
	void aTransientBounceIsRecordedButDoesNotSuppress() {
		// A full mailbox or a greylisting server — the case the outbox already
		// knows how to retry. Cutting the address off would be wrong.
		ResendEventPayloads.Event event = ResendEventPayloads.parse(bounce("Transient", "MailboxFull"));

		assertThat(event.state()).isEqualTo(DeliveryState.BOUNCED);
		assertThat(event.suppression()).isNull();
	}

	@Test
	void anUndeterminedBounceDoesNotSuppressEither() {
		ResendEventPayloads.Event event = ResendEventPayloads.parse(bounce("Undetermined", "Undetermined"));

		assertThat(event.suppression()).isNull();
	}

	@Test
	void aBounceWithoutDetailsIsStillABounce() {
		ResendEventPayloads.Event event = ResendEventPayloads
				.parse("{\"type\":\"email.bounced\",\"data\":{\"email_id\":\"re_abc\"}}");

		assertThat(event.state()).isEqualTo(DeliveryState.BOUNCED);
		assertThat(event.suppression()).isNull();
		assertThat(event.detail()).isNull();
	}

	@Test
	void deliveredIsTrackedAndHarmless() {
		ResendEventPayloads.Event event = ResendEventPayloads
				.parse("{\"type\":\"email.delivered\",\"data\":{\"email_id\":\"re_abc\"}}");

		assertThat(event.state()).isEqualTo(DeliveryState.DELIVERED);
		assertThat(event.suppression()).isNull();
	}

	@Test
	void aComplaintSuppressesToo() {
		ResendEventPayloads.Event event = ResendEventPayloads
				.parse("{\"type\":\"email.complained\",\"data\":{\"email_id\":\"re_abc\"}}");

		assertThat(event.state()).isEqualTo(DeliveryState.COMPLAINED);
		assertThat(event.suppression()).isEqualTo(SuppressionReason.COMPLAINED);
	}

	@Test
	void aDelayIsProvisional() {
		ResendEventPayloads.Event event = ResendEventPayloads
				.parse("{\"type\":\"email.delivery_delayed\",\"data\":{\"email_id\":\"re_abc\"}}");

		assertThat(event.state()).isEqualTo(DeliveryState.DELAYED);
		assertThat(event.state().isTerminal()).isFalse();
	}

	@Test
	void eventsHeraldDoesNotTrackParseButCarryNoState() {
		for (String type : new String[] { "email.sent", "email.opened", "email.clicked", "contact.created" }) {
			ResendEventPayloads.Event event = ResendEventPayloads
					.parse("{\"type\":\"" + type + "\",\"data\":{\"email_id\":\"re_abc\"}}");

			assertThat(event.tracked()).as(type).isFalse();
			assertThat(event.type()).isEqualTo(type);
		}
	}

	@Test
	void unusableBodiesParseToNothingRatherThanThrowing() {
		assertThat(ResendEventPayloads.parse(null)).isNull();
		assertThat(ResendEventPayloads.parse("")).isNull();
		assertThat(ResendEventPayloads.parse("not json at all")).isNull();
		assertThat(ResendEventPayloads.parse("{\"data\":{\"email_id\":\"re_abc\"}}")).isNull();
	}

	@Test
	void aLongProviderMessageIsTruncated() {
		String message = "x".repeat(900);
		ResendEventPayloads.Event event = ResendEventPayloads.parse("""
				{"type":"email.bounced","data":{"email_id":"re_abc",
				  "bounce":{"message":"%s","subType":"General","type":"Permanent"}}}
				""".formatted(message));

		assertThat(event.detail()).hasSize(500);
	}
}
