package io.github.ramossvitor.herald.whatsapp;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.ramossvitor.herald.common.ConflictException;
import io.github.ramossvitor.herald.common.NotFoundException;
import io.github.ramossvitor.herald.security.Secrets;
import io.github.ramossvitor.herald.tenant.TenantRepository;
import io.github.ramossvitor.herald.whatsapp.meta.WhatsAppClient;
import io.github.ramossvitor.herald.whatsapp.meta.WhatsAppResponseClassifier;

/**
 * Onboarding for a tenant's own WhatsApp account.
 *
 * The credentials arrive once, in one request, and are never readable again:
 * they go in encrypted and every response afterwards answers with ids and a
 * status. Verification is synchronous because the operator entering them is
 * standing right there — a typo in a phone number id should come back as an
 * error on that request, not as failed messages an hour later.
 */
@Service
public class WhatsAppAdminService {

	private static final Logger log = LoggerFactory.getLogger(WhatsAppAdminService.class);

	private final TenantRepository tenants;
	private final TenantWhatsAppSettingsRepository settings;
	private final WhatsAppTemplateRepository templates;
	private final WhatsAppClient client;
	private final WhatsAppTemplateSync templateSync;
	private final Secrets secrets;
	private final Clock clock;

	public WhatsAppAdminService(TenantRepository tenants, TenantWhatsAppSettingsRepository settings,
			WhatsAppTemplateRepository templates, WhatsAppClient client, WhatsAppTemplateSync templateSync,
			Secrets secrets, Clock clock) {
		this.tenants = tenants;
		this.settings = settings;
		this.templates = templates;
		this.client = client;
		this.templateSync = templateSync;
		this.secrets = secrets;
		this.clock = clock;
	}

	public record Credentials(String phoneNumberId, String wabaId, String accessToken, String appSecret,
			int dailyLimit, int recipientCooldownSeconds) {
	}

	/**
	 * Deliberately not one transaction around the network call, same rule as the
	 * sender-identity path: the credentials are committed first, then proven.
	 * A provider outage then leaves them stored and PENDING, to be re-verified,
	 * rather than making the operator type a token in again.
	 */
	public TenantWhatsAppSettings register(UUID tenantId, Credentials credentials) {
		if (!secrets.available()) {
			throw new ConflictException(
					"HERALD_SECRET_KEY is not set; refusing to store provider credentials unencrypted");
		}
		if (!tenants.existsById(tenantId)) {
			throw new NotFoundException("tenant not found: " + tenantId);
		}
		settings.findByWabaId(credentials.wabaId())
				.filter(existing -> !existing.getTenantId().equals(tenantId))
				.ifPresent(existing -> {
					// The webhook routes by WABA id alone. Two tenants on one
					// account would make an inbound receipt ambiguous, and the
					// wrong tenant would be handed the wrong app secret.
					throw new ConflictException("WhatsApp Business Account already registered: "
							+ credentials.wabaId());
				});

		Instant now = clock.instant();
		String token = secrets.encrypt(credentials.accessToken(),
				TenantWhatsAppSettings.accessTokenContext(tenantId));
		String appSecret = secrets.encrypt(credentials.appSecret(),
				TenantWhatsAppSettings.appSecretContext(tenantId));

		TenantWhatsAppSettings stored = settings.findById(tenantId)
				.map(existing -> {
					// A tenant moving to a different WhatsApp Business Account
					// takes none of the old one's templates with it. Pruning
					// normally happens inside the sync, but that is best effort —
					// and a first sync that fails would otherwise leave a mirror
					// confidently describing an account this tenant no longer
					// owns, admitting sends Meta never approved.
					if (!existing.getWabaId().equals(credentials.wabaId())) {
						templates.deleteAll(templates.findByTenantId(tenantId));
					}
					existing.replaceCredentials(credentials.phoneNumberId(), credentials.wabaId(), token, appSecret,
							now);
					existing.updateLimits(credentials.dailyLimit(), credentials.recipientCooldownSeconds(), now);
					return existing;
				})
				.orElseGet(() -> new TenantWhatsAppSettings(tenantId, credentials.phoneNumberId(),
						credentials.wabaId(), token, appSecret, credentials.dailyLimit(),
						credentials.recipientCooldownSeconds(), now));
		settings.saveAndFlush(stored);

		return verify(tenantId);
	}

	/**
	 * Proves the credentials by reading the sending number back — the cheapest
	 * call that exercises the token and the phone number id together.
	 */
	public TenantWhatsAppSettings verify(UUID tenantId) {
		TenantWhatsAppSettings stored = require(tenantId);
		WhatsAppClient.Outcome outcome = client.getPhoneNumber(
				secrets.decrypt(stored.getAccessToken(), TenantWhatsAppSettings.accessTokenContext(tenantId)),
				stored.getPhoneNumberId());
		Instant now = clock.instant();

		if (!outcome.transportFailed() && outcome.httpStatus() < 400) {
			stored.markVerified(now);
			settings.save(stored);
			// Best effort: a tenant with no templates yet is a normal state, and
			// a sync failure must not turn a good registration into a bad one.
			try {
				templateSync.sync(stored);
			}
			catch (RuntimeException ex) {
				log.warn("credentials verified for tenant {} but the first template sync failed", tenantId, ex);
			}
			return stored;
		}
		if (outcome.transportFailed() || outcome.httpStatus() >= 500) {
			// Meta's problem, not the credentials'. Leave them PENDING so a
			// retry can prove them without re-entering anything.
			log.warn("could not reach Meta to verify tenant {}", tenantId);
			return stored;
		}
		stored.markFailed(WhatsAppResponseClassifier.describe(outcome.httpStatus(), outcome.body()), now);
		settings.save(stored);
		return stored;
	}

	@Transactional
	public TenantWhatsAppSettings updateLimits(UUID tenantId, int dailyLimit, int recipientCooldownSeconds) {
		TenantWhatsAppSettings stored = require(tenantId);
		stored.updateLimits(dailyLimit, recipientCooldownSeconds, clock.instant());
		return stored;
	}

	@Transactional(readOnly = true)
	public TenantWhatsAppSettings get(UUID tenantId) {
		return require(tenantId);
	}

	/** Drops the credentials and the mirror they explain. Queued messages stay,
	 * and fail at dispatch with a reason — losing them silently would be worse. */
	@Transactional
	public void delete(UUID tenantId) {
		TenantWhatsAppSettings stored = require(tenantId);
		templates.deleteAll(templates.findByTenantId(tenantId));
		settings.delete(stored);
	}

	private TenantWhatsAppSettings require(UUID tenantId) {
		return settings.findById(tenantId)
				.orElseThrow(() -> new NotFoundException("tenant has no WhatsApp settings: " + tenantId));
	}
}
