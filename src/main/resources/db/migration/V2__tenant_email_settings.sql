-- Settings are per channel: a future push channel gets its own
-- tenant_push_settings table (VAPID key pair etc.) without touching this one.

CREATE TABLE tenant_email_settings (
    tenant_id                  UUID PRIMARY KEY REFERENCES tenants(id),
    from_address               TEXT NOT NULL,
    daily_limit                INT  NOT NULL DEFAULT 90,
    recipient_cooldown_seconds INT  NOT NULL DEFAULT 600
);

-- Generic per-key daily caps. A policy with key_prefix 'inviter' matches every
-- request that carries a limit key like 'inviter:123', each distinct key
-- counted against daily_cap independently. Channel-agnostic by design.
CREATE TABLE tenant_limit_policies (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id  UUID NOT NULL REFERENCES tenants(id),
    key_prefix TEXT NOT NULL,
    daily_cap  INT  NOT NULL,
    UNIQUE (tenant_id, key_prefix)
);
