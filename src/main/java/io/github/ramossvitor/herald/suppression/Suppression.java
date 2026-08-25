package io.github.ramossvitor.herald.suppression;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One address this tenant must stop mailing. Keyed by the canonical recipient
 * form, so a suppression earned by {@code a.b+tag@gmail.com} also covers
 * {@code ab@gmail.com} — they are one mailbox.
 *
 * Read and deleted through JPA, but never written through it: rows are inserted
 * only by {@link SuppressionRepository#insertIfAbsent}, which leaves the
 * "already suppressed" decision to the unique constraint instead of to a read
 * that a concurrent webhook can invalidate. Hence no public constructor — a
 * {@code save} of a new instance is exactly the racy path that method replaced.
 */
@Entity
@Table(name = "suppressions")
public class Suppression {

	@Id
	private UUID id;

	@Column(name = "tenant_id", nullable = false)
	private UUID tenantId;

	@Column(name = "recipient_canonical", nullable = false)
	private String recipientCanonical;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private SuppressionReason reason;

	/** The provider's explanation, kept for the human who decides to lift this. */
	@Column
	private String detail;

	@Column(name = "source_message_id")
	private UUID sourceMessageId;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	protected Suppression() {
		// JPA
	}

	public UUID getId() {
		return id;
	}

	public UUID getTenantId() {
		return tenantId;
	}

	public String getRecipientCanonical() {
		return recipientCanonical;
	}

	public SuppressionReason getReason() {
		return reason;
	}

	public String getDetail() {
		return detail;
	}

	public UUID getSourceMessageId() {
		return sourceMessageId;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}
}
