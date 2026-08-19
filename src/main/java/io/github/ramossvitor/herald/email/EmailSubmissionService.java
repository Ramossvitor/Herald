package io.github.ramossvitor.herald.email;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

	private final EmailMessageRepository messages;
	private final TenantEmailSettingsRepository emailSettings;
	private final QuotaService quotas;
	private final SenderIdentityService senderIdentities;
	private final Clock clock;
	private final MeterRegistry metrics;

	public EmailSubmissionService(EmailMessageRepository messages, TenantEmailSettingsRepository emailSettings,
			QuotaService quotas, SenderIdentityService senderIdentities, Clock clock, MeterRegistry metrics) {
		this.messages = messages;
		this.emailSettings = emailSettings;
		this.quotas = quotas;
		this.senderIdentities = senderIdentities;
		this.clock = clock;
		this.metrics = metrics;
	}

	public record Submission(EmailMessage message, boolean deduplicated) {
	}

	@Transactional
	public Submission submit(UUID tenantId, SendEmailRequest request) {
		// Serializes submissions per tenant for the duration of this
		// transaction, so the quota check and the insert are atomic against
		// concurrent requests of the same tenant. Cross-tenant traffic is
		// unaffected. Released automatically at commit/rollback.
		lockTenant(tenantId);

		if (request.idempotencyKey() != null) {
			EmailMessage existing = messages
					.findByTenantIdAndIdempotencyKey(tenantId, request.idempotencyKey())
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
		quotas.check(settings, canonicalRecipient, request.limitKeysOrEmpty());

		EmailMessage message = messages.save(new EmailMessage(
				tenantId,
				request.idempotencyKey(),
				request.to(),
				canonicalRecipient,
				from,
				request.subject(),
				request.html(),
				request.text(),
				request.replyTo(),
				request.limitKeysOrEmpty(),
				clock.instant()));
		metrics.counter("herald.emails.accepted").increment();
		return new Submission(message, false);
	}

	private void lockTenant(UUID tenantId) {
		entityManager
				.createNativeQuery("select pg_advisory_xact_lock(hashtext(:tenantId))")
				.setParameter("tenantId", tenantId.toString())
				.getSingleResult();
	}
}
