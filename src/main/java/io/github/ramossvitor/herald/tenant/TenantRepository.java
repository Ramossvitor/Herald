package io.github.ramossvitor.herald.tenant;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TenantRepository extends JpaRepository<Tenant, UUID> {

	boolean existsBySlug(String slug);
}
