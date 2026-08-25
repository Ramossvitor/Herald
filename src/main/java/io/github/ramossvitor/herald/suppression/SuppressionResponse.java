package io.github.ramossvitor.herald.suppression;

import java.time.Instant;
import java.util.UUID;

public record SuppressionResponse(String recipient, String reason, String detail, UUID sourceMessageId,
		Instant createdAt) {

	public static SuppressionResponse from(Suppression suppression) {
		return new SuppressionResponse(suppression.getRecipientCanonical(), suppression.getReason().wireName(),
				suppression.getDetail(), suppression.getSourceMessageId(), suppression.getCreatedAt());
	}
}
