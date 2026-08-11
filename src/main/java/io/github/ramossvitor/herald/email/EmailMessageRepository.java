package io.github.ramossvitor.herald.email;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface EmailMessageRepository extends JpaRepository<EmailMessage, UUID> {

	Optional<EmailMessage> findByTenantIdAndIdempotencyKey(UUID tenantId, String idempotencyKey);

	Optional<EmailMessage> findByIdAndTenantId(UUID id, UUID tenantId);

	/** Daily-window counter. Counts accepted rows regardless of status: a
	 * message that later failed still consumed provider attempts. */
	@Query("select count(m) from EmailMessage m where m.tenantId = :tenantId and m.createdAt > :cutoff")
	long countAcceptedSince(UUID tenantId, Instant cutoff);

	@Query("select max(m.createdAt) from EmailMessage m "
			+ "where m.tenantId = :tenantId and m.recipientCanonical = :recipient")
	Optional<Instant> lastAcceptedForRecipient(UUID tenantId, String recipient);

	/** {@code @>} (array contains) rides the GIN index on limit_keys. */
	@Query(value = "select count(*) from email_messages "
			+ "where tenant_id = :tenantId and created_at > :cutoff "
			+ "and limit_keys @> cast(array[:limitKey] as text[])", nativeQuery = true)
	long countWithLimitKeySince(UUID tenantId, String limitKey, Instant cutoff);
}
