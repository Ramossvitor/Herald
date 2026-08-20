package io.github.ramossvitor.herald.whatsapp;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TenantWhatsAppSettingsRepository extends JpaRepository<TenantWhatsAppSettings, UUID> {

	/** How an inbound webhook finds its tenant: the payload names a WABA and
	 * nothing else that identifies the sender. */
	Optional<TenantWhatsAppSettings> findByWabaId(String wabaId);

	List<TenantWhatsAppSettings> findAllByStatus(WhatsAppSettingsStatus status);
}
