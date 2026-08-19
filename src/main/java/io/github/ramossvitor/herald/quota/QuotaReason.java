package io.github.ramossvitor.herald.quota;

/**
 * The check order is part of the API contract: when a request trips more than
 * one limit, the reason reported is the first in this order.
 */
public enum QuotaReason {

	RECIPIENT_COOLDOWN("recipient-cooldown"),
	LIMIT_KEY_EXCEEDED("limit-key-exceeded"),
	TENANT_DAILY_LIMIT("tenant-daily-limit");

	private final String wireName;

	QuotaReason(String wireName) {
		this.wireName = wireName;
	}

	public String wireName() {
		return wireName;
	}
}
