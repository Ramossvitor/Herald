package io.github.ramossvitor.herald.security;

import java.util.UUID;

public record TenantPrincipal(UUID tenantId, String slug) {
}
