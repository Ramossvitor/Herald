package io.github.ramossvitor.herald.quota;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Service;

import io.github.ramossvitor.herald.outbox.MessageRepository;
import io.github.ramossvitor.herald.tenant.TenantLimitPolicy;
import io.github.ramossvitor.herald.tenant.TenantLimitPolicyRepository;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * All counters derive from the outbox itself — no separate bucket state, and a
 * plain SELECT explains any rejection. Every count is scoped to one channel, so
 * a tenant's budgets never bleed into each other.
 */
@Service
public class QuotaService {

	private static final Duration DAILY_WINDOW = Duration.ofHours(24);

	private final MessageRepository messages;
	private final TenantLimitPolicyRepository limitPolicies;
	private final Clock clock;
	private final MeterRegistry metrics;

	public QuotaService(MessageRepository messages, TenantLimitPolicyRepository limitPolicies, Clock clock,
			MeterRegistry metrics) {
		this.messages = messages;
		this.limitPolicies = limitPolicies;
		this.clock = clock;
		this.metrics = metrics;
	}

	/**
	 * Checks run in the contract order of {@link QuotaReason}; the caller holds
	 * a per-tenant advisory lock, so check-then-insert cannot race itself.
	 */
	public void check(ChannelLimits limits, String canonicalRecipient, List<String> limitKeys) {
		checkRecipientCooldown(limits, canonicalRecipient);
		checkLimitKeys(limits, limitKeys);
		checkDailyLimit(limits);
	}

	private void checkRecipientCooldown(ChannelLimits limits, String canonicalRecipient) {
		if (limits.recipientCooldownSeconds() <= 0) {
			return;
		}
		Instant lastAccepted = messages
				.lastAcceptedForRecipient(limits.tenantId(), limits.channel(), canonicalRecipient)
				.orElse(null);
		if (lastAccepted == null) {
			return;
		}
		Instant cooldownEnds = lastAccepted.plusSeconds(limits.recipientCooldownSeconds());
		Instant now = clock.instant();
		if (cooldownEnds.isAfter(now)) {
			reject(limits, QuotaReason.RECIPIENT_COOLDOWN);
			throw QuotaExceededException
					.recipientCooldown(Math.max(1, Duration.between(now, cooldownEnds).toSeconds()));
		}
	}

	private void checkLimitKeys(ChannelLimits limits, List<String> limitKeys) {
		Instant cutoff = clock.instant().minus(DAILY_WINDOW);
		for (String limitKey : limitKeys) {
			TenantLimitPolicy policy = limitPolicies
					.findByTenantIdAndKeyPrefix(limits.tenantId(), prefixOf(limitKey))
					.orElse(null);
			if (policy == null) {
				// Keys without a policy still land in limit_keys — counted the
				// day a policy is created, invisible until then.
				continue;
			}
			long used = messages.countWithLimitKeySince(limits.tenantId(), limits.channel().name(), limitKey, cutoff);
			if (used >= policy.getDailyCap()) {
				reject(limits, QuotaReason.LIMIT_KEY_EXCEEDED);
				throw QuotaExceededException.limitKeyExceeded(limitKey);
			}
		}
	}

	private void checkDailyLimit(ChannelLimits limits) {
		Instant cutoff = clock.instant().minus(DAILY_WINDOW);
		if (messages.countAcceptedSince(limits.tenantId(), limits.channel(), cutoff) >= limits.dailyLimit()) {
			reject(limits, QuotaReason.TENANT_DAILY_LIMIT);
			throw QuotaExceededException.tenantDailyLimit();
		}
	}

	private static String prefixOf(String limitKey) {
		int colon = limitKey.indexOf(':');
		return colon < 0 ? limitKey : limitKey.substring(0, colon);
	}

	private void reject(ChannelLimits limits, QuotaReason reason) {
		metrics.counter("herald.messages.rejected",
				"channel", limits.channel().name().toLowerCase(),
				"reason", reason.wireName()).increment();
	}
}
