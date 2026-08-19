package io.github.ramossvitor.herald.sender;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.ramossvitor.herald.common.ConflictException;
import io.github.ramossvitor.herald.common.HeraldProperties;
import io.github.ramossvitor.herald.email.EmailAddresses;
import io.github.ramossvitor.herald.email.resend.ResendClient;
import io.github.ramossvitor.herald.email.resend.ResendDomainPayloads;
import io.github.ramossvitor.herald.tenant.TenantEmailSettings;
import io.github.ramossvitor.herald.tenant.TenantEmailSettingsRepository;

/**
 * The one gate between "a from address someone typed" and "an identity this
 * tenant is allowed to send as".
 */
@Service
public class SenderIdentityService {

	private static final Duration FIRST_CHECK = Duration.ofMinutes(1);

	/**
	 * A registration claims its domain system-wide, so an unbounded supply of
	 * them is a way to squat other people's domains and to exhaust the
	 * operator's provider account. Verified ones do not count: those are earned.
	 */
	public static final int MAX_UNVERIFIED_DOMAINS = 10;

	private final SenderIdentityRepository identities;
	private final TenantEmailSettingsRepository emailSettings;
	private final ResendClient resend;
	private final HeraldProperties.Email properties;
	private final Clock clock;

	public SenderIdentityService(SenderIdentityRepository identities, TenantEmailSettingsRepository emailSettings,
			ResendClient resend, HeraldProperties properties, Clock clock) {
		this.identities = identities;
		this.emailSettings = emailSettings;
		this.resend = resend;
		this.properties = properties.email();
		this.clock = clock;
	}

	private String sharedRootDomain() {
		return properties.sharedRootDomain().trim().toLowerCase(Locale.ROOT);
	}

	/** "Acme" with slug acme on root send.example → {@code Acme <acme@send.example>}. */
	public String defaultSharedFrom(String slug, String name) {
		String root = sharedRootDomain();
		if (root.isBlank()) {
			throw new ConflictException("fromAddress is required: no shared root domain is configured");
		}
		// Through formatFrom so a name carrying commas, quotes or brackets
		// still yields an address resolveUsable will accept.
		return EmailAddresses.formatFrom(name, slug + "@" + root);
	}

	private void requireProvider() {
		if (!resend.configured()) {
			throw new ProviderUnavailableException("the email provider is not configured");
		}
	}

	/** Called on tenant creation: a shared address always, plus the operator's
	 * own domain when they configured one explicitly. */
	@Transactional
	public void provisionFor(UUID tenantId, String slug, String explicitFromAddress) {
		String root = sharedRootDomain();
		if (!root.isBlank()) {
			identities.save(SenderIdentity.trusted(tenantId, Channel.EMAIL,
					SenderIdentityKind.EMAIL_SHARED_ADDRESS, slug + "@" + root, clock.instant()));
		}
		if (explicitFromAddress != null) {
			trustCustomDomain(tenantId, explicitFromAddress);
		}
	}

	/**
	 * The canonical form of {@code from}, or a throw when it is not an identity
	 * this tenant may send as. Callers must send what this returns rather than
	 * what they passed in: the check and the transmission have to read the same
	 * address, and only the normalized form guarantees that.
	 */
	@Transactional(readOnly = true)
	public String resolveUsable(UUID tenantId, String from) {
		String normalized = EmailAddresses.normalizeFrom(from);
		if (normalized == null) {
			throw new SenderNotVerifiedException(from);
		}
		String addrSpec = EmailAddresses.addrSpec(normalized);
		boolean sharedAddress = identities.existsByTenantIdAndChannelAndKindAndIdentifierAndStatus(tenantId,
				Channel.EMAIL, SenderIdentityKind.EMAIL_SHARED_ADDRESS, addrSpec, SenderIdentityStatus.VERIFIED);
		boolean customDomain = identities.existsByTenantIdAndChannelAndKindAndIdentifierAndStatus(tenantId,
				Channel.EMAIL, SenderIdentityKind.EMAIL_CUSTOM_DOMAIN, EmailAddresses.domainOf(addrSpec),
				SenderIdentityStatus.VERIFIED);
		if (!sharedAddress && !customDomain) {
			throw new SenderNotVerifiedException(from);
		}
		return normalized;
	}

	/**
	 * Canonical form of an address an operator configured by hand. Rejected up
	 * front rather than stored, so a tenant is never left with a from address
	 * {@link #resolveUsable} would refuse at every send.
	 */
	public String normalizeConfigured(String fromAddress) {
		String normalized = EmailAddresses.normalizeFrom(fromAddress);
		if (normalized == null) {
			throw new SenderNotVerifiedException(fromAddress);
		}
		return normalized;
	}

	/**
	 * The operator configured this from address by hand, which implies they
	 * verified its domain in the provider dashboard — trust it as VERIFIED.
	 */
	@Transactional
	public void trustCustomDomain(UUID tenantId, String fromAddress) {
		String addrSpec = EmailAddresses.addrSpec(fromAddress);
		if (addrSpec == null) {
			return;
		}
		String domain = EmailAddresses.domainOf(addrSpec);
		// A from on the shared root is covered by the tenant's own address
		// there; granting the whole root would let it send as every tenant.
		if (domain.equals(sharedRootDomain())) {
			return;
		}
		SenderIdentity existing = identities
				.findByTenantIdAndChannelAndIdentifier(tenantId, Channel.EMAIL, domain).orElse(null);
		if (existing == null) {
			identities.save(SenderIdentity.trusted(tenantId, Channel.EMAIL, SenderIdentityKind.EMAIL_CUSTOM_DOMAIN,
					domain, clock.instant()));
			return;
		}
		// A row that is not VERIFIED yet — a self-service registration still
		// pending, or one that failed — must still be promoted, or the operator
		// configures a from address that every send then refuses.
		if (existing.getStatus() != SenderIdentityStatus.VERIFIED) {
			existing.markVerified(clock.instant());
			identities.save(existing);
		}
	}

	/**
	 * Registers a custom domain with the provider and returns the DNS records
	 * its owner has to publish. Deliberately not transactional: the network
	 * call must not run inside one (same rule as the outbox worker).
	 */
	public SenderIdentity registerCustomDomain(UUID tenantId, String rawDomain, boolean allowSharedRoot) {
		String domain = rawDomain.trim().toLowerCase(Locale.ROOT);
		String root = sharedRootDomain();
		// The root itself is never registrable, by anyone: an identity covering
		// it would grant every tenant's slug@root shared address at once. The
		// operator's extra reach is subdomains of it, for a dedicated sender.
		if (!root.isBlank() && domain.equals(root)) {
			throw new ConflictException("domain is reserved: " + domain);
		}
		if (!allowSharedRoot && !root.isBlank() && domain.endsWith("." + root)) {
			throw new ConflictException("domain is reserved: " + domain);
		}
		if (identities.existsByChannelAndKindAndIdentifierAndProviderRefIsNotNull(Channel.EMAIL,
				SenderIdentityKind.EMAIL_CUSTOM_DOMAIN, domain)) {
			throw new ConflictException("domain already registered: " + domain);
		}
		SenderIdentity previous = identities
				.findByTenantIdAndChannelAndIdentifier(tenantId, Channel.EMAIL, domain).orElse(null);
		if (previous != null) {
			// A failed attempt is not a life sentence: DNS gets fixed. Anything
			// else is a domain this tenant already holds.
			if (previous.getStatus() != SenderIdentityStatus.FAILED) {
				throw new ConflictException("domain already registered: " + domain);
			}
			delete(previous, true);
		}
		if (identities.countByTenantIdAndChannelAndKindAndStatusNot(tenantId, Channel.EMAIL,
				SenderIdentityKind.EMAIL_CUSTOM_DOMAIN, SenderIdentityStatus.VERIFIED) >= MAX_UNVERIFIED_DOMAINS) {
			throw new ConflictException(
					"too many unverified domains: verify or delete one before registering another");
		}
		requireProvider();

		// Nothing is persisted until the provider has answered: a row saved
		// first and abandoned mid-call would claim the domain system-wide with
		// no provider_ref to delete it by.
		SenderIdentity identity = new SenderIdentity(tenantId, Channel.EMAIL,
				SenderIdentityKind.EMAIL_CUSTOM_DOMAIN, domain, clock.instant());
		ResendClient.Outcome outcome = resend.createDomain(domain);
		if (outcome.transportFailed() || outcome.httpStatus() >= 400) {
			throw new ProviderUnavailableException(describe("domain registration failed", outcome));
		}
		identity.recordProviderRegistration(ResendDomainPayloads.domainId(outcome.body()),
				ResendDomainPayloads.records(outcome.body()), clock.instant());
		return identities.save(identity);
	}

	/** Fires a provider verification and hands follow-up to the poller. */
	public SenderIdentity requestVerification(SenderIdentity identity) {
		if (identity.getProviderRef() == null) {
			throw new ConflictException("identity has no provider registration to verify");
		}
		requireProvider();
		// A verification the provider never accepted would leave the poller
		// asking about a check that was never started — three days of polling
		// before it gives up with a timeout that explains nothing.
		ResendClient.Outcome outcome = resend.verifyDomain(identity.getProviderRef());
		if (outcome.transportFailed() || outcome.httpStatus() >= 400) {
			throw new ProviderUnavailableException(describe("verification request failed", outcome));
		}
		Instant now = clock.instant();
		identity.startVerifying(now.plus(FIRST_CHECK), now);
		return identities.save(identity);
	}

	/**
	 * {@code force} is the operator's override. A tenant may not drop the
	 * identity its own from address resolves to: every later send would 422 and
	 * nothing in the tenant API could put the identity back.
	 */
	public void delete(SenderIdentity identity, boolean force) {
		if (!force) {
			requireNotTheConfiguredSender(identity);
		}
		if (identity.getProviderRef() != null) {
			requireProvider();
			ResendClient.Outcome outcome = resend.deleteDomain(identity.getProviderRef());
			// 4xx (already gone upstream) is fine — the goal is absence.
			if (outcome.transportFailed() || outcome.httpStatus() >= 500) {
				throw new ProviderUnavailableException(describe("domain deletion failed", outcome));
			}
		}
		identities.deleteById(identity.getId());
	}

	private void requireNotTheConfiguredSender(SenderIdentity identity) {
		String configured = emailSettings.findById(identity.getTenantId())
				.map(TenantEmailSettings::getFromAddress)
				.map(EmailAddresses::addrSpec)
				.orElse(null);
		if (configured == null) {
			return;
		}
		boolean inUse = switch (identity.getKind()) {
			case EMAIL_SHARED_ADDRESS -> identity.getIdentifier().equals(configured);
			case EMAIL_CUSTOM_DOMAIN -> identity.getIdentifier().equals(EmailAddresses.domainOf(configured));
		};
		if (inUse) {
			throw new ConflictException("identity is the tenant's configured sender: " + identity.getIdentifier());
		}
	}

	private static String describe(String action, ResendClient.Outcome outcome) {
		if (outcome.transportFailed()) {
			return action + ": transport: " + outcome.transportError();
		}
		return action + ": http " + outcome.httpStatus();
	}
}
