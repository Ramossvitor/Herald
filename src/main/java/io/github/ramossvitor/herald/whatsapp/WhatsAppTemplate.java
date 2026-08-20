package io.github.ramossvitor.herald.whatsapp;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A local mirror of one template Meta knows about. Never the source of truth:
 * Meta can reject, pause or reclassify a template at any time, so this row is
 * only ever as fresh as the last sync.
 *
 * {@code status} and {@code category} are stored as text rather than enums on
 * purpose — they are Meta's vocabulary, and it grows without asking.
 */
@Entity
@Table(name = "whatsapp_templates")
public class WhatsAppTemplate {

	/** The only status a message may be sent under. */
	public static final String APPROVED = "APPROVED";

	@Id
	private UUID id;

	@Column(name = "tenant_id", nullable = false)
	private UUID tenantId;

	@Column(nullable = false)
	private String name;

	@Column(nullable = false)
	private String language;

	@Column(nullable = false)
	private String category;

	@Column(nullable = false)
	private String status;

	@Column(name = "body_param_count", nullable = false)
	private int bodyParamCount;

	/** The template wants a value in a header or a button URL, which Herald has
	 * no way to supply — see {@code WhatsAppTemplatePayloads.unsupportedParams}. */
	@Column(name = "unsupported_params", nullable = false)
	private boolean unsupportedParams;

	@Column(name = "provider_ref")
	private String providerRef;

	@Column(name = "rejected_reason")
	private String rejectedReason;

	@Column(name = "synced_at", nullable = false)
	private Instant syncedAt;

	protected WhatsAppTemplate() {
		// JPA
	}

	public WhatsAppTemplate(UUID tenantId, String name, String language, String category, String status,
			int bodyParamCount, boolean unsupportedParams, String providerRef, String rejectedReason,
			Instant syncedAt) {
		this.id = UUID.randomUUID();
		this.tenantId = tenantId;
		this.name = name;
		this.language = language;
		this.category = category;
		this.status = status;
		this.bodyParamCount = bodyParamCount;
		this.unsupportedParams = unsupportedParams;
		this.providerRef = providerRef;
		this.rejectedReason = rejectedReason;
		this.syncedAt = syncedAt;
	}

	public void refresh(String category, String status, int bodyParamCount, boolean unsupportedParams,
			String providerRef, String rejectedReason, Instant syncedAt) {
		this.category = category;
		this.status = status;
		this.bodyParamCount = bodyParamCount;
		this.unsupportedParams = unsupportedParams;
		this.providerRef = providerRef;
		this.rejectedReason = rejectedReason;
		this.syncedAt = syncedAt;
	}

	/** Approved is necessary but not sufficient: a template Herald cannot fill
	 * completely would be rejected on delivery, not at submission. */
	public boolean isSendable() {
		return APPROVED.equals(status) && !unsupportedParams;
	}

	public boolean hasUnsupportedParams() {
		return unsupportedParams;
	}

	public UUID getId() {
		return id;
	}

	public UUID getTenantId() {
		return tenantId;
	}

	public String getName() {
		return name;
	}

	public String getLanguage() {
		return language;
	}

	public String getCategory() {
		return category;
	}

	public String getStatus() {
		return status;
	}

	public int getBodyParamCount() {
		return bodyParamCount;
	}

	public String getProviderRef() {
		return providerRef;
	}

	public String getRejectedReason() {
		return rejectedReason;
	}

	public Instant getSyncedAt() {
		return syncedAt;
	}
}
