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
	static final int MAX_CHECKS = 80;

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
			check(identity);
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
					identity.markFailed("provider reported failed verification", now);
					identities.save(identity);
					log.warn("sender domain failed verification: {}", identity.getIdentifier());
					return;
				}
				case PENDING -> {
					// DNS has not propagated yet; reschedule below.
				}
			}
		}
		if (identity.getCheckAttempts() + 1 >= MAX_CHECKS) {
			identity.markFailed("verification timed out", now);
		}
		else {
			identity.recordCheck(now.plus(nextDelay(identity.getCheckAttempts() + 1)), now);
		}
		identities.save(identity);
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
