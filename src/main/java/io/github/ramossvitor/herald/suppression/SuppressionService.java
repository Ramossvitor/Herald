package io.github.ramossvitor.herald.suppression;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.ramossvitor.herald.common.NotFoundException;
import io.github.ramossvitor.herald.email.EmailAddresses;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * The list of addresses a tenant may no longer mail, and the one gate that
 * enforces it.
 *
 * A suppression exists to protect the sending domain's reputation: mailbox
 * providers read a continued stream of bounces as a sender who does not clean
 * its list, and the tier every tenant starts on shares one domain — so an
 * unchecked bounce loop in one tenant degrades delivery for all of them.
 */
@Service
public class SuppressionService {

	private static final Logger log = LoggerFactory.getLogger(SuppressionService.class);

	private final SuppressionRepository suppressions;
	private final Clock clock;
	private final MeterRegistry metrics;

	public SuppressionService(SuppressionRepository suppressions, Clock clock, MeterRegistry metrics) {
		this.suppressions = suppressions;
		this.clock = clock;
		this.metrics = metrics;
	}

	/**
	 * Called on the submission path, before the quota gates. Takes the already
	 * canonicalized recipient so the caller's spelling cannot slip past an entry
	 * that was recorded under another one.
	 */
	@Transactional(readOnly = true)
	public void requireNotSuppressed(UUID tenantId, String canonicalRecipient) {
		suppressions.findByTenantIdAndRecipientCanonical(tenantId, canonicalRecipient)
				.ifPresent(suppression -> {
					throw new SuppressedRecipientException(canonicalRecipient, suppression.getReason());
				});
	}

	/**
	 * Adds an address, or leaves the existing entry alone. The first reason is
	 * kept rather than overwritten: a bounce followed by a complaint is still,
	 * primarily, an address that bounces, and the earliest evidence is the one
	 * that dates the decision.
	 *
	 * "Already there" is decided by the unique constraint rather than by a read
	 * beforehand — see {@link SuppressionRepository#insertIfAbsent}, which
	 * explains why a losing race here would cost the caller its whole
	 * transaction. Nothing is counted or logged when the row already existed,
	 * so a second bounce for the same address stays as quiet as it was before.
	 */
	@Transactional
	public void suppress(UUID tenantId, String canonicalRecipient, SuppressionReason reason, String detail,
			UUID sourceMessageId) {
		int inserted = suppressions.insertIfAbsent(tenantId, canonicalRecipient, reason.name(), detail, sourceMessageId,
				clock.instant());
		if (inserted == 0) {
			return;
		}
		metrics.counter("herald.suppressions.added", "reason", reason.wireName()).increment();
		log.info("suppressed {} for tenant {}: {}", canonicalRecipient, tenantId, reason);
	}

	@Transactional(readOnly = true)
	public List<Suppression> list(UUID tenantId) {
		return suppressions.findByTenantIdOrderByCreatedAtDesc(tenantId);
	}

	/**
	 * Lifts a suppression. Canonicalizes first: an address suppressed as one
	 * Gmail spelling has to be removable by any of them, or the entry becomes
	 * unreachable through the API that created it.
	 */
	@Transactional
	public void remove(UUID tenantId, String recipient) {
		String canonical = EmailAddresses.canonicalize(recipient);
		Suppression suppression = suppressions.findByTenantIdAndRecipientCanonical(tenantId, canonical)
				.orElseThrow(() -> new NotFoundException("recipient is not suppressed: " + recipient));
		suppressions.delete(suppression);
		log.info("lifted suppression for {} on tenant {}", canonical, tenantId);
	}
}
