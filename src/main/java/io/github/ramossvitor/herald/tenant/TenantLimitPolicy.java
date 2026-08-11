package io.github.ramossvitor.herald.tenant;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A per-tenant daily cap over limit keys sharing a prefix: a policy for
 * {@code inviter} caps each distinct {@code inviter:<id>} key independently.
 */
@Entity
@Table(name = "tenant_limit_policies")
public class TenantLimitPolicy {

	@Id
	private UUID id;

	@Column(name = "tenant_id", nullable = false)
	private UUID tenantId;

	@Column(name = "key_prefix", nullable = false)
	private String keyPrefix;

	@Column(name = "daily_cap", nullable = false)
	private int dailyCap;

	protected TenantLimitPolicy() {
		// JPA
	}

	public TenantLimitPolicy(UUID tenantId, String keyPrefix, int dailyCap) {
		this.id = UUID.randomUUID();
		this.tenantId = tenantId;
		this.keyPrefix = keyPrefix;
		this.dailyCap = dailyCap;
	}

	public UUID getTenantId() {
		return tenantId;
	}

	public String getKeyPrefix() {
		return keyPrefix;
	}

	public int getDailyCap() {
		return dailyCap;
	}
}
