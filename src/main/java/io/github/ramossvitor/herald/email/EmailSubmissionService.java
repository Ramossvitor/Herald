package io.github.ramossvitor.herald.email;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.ramossvitor.herald.outbox.Message;
import io.github.ramossvitor.herald.outbox.MessageRepository;
import io.github.ramossvitor.herald.quota.ChannelLimits;
import io.github.ramossvitor.herald.quota.QuotaService;
import io.github.ramossvitor.herald.sender.Channel;
import io.github.ramossvitor.herald.sender.SenderIdentityService;
import io.github.ramossvitor.herald.tenant.TenantEmailSettings;
import io.github.ramossvitor.herald.tenant.TenantEmailSettingsRepository;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * Quota is decided synchronously, in the same transaction that inserts the
 * outbox row — the caller learns "accepted" or the exact reason immediately.
 * Transport is the worker's problem.
 */
@Service
public class EmailSubmissionService {

	@PersistenceContext
	private EntityManager entityManager;

	private final MessageRepository messages;
	private final TenantEmailSettingsRepository emailSettings;
	private final QuotaService quotas;
	private final SenderIdentityService senderIdentities;
	private final Clock clock;
	private final MeterRegistry metrics;

	public EmailSubmissionService(MessageRepository messages, TenantEmailSettingsRepository emailSettings,
			QuotaService quotas, SenderIdentityService senderIdentities, Clock clock, MeterRegistry metrics) {
		this.messages = messages;
		this.emailSettings = emailSettings;
		this.quotas = quotas;
		this.senderIdentities = senderIdentities;
		this.clock = clock;
		this.metrics = metrics;
	}

	public record Submission(Message message, boolean deduplicated) {
	}

	@Transactional
	public Submission submit(UUID tenantId, SendEmailRequest request) {
		// Serializes submissions per tenant for the duration of this
		// transaction, so the quota check and the insert are atomic against
		// concurrent requests of the same tenant. Cross-tenant traffic is
		// unaffected. Released automatically at commit/rollback.
		lockTenant(tenantId);

		if (request.idempotencyKey() != null) {
			Message existing = messages
					.findByTenantIdAndChannelAndIdempotencyKey(tenantId, Channel.EMAIL, request.idempotencyKey())
					.orElse(null);
			if (existing != null) {
				return new Submission(existing, true);
			}
		}

		TenantEmailSettings settings = emailSettings.findById(tenantId)
				.orElseThrow(() -> new IllegalStateException(
						"tenant has no email settings — provisioning bug: " + tenantId));

		// Before the quota check on purpose: an unusable sender is a caller
		// mistake, and must never surface as a quota rejection. The canonical
		// form comes back out and is what gets stored and sent — sending the
		// caller's spelling would mean verifying one address and mailing another.
		String from = senderIdentities.resolveUsable(tenantId,
				request.from() != null ? request.from() : settings.getFromAddress());

		String canonicalRecipient = EmailAddresses.canonicalize(request.to());
		quotas.check(limitsFor(settings), canonicalRecipient, request.limitKeysOrEmpty());

		Message message = messages.save(new Message(
				tenantId,
				Channel.EMAIL,
				request.idempotencyKey(),
				request.to(),
				canonicalRecipient,
				from,
				payloadOf(request),
				request.limitKeysOrEmpty(),
				clock.instant()));
		metrics.counter("herald.messages.accepted", "channel", "email").increment();
		return new Submission(message, false);
	}

	private static ChannelLimits limitsFor(TenantEmailSettings settings) {
		return new ChannelLimits(settings.getTenantId(), Channel.EMAIL, settings.getDailyLimit(),
				settings.getRecipientCooldownSeconds());
	}

	/** Null entries are omitted, not stored: the column is JSON, and an absent
	 * key reads the same as a null one without carrying it around. */
	private static Map<String, Object> payloadOf(SendEmailRequest request) {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("subject", request.subject());
		payload.put("html", request.html());
		payload.put("text", request.text());
		if (request.replyTo() != null) {
			payload.put("replyTo", request.replyTo());
		}
		return payload;
	}

	private void lockTenant(UUID tenantId) {
		entityManager
				.createNativeQuery("select pg_advisory_xact_lock(hashtext(:tenantId))")
				.setParameter("tenantId", tenantId.toString())
				.getSingleResult();
	}
}
