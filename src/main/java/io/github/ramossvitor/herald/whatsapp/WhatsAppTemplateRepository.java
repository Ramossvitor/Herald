package io.github.ramossvitor.herald.whatsapp;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface WhatsAppTemplateRepository extends JpaRepository<WhatsAppTemplate, UUID> {

	Optional<WhatsAppTemplate> findByTenantIdAndNameAndLanguage(UUID tenantId, String name, String language);

	List<WhatsAppTemplate> findByTenantIdOrderByNameAscLanguageAsc(UUID tenantId);

	List<WhatsAppTemplate> findByTenantId(UUID tenantId);
}
