package io.github.ramossvitor.herald.email;

public class QuotaExceededException extends RuntimeException {

	private final QuotaReason reason;
	private final Long retryAfterSeconds;
	private final String limitKey;

	private QuotaExceededException(QuotaReason reason, Long retryAfterSeconds, String limitKey) {
		super(reason.wireName());
		this.reason = reason;
		this.retryAfterSeconds = retryAfterSeconds;
		this.limitKey = limitKey;
	}

	public static QuotaExceededException recipientCooldown(long retryAfterSeconds) {
		return new QuotaExceededException(QuotaReason.RECIPIENT_COOLDOWN, retryAfterSeconds, null);
	}

	public static QuotaExceededException limitKeyExceeded(String limitKey) {
		return new QuotaExceededException(QuotaReason.LIMIT_KEY_EXCEEDED, null, limitKey);
	}

	public static QuotaExceededException tenantDailyLimit() {
		return new QuotaExceededException(QuotaReason.TENANT_DAILY_LIMIT, null, null);
	}

	public QuotaReason reason() {
		return reason;
	}

	public Long retryAfterSeconds() {
		return retryAfterSeconds;
	}

	public String limitKey() {
		return limitKey;
	}
}
