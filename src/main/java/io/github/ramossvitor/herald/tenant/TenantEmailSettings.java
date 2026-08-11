package io.github.ramossvitor.herald.tenant;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "tenant_email_settings")
public class TenantEmailSettings {

	@Id
	@Column(name = "tenant_id")
	private UUID tenantId;

	@Column(name = "from_address", nullable = false)
	private String fromAddress;

	@Column(name = "daily_limit", nullable = false)
	private int dailyLimit;

	@Column(name = "recipient_cooldown_seconds", nullable = false)
	private int recipientCooldownSeconds;

	protected TenantEmailSettings() {
		// JPA
	}

	public TenantEmailSettings(UUID tenantId, String fromAddress, int dailyLimit, int recipientCooldownSeconds) {
		this.tenantId = tenantId;
		this.fromAddress = fromAddress;
		this.dailyLimit = dailyLimit;
		this.recipientCooldownSeconds = recipientCooldownSeconds;
	}

	public void update(String fromAddress, int dailyLimit, int recipientCooldownSeconds) {
		this.fromAddress = fromAddress;
		this.dailyLimit = dailyLimit;
		this.recipientCooldownSeconds = recipientCooldownSeconds;
	}

	public UUID getTenantId() {
		return tenantId;
	}

	public String getFromAddress() {
		return fromAddress;
	}

	public int getDailyLimit() {
		return dailyLimit;
	}

	public int getRecipientCooldownSeconds() {
		return recipientCooldownSeconds;
	}
}
