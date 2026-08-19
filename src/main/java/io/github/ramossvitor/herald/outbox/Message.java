package io.github.ramossvitor.herald.outbox;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import io.github.ramossvitor.herald.sender.Channel;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One queued delivery, on any channel. The columns are the dispatch loop's
 * vocabulary; whatever only one channel understands rides in {@link #payload}.
 */
@Entity
@Table(name = "messages")
public class Message {

	@Id
	private UUID id;

	@Column(name = "tenant_id", nullable = false)
	private UUID tenantId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private Channel channel;

	@Column(name = "idempotency_key")
	private String idempotencyKey;

	@Column(nullable = false)
	private String recipient;

	/** Quota windows count destinations, not spellings. */
	@Column(name = "recipient_canonical", nullable = false)
	private String recipientCanonical;

	/** Snapshot: editing tenant settings must not change queued messages. */
	@Column(nullable = false)
	private String sender;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(nullable = false, columnDefinition = "jsonb")
	private Map<String, Object> payload;

	@JdbcTypeCode(SqlTypes.ARRAY)
	@Column(name = "limit_keys", columnDefinition = "text[]", nullable = false)
	private List<String> limitKeys;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private MessageStatus status;

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

	protected Message() {
		// JPA
	}

	public Message(UUID tenantId, Channel channel, String idempotencyKey, String recipient, String recipientCanonical,
			String sender, Map<String, Object> payload, List<String> limitKeys, Instant createdAt) {
		this.id = UUID.randomUUID();
		this.tenantId = tenantId;
		this.channel = channel;
		this.idempotencyKey = idempotencyKey;
		this.recipient = recipient;
		this.recipientCanonical = recipientCanonical;
		this.sender = sender;
		this.payload = Map.copyOf(payload);
		this.limitKeys = List.copyOf(limitKeys);
		this.status = MessageStatus.PENDING;
		this.attemptCount = 0;
		this.nextAttemptAt = createdAt;
		this.createdAt = createdAt;
		this.updatedAt = createdAt;
	}

	public void markSending(Instant now) {
		this.status = MessageStatus.SENDING;
		this.updatedAt = now;
	}

	public void recordAttempt() {
		this.attemptCount++;
	}

	public void recordSuccess(String providerMessageId, Instant now) {
		this.status = MessageStatus.SENT;
		this.providerMessageId = providerMessageId;
		this.lastError = null;
		this.sentAt = now;
		this.updatedAt = now;
	}

	public void recordFailure(String error, Instant now) {
		this.status = MessageStatus.FAILED;
		this.lastError = error;
		this.updatedAt = now;
	}

	public void scheduleRetry(String error, Instant nextAttemptAt, Instant now) {
		this.status = MessageStatus.PENDING;
		this.lastError = error;
		this.nextAttemptAt = nextAttemptAt;
		this.updatedAt = now;
	}

	/** Null when the key is absent or holds something other than a string. */
	public String payloadText(String key) {
		Object value = payload.get(key);
		return value instanceof String text ? text : null;
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

	public String getIdempotencyKey() {
		return idempotencyKey;
	}

	public String getRecipient() {
		return recipient;
	}

	public String getRecipientCanonical() {
		return recipientCanonical;
	}

	public String getSender() {
		return sender;
	}

	public List<String> getLimitKeys() {
		return limitKeys;
	}

	public MessageStatus getStatus() {
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
