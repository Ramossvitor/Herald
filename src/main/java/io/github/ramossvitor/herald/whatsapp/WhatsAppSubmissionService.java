package io.github.ramossvitor.herald.whatsapp;

import java.time.Clock;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.ramossvitor.herald.outbox.Message;
import io.github.ramossvitor.herald.outbox.MessageRepository;
import io.github.ramossvitor.herald.quota.ChannelLimits;
import io.github.ramossvitor.herald.quota.QuotaService;
import io.github.ramossvitor.herald.sender.Channel;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * Same contract as the email path: quota is decided synchronously, in the
 * transaction that inserts the outbox row, and transport is the worker's
 * problem.
 *
 * What this channel adds ahead of the quota check is a template gate. Meta
 * would refuse an unapproved template or a wrong argument count anyway; catching
 * it here turns a FAILED row discovered minutes later into a 422 the caller can
 * act on, and spends no attempt doing it.
 */
@Service
public class WhatsAppSubmissionService {

	@PersistenceContext
	private EntityManager entityManager;

	private final MessageRepository messages;
	private final TenantWhatsAppSettingsRepository settings;
	private final WhatsAppTemplateRepository templates;
	private final QuotaService quotas;
	private final Clock clock;
	private final MeterRegistry metrics;

	public WhatsAppSubmissionService(MessageRepository messages, TenantWhatsAppSettingsRepository settings,
			WhatsAppTemplateRepository templates, QuotaService quotas, Clock clock, MeterRegistry metrics) {
		this.messages = messages;
		this.settings = settings;
		this.templates = templates;
		this.quotas = quotas;
		this.clock = clock;
		this.metrics = metrics;
	}

	public record Submission(Message message, boolean deduplicated) {
	}

	@Transactional
	public Submission submit(UUID tenantId, SendWhatsAppRequest request) {
		// Same per-tenant advisory lock as email, so check-then-insert cannot
		// race itself. Taken on the tenant, not the channel: a tenant's two
		// channels serialize against each other, which costs nothing at these
		// volumes and keeps the lock a single, obvious thing.
		lockTenant(tenantId);

		if (request.idempotencyKey() != null) {
			Message existing = messages
					.findByTenantIdAndChannelAndIdempotencyKey(tenantId, Channel.WHATSAPP, request.idempotencyKey())
					.orElse(null);
			if (existing != null) {
				return new Submission(existing, true);
			}
		}

		TenantWhatsAppSettings tenantSettings = settings.findById(tenantId)
				.orElseThrow(() -> new WhatsAppNotConfiguredException(
						"tenant has no WhatsApp credentials; register a number first"));
		if (tenantSettings.getStatus() != WhatsAppSettingsStatus.VERIFIED) {
			throw new WhatsAppNotConfiguredException(
					"WhatsApp credentials are " + tenantSettings.getStatus() + ", not VERIFIED");
		}

		// Before the quota check on purpose: a bad template is a caller mistake
		// and must never surface as a quota rejection.
		requireUsableTemplate(tenantId, request);

		String canonicalRecipient = PhoneNumbers.canonicalize(request.to());
		if (canonicalRecipient == null) {
			throw new InvalidRecipientException(request.to(),
					"to must be an E.164 number with a country code, 8 to 15 digits");
		}

		quotas.check(limitsFor(tenantSettings), canonicalRecipient, request.limitKeysOrEmpty());

		Message message = messages.save(new Message(
				tenantId,
				Channel.WHATSAPP,
				request.idempotencyKey(),
				request.to(),
				canonicalRecipient,
				tenantSettings.getPhoneNumberId(),
				WhatsAppPayload.of(request.template(), request.language(), request.paramsOrEmpty()),
				request.limitKeysOrEmpty(),
				clock.instant()));
		metrics.counter("herald.messages.accepted", "channel", "whatsapp").increment();
		return new Submission(message, false);
	}

	private void requireUsableTemplate(UUID tenantId, SendWhatsAppRequest request) {
		WhatsAppTemplate template = templates
				.findByTenantIdAndNameAndLanguage(tenantId, request.template(), request.language())
				.orElseThrow(() -> TemplateNotUsableException.unknown(request.template(), request.language()));
		// Before the status check, so an approved template Herald cannot fill
		// reports why rather than claiming it was not approved.
		if (template.hasUnsupportedParams()) {
			throw TemplateNotUsableException.unsupportedParameters(template);
		}
		if (!template.isSendable()) {
			throw TemplateNotUsableException.notApproved(template);
		}
		// Meta fills placeholders by position, so a count that does not match is
		// not a near miss — every argument after the gap lands in the wrong blank.
		if (template.getBodyParamCount() != request.paramsOrEmpty().size()) {
			throw TemplateNotUsableException.wrongArity(template, request.paramsOrEmpty().size());
		}
	}

	private static ChannelLimits limitsFor(TenantWhatsAppSettings settings) {
		return new ChannelLimits(settings.getTenantId(), Channel.WHATSAPP, settings.getDailyLimit(),
				settings.getRecipientCooldownSeconds());
	}

	private void lockTenant(UUID tenantId) {
		entityManager
				.createNativeQuery("select pg_advisory_xact_lock(hashtext(:tenantId))")
				.setParameter("tenantId", tenantId.toString())
				.getSingleResult();
	}
}
