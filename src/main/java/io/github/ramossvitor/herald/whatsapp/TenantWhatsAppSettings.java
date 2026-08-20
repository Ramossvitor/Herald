package io.github.ramossvitor.herald.whatsapp;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One tenant's own WhatsApp Business account, as Herald needs to see it.
 *
 * {@code accessToken} and {@code appSecret} hold ciphertext. They are never
 * returned by any endpoint and never logged — the admin API answers with the
 * ids and the status, never with what it was given.
 */
@Entity
@Table(name = "tenant_whatsapp_settings")
public class TenantWhatsAppSettings {

	@Id
	@Column(name = "tenant_id")
	private UUID tenantId;

	@Column(name = "phone_number_id", nullable = false)
	private String phoneNumberId;

	@Column(name = "waba_id", nullable = false)
	private String wabaId;

	@Column(name = "access_token", nullable = false)
	private String accessToken;

	@Column(name = "app_secret", nullable = false)
	private String appSecret;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private WhatsAppSettingsStatus status;

	@Column(name = "last_error")
	private String lastError;

	@Column(name = "daily_limit", nullable = false)
	private int dailyLimit;

	@Column(name = "recipient_cooldown_seconds", nullable = false)
	private int recipientCooldownSeconds;

	@Column(name = "templates_synced_at")
	private Instant templatesSyncedAt;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	/**
	 * What each stored credential is encrypted as, so that {@code Secrets} can
	 * refuse one that turns up in the wrong row or the wrong column. Taken on the
	 * tenant id rather than the entity, because registration encrypts before
	 * there is an entity to ask.
	 */
	public static String accessTokenContext(UUID tenantId) {
		return tenantId + ":access_token";
	}

	public static String appSecretContext(UUID tenantId) {
		return tenantId + ":app_secret";
	}

	protected TenantWhatsAppSettings() {
		// JPA
	}

	public TenantWhatsAppSettings(UUID tenantId, String phoneNumberId, String wabaId, String encryptedAccessToken,
			String encryptedAppSecret, int dailyLimit, int recipientCooldownSeconds, Instant now) {
		this.tenantId = tenantId;
		this.phoneNumberId = phoneNumberId;
		this.wabaId = wabaId;
		this.accessToken = encryptedAccessToken;
		this.appSecret = encryptedAppSecret;
		this.status = WhatsAppSettingsStatus.PENDING;
		this.dailyLimit = dailyLimit;
		this.recipientCooldownSeconds = recipientCooldownSeconds;
		this.createdAt = now;
		this.updatedAt = now;
	}

	/** New credentials are unproven again, whatever the old ones were. */
	public void replaceCredentials(String phoneNumberId, String wabaId, String encryptedAccessToken,
			String encryptedAppSecret, Instant now) {
		this.phoneNumberId = phoneNumberId;
		this.wabaId = wabaId;
		this.accessToken = encryptedAccessToken;
		this.appSecret = encryptedAppSecret;
		this.status = WhatsAppSettingsStatus.PENDING;
		this.lastError = null;
		this.updatedAt = now;
	}

	public void updateLimits(int dailyLimit, int recipientCooldownSeconds, Instant now) {
		this.dailyLimit = dailyLimit;
		this.recipientCooldownSeconds = recipientCooldownSeconds;
		this.updatedAt = now;
	}

	public void markVerified(Instant now) {
		this.status = WhatsAppSettingsStatus.VERIFIED;
		this.lastError = null;
		this.updatedAt = now;
	}

	public void markFailed(String error, Instant now) {
		this.status = WhatsAppSettingsStatus.FAILED;
		this.lastError = error;
		this.updatedAt = now;
	}

	public void markTemplatesSynced(Instant now) {
		this.templatesSyncedAt = now;
		this.updatedAt = now;
	}

	public UUID getTenantId() {
		return tenantId;
	}

	public String getPhoneNumberId() {
		return phoneNumberId;
	}

	public String getWabaId() {
		return wabaId;
	}

	/** Ciphertext. Decrypt through {@code Secrets}, never log the result. */
	public String getAccessToken() {
		return accessToken;
	}

	/** Ciphertext. Decrypt through {@code Secrets}, never log the result. */
	public String getAppSecret() {
		return appSecret;
	}

	public WhatsAppSettingsStatus getStatus() {
		return status;
	}

	public String getLastError() {
		return lastError;
	}

	public int getDailyLimit() {
		return dailyLimit;
	}

	public int getRecipientCooldownSeconds() {
		return recipientCooldownSeconds;
	}

	public Instant getTemplatesSyncedAt() {
		return templatesSyncedAt;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}
}
