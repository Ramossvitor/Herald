package io.github.ramossvitor.herald.sender;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * An identity a tenant is allowed to send as. Verification state lives here
 * because it is the provider's answer about DNS, not something Herald decides.
 */
@Entity
@Table(name = "sender_identities")
public class SenderIdentity {

	@Id
	private UUID id;

	@Column(name = "tenant_id", nullable = false)
	private UUID tenantId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private Channel channel;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private SenderIdentityKind kind;

	@Column(nullable = false)
	private String identifier;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private SenderIdentityStatus status;

	@Column(name = "provider_ref")
	private String providerRef;

	@Column(name = "dns_records")
	private String dnsRecords;

	@Column(name = "last_error")
	private String lastError;

	@Column(name = "check_attempts", nullable = false)
	private int checkAttempts;

	@Column(name = "next_check_at")
	private Instant nextCheckAt;

	@Column(name = "verified_at")
	private Instant verifiedAt;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected SenderIdentity() {
		// JPA
	}

	public SenderIdentity(UUID tenantId, Channel channel, SenderIdentityKind kind, String identifier,
			Instant createdAt) {
		this.id = UUID.randomUUID();
		this.tenantId = tenantId;
		this.channel = channel;
		this.kind = kind;
		this.identifier = identifier;
		this.status = SenderIdentityStatus.PENDING;
		this.checkAttempts = 0;
		this.createdAt = createdAt;
		this.updatedAt = createdAt;
	}

	/** Operator-trusted identities skip provider verification entirely. */
	public static SenderIdentity trusted(UUID tenantId, Channel channel, SenderIdentityKind kind, String identifier,
			Instant now) {
		SenderIdentity identity = new SenderIdentity(tenantId, channel, kind, identifier, now);
		identity.status = SenderIdentityStatus.VERIFIED;
		identity.verifiedAt = now;
		return identity;
	}

	public void recordProviderRegistration(String providerRef, String dnsRecords, Instant now) {
		this.providerRef = providerRef;
		this.dnsRecords = dnsRecords;
		this.updatedAt = now;
	}

	public void startVerifying(Instant nextCheckAt, Instant now) {
		this.status = SenderIdentityStatus.VERIFYING;
		this.checkAttempts = 0;
		this.nextCheckAt = nextCheckAt;
		this.lastError = null;
		this.updatedAt = now;
	}

	public void recordCheck(Instant nextCheckAt, Instant now) {
		this.checkAttempts++;
		this.nextCheckAt = nextCheckAt;
		this.updatedAt = now;
	}

	public void markVerified(Instant now) {
		this.status = SenderIdentityStatus.VERIFIED;
		this.verifiedAt = now;
		this.nextCheckAt = null;
		this.lastError = null;
		this.updatedAt = now;
	}

	public void markFailed(String error, Instant now) {
		this.status = SenderIdentityStatus.FAILED;
		this.lastError = error;
		this.nextCheckAt = null;
		this.updatedAt = now;
	}

	public UUID getId() {
		return id;
	}

	public UUID getTenantId() {
		return tenantId;
	}

	public Channel getChannel() {
		return channel;
	}

	public SenderIdentityKind getKind() {
		return kind;
	}

	public String getIdentifier() {
		return identifier;
	}

	public SenderIdentityStatus getStatus() {
		return status;
	}

	public String getProviderRef() {
		return providerRef;
	}

	public String getDnsRecords() {
		return dnsRecords;
	}

	public String getLastError() {
		return lastError;
	}

	public int getCheckAttempts() {
		return checkAttempts;
	}

	public Instant getNextCheckAt() {
		return nextCheckAt;
	}

	public Instant getVerifiedAt() {
		return verifiedAt;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}
}
