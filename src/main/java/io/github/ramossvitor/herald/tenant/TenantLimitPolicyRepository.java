package io.github.ramossvitor.herald.tenant;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TenantLimitPolicyRepository extends JpaRepository<TenantLimitPolicy, UUID> {

	Optional<TenantLimitPolicy> findByTenantIdAndKeyPrefix(UUID tenantId, String keyPrefix);

	List<TenantLimitPolicy> findByTenantId(UUID tenantId);

	void deleteByTenantId(UUID tenantId);
}
