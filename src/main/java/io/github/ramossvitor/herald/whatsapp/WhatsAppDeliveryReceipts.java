package io.github.ramossvitor.herald.whatsapp;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.ramossvitor.herald.outbox.Message;
import io.github.ramossvitor.herald.outbox.MessageRepository;
import io.github.ramossvitor.herald.outbox.MessageStatus;
import io.github.ramossvitor.herald.security.Secrets;
import io.github.ramossvitor.herald.sender.Channel;
import io.github.ramossvitor.herald.whatsapp.meta.WebhookPayloads;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Turns Meta's delivery receipts into outbox state.
 *
 * Accepting a message and delivering it are different events on this channel:
 * the API answers 200 with a wamid long before the handset is involved, so a
 * SENT row means "Meta took it", not "it arrived". A {@code failed} receipt is
 * how the difference becomes visible — without it a number that never receives
 * anything looks, from the status endpoint, exactly like one that does.
 */
@Service
public class WhatsAppDeliveryReceipts {

	private static final Logger log = LoggerFactory.getLogger(WhatsAppDeliveryReceipts.class);

	private final TenantWhatsAppSettingsRepository settings;
	private final MessageRepository messages;
	private final Secrets secrets;
	private final Clock clock;
	private final MeterRegistry metrics;

	public WhatsAppDeliveryReceipts(TenantWhatsAppSettingsRepository settings, MessageRepository messages,
			Secrets secrets, Clock clock, MeterRegistry metrics) {
		this.settings = settings;
		this.messages = messages;
		this.secrets = secrets;
		this.clock = clock;
		this.metrics = metrics;
	}

	/** The tenant a payload belongs to, and the secret that proves it. */
	public record Recipient(UUID tenantId, String appSecret) {
	}

	/**
	 * @return null when no tenant owns that account, or its stored secret cannot
	 *         be read — in both cases there is nothing to verify against, which
	 *         is the same answer as "not ours"
	 */
	@Transactional(readOnly = true)
	public Recipient resolve(String wabaId) {
		TenantWhatsAppSettings tenant = settings.findByWabaId(wabaId).orElse(null);
		if (tenant == null) {
			return null;
		}
		try {
			return new Recipient(tenant.getTenantId(), secrets.decrypt(tenant.getAppSecret(),
					TenantWhatsAppSettings.appSecretContext(tenant.getTenantId())));
		}
		catch (RuntimeException ex) {
			log.warn("stored app secret for tenant {} could not be read", tenant.getTenantId());
			return null;
		}
	}

	@Transactional
	public void apply(Recipient recipient, List<WebhookPayloads.DeliveryStatus> statuses) {
		for (WebhookPayloads.DeliveryStatus status : statuses) {
			if (!status.isFailure()) {
				// sent/delivered/read carry no state the outbox models. Recording
				// them would mean a schema for a lifecycle nothing acts on.
				continue;
			}
			Message message = messages
					.findByTenantIdAndChannelAndProviderMessageId(recipient.tenantId(), Channel.WHATSAPP,
							status.providerMessageId())
					.orElse(null);
			if (message == null) {
				// A receipt for something this tenant never sent through Herald,
				// or one that arrived before the send was recorded.
				continue;
			}
			if (message.getStatus() == MessageStatus.FAILED) {
				continue;
			}
			// Deliberately terminal. Meta rejected the delivery itself — a bad
			// number, a blocked recipient — and the outbox retrying that would
			// spend attempts on an answer that will not change.
			message.recordFailure(status.describe(), clock.instant());
			metrics.counter("herald.messages.failed", "channel", "whatsapp").increment();
			log.warn("delivery failed for message {}: {}", message.getId(), status.describe());
		}
	}
}
