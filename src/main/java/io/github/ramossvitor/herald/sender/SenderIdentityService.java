package io.github.ramossvitor.herald.sender;

import java.time.Clock;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.ramossvitor.herald.email.EmailAddresses;

/**
 * The one gate between "a from address someone typed" and "an identity this
 * tenant is allowed to send as".
 */
@Service
public class SenderIdentityService {

	private final SenderIdentityRepository identities;
	private final Clock clock;

	public SenderIdentityService(SenderIdentityRepository identities, Clock clock) {
		this.identities = identities;
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
}
