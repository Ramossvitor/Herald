package io.github.ramossvitor.herald.webhook;

import java.time.Clock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.ramossvitor.herald.outbox.Message;
import io.github.ramossvitor.herald.outbox.MessageRepository;
import io.github.ramossvitor.herald.suppression.SuppressionService;

/**
 * Applies one verified webhook: record it, match it to a message, update the
 * delivery state, and suppress the address when the provider says it is dead.
 *
 * Everything here runs after the signature check — this class assumes the
 * payload is authentic and concerns itself only with what it means.
 */
@Service
public class WebhookService {

	private static final Logger log = LoggerFactory.getLogger(WebhookService.class);

	/**
	 * What one delivery amounted to. Only for logging and metrics: every value
	 * answers the provider with a 2xx, because none of them is something the
	 * provider could fix by sending again.
	 */
	public enum Outcome {
		/** State updated, and the address suppressed if the event called for it. */
		APPLIED,
		/** A terminal state was already recorded; the event did not change it. */
		SUPERSEDED,
		/** Already processed under this svix-id. */
		DUPLICATE,
		/** A valid event Herald does not track (sent, opened, clicked…). */
		IGNORED,
		/** No message here has that provider id. */
		NO_MATCH,
		/** Authentic, but not a shape this code understands. */
		UNPARSEABLE
	}

	private final WebhookEventRepository events;
	private final MessageRepository messages;
	private final SuppressionService suppressions;
	private final Clock clock;

	public WebhookService(WebhookEventRepository events, MessageRepository messages, SuppressionService suppressions,
			Clock clock) {
		this.events = events;
		this.messages = messages;
		this.suppressions = suppressions;
		this.clock = clock;
	}

	@Transactional
	public Outcome process(String svixId, String rawBody) {
		ResendEventPayloads.Event event = ResendEventPayloads.parse(rawBody);
		if (event == null) {
			// Correctly signed, so it came from the provider — which makes this
			// a contract change on their side, not a bad request on ours.
			log.warn("could not parse a verified webhook payload");
			return Outcome.UNPARSEABLE;
		}

		// Before any effect, so a redelivery cannot suppress an address twice
		// or overwrite a state that a later event has already corrected. Flushed
		// now rather than at commit so the duplicate surfaces here.
		if (events.existsById(svixId)) {
			return Outcome.DUPLICATE;
		}
		events.saveAndFlush(new WebhookEvent(svixId, event.type(), clock.instant()));

		if (!event.tracked()) {
			return Outcome.IGNORED;
		}
		if (event.providerMessageId() == null) {
			return Outcome.UNPARSEABLE;
		}

		Message message = messages.findByProviderMessageId(event.providerMessageId()).orElse(null);
		if (message == null) {
			// The operator's provider account may well send mail that did not
			// come through Herald; those events are not ours to act on.
			log.debug("webhook {} references an unknown provider id", event.type());
			return Outcome.NO_MATCH;
		}

		// The tenant comes from the matched row, never from the payload: the
		// event says which message, and only the message says whose it is.
		boolean applied = message.recordDeliveryEvent(event.state(), event.detail(), clock.instant());

		// Suppression is decided by the event, not by whether it won the state
		// race. A bounce arriving after a complaint does not update the state,
		// but it is still fresh evidence that the address is dead — and the
		// list exists to act on that, whatever order the news arrives in.
		if (event.suppression() != null) {
			suppressions.suppress(message.getTenantId(), message.getRecipientCanonical(), event.suppression(),
					event.detail(), message.getId());
		}
		return applied ? Outcome.APPLIED : Outcome.SUPERSEDED;
	}
}
