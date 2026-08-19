package io.github.ramossvitor.herald.quota;

import java.util.UUID;

import io.github.ramossvitor.herald.sender.Channel;

/**
 * What the quota gate needs to know about one tenant on one channel, decoupled
 * from where those numbers are stored. Budgets are per channel because the cost
 * per message differs by an order of magnitude between them — an email daily
 * limit is a spam guard, a WhatsApp one is a spending cap.
 *
 * @param recipientCooldownSeconds zero or less disables the cooldown
 */
public record ChannelLimits(UUID tenantId, Channel channel, int dailyLimit, int recipientCooldownSeconds) {
}
