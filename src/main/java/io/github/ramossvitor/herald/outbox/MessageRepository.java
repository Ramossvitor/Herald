package io.github.ramossvitor.herald.outbox;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/**
 * Every read is scoped to one tenant: quota budgets, idempotency keys and
 * status lookups all belong to exactly one, and nothing here may answer across
 * that boundary.
 */
public interface MessageRepository extends JpaRepository<Message, UUID> {

	Optional<Message> findByTenantIdAndIdempotencyKey(UUID tenantId, String idempotencyKey);

	Optional<Message> findByIdAndTenantId(UUID id, UUID tenantId);

	/** Daily-window counter. Counts accepted rows regardless of status: a
	 * message that later failed still consumed provider attempts. */
	@Query("select count(m) from Message m where m.tenantId = :tenantId and m.createdAt > :cutoff")
	long countAcceptedSince(UUID tenantId, Instant cutoff);

	@Query("select max(m.createdAt) from Message m "
			+ "where m.tenantId = :tenantId and m.recipientCanonical = :recipient")
	Optional<Instant> lastAcceptedForRecipient(UUID tenantId, String recipient);

	/** {@code @>} (array contains) rides the GIN index on limit_keys. */
	@Query(value = "select count(*) from messages "
			+ "where tenant_id = :tenantId and created_at > :cutoff "
			+ "and limit_keys @> cast(array[:limitKey] as text[])", nativeQuery = true)
	long countWithLimitKeySince(UUID tenantId, String limitKey, Instant cutoff);
}
