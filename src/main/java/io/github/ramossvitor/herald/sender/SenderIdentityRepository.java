package io.github.ramossvitor.herald.sender;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SenderIdentityRepository extends JpaRepository<SenderIdentity, UUID> {

	List<SenderIdentity> findByTenantIdAndChannelOrderByCreatedAt(UUID tenantId, Channel channel);

	Optional<SenderIdentity> findByIdAndTenantId(UUID id, UUID tenantId);

	Optional<SenderIdentity> findByTenantIdAndChannelAndIdentifier(UUID tenantId, Channel channel, String identifier);

	boolean existsByTenantIdAndChannelAndKindAndIdentifierAndStatus(UUID tenantId, Channel channel,
			SenderIdentityKind kind, String identifier, SenderIdentityStatus status);

	boolean existsByChannelAndKindAndIdentifierAndProviderRefIsNotNull(Channel channel, SenderIdentityKind kind,
			String identifier);

	List<SenderIdentity> findTop50ByStatusAndNextCheckAtBeforeOrderByNextCheckAt(SenderIdentityStatus status,
			Instant cutoff);
}
