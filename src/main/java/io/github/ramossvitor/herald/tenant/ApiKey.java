package io.github.ramossvitor.herald.tenant;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * Only the SHA-256 of the key is stored; the plaintext exists once, in the
 * response of the admin call that issued it.
 */
@Entity
@Table(name = "api_keys")
public class ApiKey {

	@Id
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "tenant_id")
	private Tenant tenant;

	@Column(name = "key_hash", nullable = false, unique = true)
	private String keyHash;

	@Column(name = "key_prefix", nullable = false)
	private String keyPrefix;

	private String label;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "last_used_at")
	private Instant lastUsedAt;

	@Column(name = "revoked_at")
	private Instant revokedAt;

	protected ApiKey() {
		// JPA
	}

	public ApiKey(Tenant tenant, String keyHash, String keyPrefix, String label, Instant createdAt) {
		this.id = UUID.randomUUID();
		this.tenant = tenant;
		this.keyHash = keyHash;
		this.keyPrefix = keyPrefix;
		this.label = label;
		this.createdAt = createdAt;
	}

	public void revoke(Instant when) {
		this.revokedAt = when;
	}

	public UUID getId() {
		return id;
	}

	public Tenant getTenant() {
		return tenant;
	}

	public String getKeyPrefix() {
		return keyPrefix;
	}

	public String getLabel() {
		return label;
	}

	public Instant getLastUsedAt() {
		return lastUsedAt;
	}

	public Instant getRevokedAt() {
		return revokedAt;
	}
}
