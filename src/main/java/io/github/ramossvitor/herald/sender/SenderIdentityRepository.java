package io.github.ramossvitor.herald.sender;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SenderIdentityRepository extends JpaRepository<SenderIdentity, UUID> {

	List<SenderIdentity> findByTenantIdOrderByCreatedAt(UUID tenantId);

	Optional<SenderIdentity> findByIdAndTenantId(UUID id, UUID tenantId);

	Optional<SenderIdentity> findByTenantIdAndIdentifier(UUID tenantId, String identifier);

	boolean existsByTenantIdAndKindAndIdentifierAndStatus(UUID tenantId, SenderIdentityKind kind, String identifier,
			SenderIdentityStatus status);

	boolean existsByKindAndIdentifierAndProviderRefIsNotNull(SenderIdentityKind kind, String identifier);

	long countByTenantIdAndKindAndStatusNot(UUID tenantId, SenderIdentityKind kind, SenderIdentityStatus status);

	List<SenderIdentity> findTop50ByStatusAndNextCheckAtBeforeOrderByNextCheckAt(SenderIdentityStatus status,
			Instant cutoff);
}
