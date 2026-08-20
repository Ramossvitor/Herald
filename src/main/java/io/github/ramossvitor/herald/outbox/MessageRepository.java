package io.github.ramossvitor.herald.outbox;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import io.github.ramossvitor.herald.sender.Channel;

/**
 * Every read is scoped to one channel: quota budgets, idempotency keys and
 * status lookups are all per channel, so a tenant's WhatsApp traffic can never
 * spend its email allowance or collide with an email idempotency key.
 */
public interface MessageRepository extends JpaRepository<Message, UUID> {

	Optional<Message> findByTenantIdAndChannelAndIdempotencyKey(UUID tenantId, Channel channel, String idempotencyKey);

	Optional<Message> findByIdAndTenantIdAndChannel(UUID id, UUID tenantId, Channel channel);

	/** How a provider's delivery receipt finds the row it is about. Scoped to
	 * the tenant so one cannot be handed another's message by guessing an id. */
	Optional<Message> findByTenantIdAndChannelAndProviderMessageId(UUID tenantId, Channel channel,
			String providerMessageId);

	/** Daily-window counter. Counts accepted rows regardless of status: a
	 * message that later failed still consumed provider attempts. */
	@Query("select count(m) from Message m "
			+ "where m.tenantId = :tenantId and m.channel = :channel and m.createdAt > :cutoff")
	long countAcceptedSince(UUID tenantId, Channel channel, Instant cutoff);

	@Query("select max(m.createdAt) from Message m "
			+ "where m.tenantId = :tenantId and m.channel = :channel and m.recipientCanonical = :recipient")
	Optional<Instant> lastAcceptedForRecipient(UUID tenantId, Channel channel, String recipient);

	/** {@code @>} (array contains) rides the GIN index on limit_keys. */
	@Query(value = "select count(*) from messages "
			+ "where tenant_id = :tenantId and channel = :channel and created_at > :cutoff "
			+ "and limit_keys @> cast(array[:limitKey] as text[])", nativeQuery = true)
	long countWithLimitKeySince(UUID tenantId, String channel, String limitKey, Instant cutoff);
}
