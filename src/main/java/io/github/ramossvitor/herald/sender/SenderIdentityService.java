package io.github.ramossvitor.herald.sender;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.ramossvitor.herald.common.ConflictException;
import io.github.ramossvitor.herald.email.EmailAddresses;
import io.github.ramossvitor.herald.email.resend.ResendClient;
import io.github.ramossvitor.herald.email.resend.ResendDomainPayloads;

/**
 * The one gate between "a from address someone typed" and "an identity this
 * tenant is allowed to send as".
 */
@Service
public class SenderIdentityService {

	private static final Duration FIRST_CHECK = Duration.ofMinutes(1);

	private final SenderIdentityRepository identities;
	private final ResendClient resend;
	private final Clock clock;

	public SenderIdentityService(SenderIdentityRepository identities, ResendClient resend, Clock clock) {
		this.identities = identities;
		this.resend = resend;
		this.clock = clock;
	}

	/** Throws unless {@code from} resolves to a VERIFIED identity of the tenant. */
	@Transactional(readOnly = true)
	public void requireUsable(UUID tenantId, String from) {
		String addrSpec = EmailAddresses.addrSpec(from);
		if (addrSpec == null) {
			throw new SenderNotVerifiedException(from);
		}
		boolean sharedAddress = identities.existsByTenantIdAndChannelAndKindAndIdentifierAndStatus(tenantId,
				Channel.EMAIL, SenderIdentityKind.EMAIL_SHARED_ADDRESS, addrSpec, SenderIdentityStatus.VERIFIED);
		boolean customDomain = identities.existsByTenantIdAndChannelAndKindAndIdentifierAndStatus(tenantId,
				Channel.EMAIL, SenderIdentityKind.EMAIL_CUSTOM_DOMAIN, EmailAddresses.domainOf(addrSpec),
				SenderIdentityStatus.VERIFIED);
		if (!sharedAddress && !customDomain) {
			throw new SenderNotVerifiedException(from);
		}
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
		if (identities.findByTenantIdAndChannelAndIdentifier(tenantId, Channel.EMAIL, domain).isPresent()) {
			return;
		}
		identities.save(SenderIdentity.trusted(tenantId, Channel.EMAIL, SenderIdentityKind.EMAIL_CUSTOM_DOMAIN,
				domain, clock.instant()));
	}

	/**
	 * Registers a custom domain with the provider and returns the DNS records
	 * its owner has to publish. Deliberately not transactional: the network
	 * call must not run inside one (same rule as the outbox worker).
	 */
	public SenderIdentity registerCustomDomain(UUID tenantId, String rawDomain) {
		String domain = rawDomain.trim().toLowerCase(Locale.ROOT);
		if (identities.existsByChannelAndKindAndIdentifierAndProviderRefIsNotNull(Channel.EMAIL,
				SenderIdentityKind.EMAIL_CUSTOM_DOMAIN, domain)
				|| identities.findByTenantIdAndChannelAndIdentifier(tenantId, Channel.EMAIL, domain).isPresent()) {
			throw new ConflictException("domain already registered: " + domain);
		}
		SenderIdentity identity = identities.save(new SenderIdentity(tenantId, Channel.EMAIL,
				SenderIdentityKind.EMAIL_CUSTOM_DOMAIN, domain, clock.instant()));

		ResendClient.Outcome outcome = resend.createDomain(domain);
		if (outcome.transportFailed() || outcome.httpStatus() >= 400) {
			// Nothing was created upstream, so leaving a row behind would only
			// block the tenant from retrying.
			identities.deleteById(identity.getId());
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
		resend.verifyDomain(identity.getProviderRef());
		Instant now = clock.instant();
		identity.startVerifying(now.plus(FIRST_CHECK), now);
		return identities.save(identity);
	}

	public void delete(SenderIdentity identity) {
		if (identity.getProviderRef() != null) {
			ResendClient.Outcome outcome = resend.deleteDomain(identity.getProviderRef());
			// 4xx (already gone upstream) is fine — the goal is absence.
			if (outcome.transportFailed() || outcome.httpStatus() >= 500) {
				throw new ProviderUnavailableException(describe("domain deletion failed", outcome));
			}
		}
		identities.deleteById(identity.getId());
	}

	private static String describe(String action, ResendClient.Outcome outcome) {
		if (outcome.transportFailed()) {
			return action + ": transport: " + outcome.transportError();
		}
		return action + ": http " + outcome.httpStatus();
	}
}
