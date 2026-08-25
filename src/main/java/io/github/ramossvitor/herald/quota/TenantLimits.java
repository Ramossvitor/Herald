package io.github.ramossvitor.herald.quota;

import java.util.UUID;

/**
 * What the quota gate needs to know about one tenant, decoupled from where
 * those numbers are stored.
 *
 * @param recipientCooldownSeconds zero or less disables the cooldown
 */
public record TenantLimits(UUID tenantId, int dailyLimit, int recipientCooldownSeconds) {
}
