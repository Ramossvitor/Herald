package io.github.ramossvitor.herald.email;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "email_messages")
public class EmailMessage {

	@Id
	private UUID id;

	@Column(name = "tenant_id", nullable = false)
	private UUID tenantId;

	@Column(name = "idempotency_key")
	private String idempotencyKey;

	@Column(nullable = false)
	private String recipient;

	@Column(name = "recipient_canonical", nullable = false)
	private String recipientCanonical;

	/** Snapshot: editing tenant settings must not change queued messages. */
	@Column(name = "from_address", nullable = false)
	private String fromAddress;

	@Column(nullable = false)
	private String subject;

	@Column(name = "html_body", nullable = false)
	private String htmlBody;

	@Column(name = "text_body", nullable = false)
	private String textBody;

	@Column(name = "reply_to")
	private String replyTo;

	@JdbcTypeCode(SqlTypes.ARRAY)
	@Column(name = "limit_keys", columnDefinition = "text[]", nullable = false)
	private List<String> limitKeys;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private EmailStatus status;

	@Column(name = "attempt_count", nullable = false)
	private int attemptCount;

	@Column(name = "next_attempt_at", nullable = false)
	private Instant nextAttemptAt;

	@Column(name = "provider_message_id")
	private String providerMessageId;

	@Column(name = "last_error")
	private String lastError;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	@Column(name = "sent_at")
	private Instant sentAt;

	protected EmailMessage() {
		// JPA
	}

	public EmailMessage(UUID tenantId, String idempotencyKey, String recipient, String recipientCanonical,
			String fromAddress, String subject, String htmlBody, String textBody, String replyTo,
			List<String> limitKeys, Instant createdAt) {
		this.id = UUID.randomUUID();
		this.tenantId = tenantId;
		this.idempotencyKey = idempotencyKey;
		this.recipient = recipient;
		this.recipientCanonical = recipientCanonical;
		this.fromAddress = fromAddress;
		this.subject = subject;
		this.htmlBody = htmlBody;
		this.textBody = textBody;
		this.replyTo = replyTo;
		this.limitKeys = List.copyOf(limitKeys);
		this.status = EmailStatus.PENDING;
		this.attemptCount = 0;
		this.nextAttemptAt = createdAt;
		this.createdAt = createdAt;
		this.updatedAt = createdAt;
	}

	public void markSending(Instant now) {
		this.status = EmailStatus.SENDING;
		this.updatedAt = now;
	}

	public void recordAttempt() {
		this.attemptCount++;
	}

	public void recordSuccess(String providerMessageId, Instant now) {
		this.status = EmailStatus.SENT;
		this.providerMessageId = providerMessageId;
		this.lastError = null;
		this.sentAt = now;
		this.updatedAt = now;
	}

	public void recordFailure(String error, Instant now) {
		this.status = EmailStatus.FAILED;
		this.lastError = error;
		this.updatedAt = now;
	}

	public void scheduleRetry(String error, Instant nextAttemptAt, Instant now) {
		this.status = EmailStatus.PENDING;
		this.lastError = error;
		this.nextAttemptAt = nextAttemptAt;
		this.updatedAt = now;
	}

	public UUID getId() {
		return id;
	}

	public UUID getTenantId() {
		return tenantId;
	}

	public String getIdempotencyKey() {
		return idempotencyKey;
	}

	public String getRecipient() {
		return recipient;
	}

	public String getRecipientCanonical() {
		return recipientCanonical;
	}

	public String getFromAddress() {
		return fromAddress;
	}

	public String getSubject() {
		return subject;
	}

	public String getHtmlBody() {
		return htmlBody;
	}

	public String getTextBody() {
		return textBody;
	}

	public String getReplyTo() {
		return replyTo;
	}

	public List<String> getLimitKeys() {
		return limitKeys;
	}

	public EmailStatus getStatus() {
		return status;
	}

	public int getAttemptCount() {
		return attemptCount;
	}

	public Instant getNextAttemptAt() {
		return nextAttemptAt;
	}

	public String getProviderMessageId() {
		return providerMessageId;
	}

	public String getLastError() {
		return lastError;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getSentAt() {
		return sentAt;
	}
}
