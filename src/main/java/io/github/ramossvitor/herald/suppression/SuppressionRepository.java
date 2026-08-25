package io.github.ramossvitor.herald.suppression;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Every read is scoped to one tenant: a suppression is never global. */
public interface SuppressionRepository extends JpaRepository<Suppression, UUID> {

	Optional<Suppression> findByTenantIdAndRecipientCanonical(UUID tenantId, String recipientCanonical);

	List<Suppression> findByTenantIdOrderByCreatedAtDesc(UUID tenantId);

	/**
	 * Adds an address, or does nothing if it is already there.
	 *
	 * Native, and letting the unique constraint decide, because a read-then-save
	 * cannot: two webhooks for the same recipient — two bounces to one dead
	 * address is the ordinary case — would both find nothing and both insert.
	 * The loser's constraint violation would abort a transaction that is also
	 * carrying the delivery-state update and the {@code webhook_events} row that
	 * makes the event's replay a no-op, so the event would be lost outright.
	 * {@code ON CONFLICT DO NOTHING} keeps the caller's transaction intact.
	 *
	 * The id comes from the column default rather than the entity constructor,
	 * since this bypasses the persistence context entirely.
	 *
	 * @return 1 when the row was inserted, 0 when one already existed
	 */
	@Modifying
	@Query(value = """
			insert into suppressions (tenant_id, recipient_canonical, reason, detail, source_message_id, created_at)
			values (:tenantId, :recipientCanonical, :reason, cast(:detail as text),
			        cast(:sourceMessageId as uuid), :createdAt)
			on conflict (tenant_id, recipient_canonical) do nothing
			""", nativeQuery = true)
	int insertIfAbsent(@Param("tenantId") UUID tenantId, @Param("recipientCanonical") String recipientCanonical,
			@Param("reason") String reason, @Param("detail") String detail,
			@Param("sourceMessageId") UUID sourceMessageId, @Param("createdAt") Instant createdAt);
}
