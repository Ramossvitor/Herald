package io.github.ramossvitor.herald.webhook;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One delivery of one webhook, recorded so the next delivery of the same one
 * does nothing. The provider retries until it gets a 2xx, so duplicates are
 * routine rather than exceptional — and without this a redelivered bounce would
 * be indistinguishable from a second bounce.
 */
@Entity
@Table(name = "webhook_events")
public class WebhookEvent {

	/** The provider's per-delivery id, from the {@code svix-id} header. */
	@Id
	@Column(name = "svix_id")
	private String svixId;

	@Column(name = "event_type", nullable = false)
	private String eventType;

	@Column(name = "received_at", nullable = false)
	private Instant receivedAt;

	protected WebhookEvent() {
		// JPA
	}

	public WebhookEvent(String svixId, String eventType, Instant receivedAt) {
		this.svixId = svixId;
		this.eventType = eventType;
		this.receivedAt = receivedAt;
	}

	public String getSvixId() {
		return svixId;
	}

	public String getEventType() {
		return eventType;
	}

	public Instant getReceivedAt() {
		return receivedAt;
	}
}
