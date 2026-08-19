package io.github.ramossvitor.herald.sender;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import io.github.ramossvitor.herald.email.resend.ResendClient;
import io.github.ramossvitor.herald.email.resend.ResendDomainPayloads;

/**
 * Polls the provider for domains awaiting DNS verification. Rides the same
 * five-minute cadence as OutboxRecovery so serverless Postgres sees no new
 * wake-up regime; per-row pacing lives in next_check_at.
 */
@Component
@Lazy(false) // see OutboxWorker
public class SenderIdentityVerifier {

	private static final Logger log = LoggerFactory.getLogger(SenderIdentityVerifier.class);

	/** Roughly three days on the ladder below — DNS that has not landed by
	 * then was misconfigured, not slow. */
	public static final int MAX_CHECKS = 80;

	private final SenderIdentityRepository identities;
	private final ResendClient resend;
	private final Clock clock;

	public SenderIdentityVerifier(SenderIdentityRepository identities, ResendClient resend, Clock clock) {
		this.identities = identities;
		this.resend = resend;
		this.clock = clock;
	}

	@Scheduled(fixedDelayString = "PT5M", initialDelayString = "PT5M")
	void run() {
		runOnce();
	}

	/** One pass over everything due. Public for tests. */
	public int runOnce() {
		if (!resend.configured()) {
			return 0;
		}
		List<SenderIdentity> due = identities.findTop50ByStatusAndNextCheckAtBeforeOrderByNextCheckAt(
				SenderIdentityStatus.VERIFYING, clock.instant());
		for (SenderIdentity identity : due) {
			try {
				check(identity);
			}
			catch (RuntimeException ex) {
				// One unhappy row must not cost the rest of the batch its tick.
				log.error("sender domain check failed: {}", identity.getIdentifier(), ex);
			}
		}
		return due.size();
	}

	private void check(SenderIdentity identity) {
		Instant now = clock.instant();
		ResendClient.Outcome outcome = resend.getDomain(identity.getProviderRef());
		if (!outcome.transportFailed() && outcome.httpStatus() < 400) {
			switch (ResendDomainPayloads.status(outcome.body())) {
				case VERIFIED -> {
					identity.markVerified(now);
					identities.save(identity);
					log.info("sender domain verified: {}", identity.getIdentifier());
					return;
				}
				case FAILED -> {
					fail(identity, "provider reported failed verification", now);
					log.warn("sender domain failed verification: {}", identity.getIdentifier());
					return;
				}
				case PENDING -> {
					// DNS has not propagated yet; reschedule below.
				}
			}
		}
		if (identity.getCheckAttempts() + 1 >= MAX_CHECKS) {
			fail(identity, "verification timed out" + sinceLastProviderError(outcome), now);
			return;
		}
		// Carry the provider's last word forward: without it a domain that
		// failed eighty calls ends as a bare timeout that explains nothing.
		identity.recordCheck(now.plus(nextDelay(identity.getCheckAttempts() + 1)), now,
				describe(outcome));
		identities.save(identity);
	}

	/**
	 * The row stays — the tenant has to be able to read why it failed — but the
	 * provider registration behind it does not. Dropping it frees the slot in
	 * the operator's provider account and, because the system-wide claim is
	 * keyed on a non-null provider_ref, releases the domain for whoever can
	 * actually prove they own it.
	 */
	private void fail(SenderIdentity identity, String error, Instant now) {
		String providerRef = identity.getProviderRef();
		identity.markFailed(error, now);
		if (providerRef != null) {
			ResendClient.Outcome outcome = resend.deleteDomain(providerRef);
			if (outcome.transportFailed() || outcome.httpStatus() >= 500) {
				// Keep the ref so a later attempt can still release it.
				log.warn("could not release failed sender domain {} upstream: {}",
						identity.getIdentifier(), describe(outcome));
			}
			else {
				identity.releaseProviderRegistration();
			}
		}
		identities.save(identity);
	}

	private static String describe(ResendClient.Outcome outcome) {
		if (outcome.transportFailed()) {
			return "transport: " + outcome.transportError();
		}
		return outcome.httpStatus() >= 400 ? "provider answered http " + outcome.httpStatus() : null;
	}

	private static String sinceLastProviderError(ResendClient.Outcome outcome) {
		String detail = describe(outcome);
		return detail == null ? "" : " (last: " + detail + ")";
	}

	/** Escalating ladder: quick at first, hourly once it is clearly slow DNS. */
	static Duration nextDelay(int checkAttempts) {
		if (checkAttempts < 5) {
			return Duration.ofMinutes(1);
		}
		if (checkAttempts < 10) {
			return Duration.ofMinutes(5);
		}
		if (checkAttempts < 15) {
			return Duration.ofMinutes(15);
		}
		return Duration.ofHours(1);
	}
}
