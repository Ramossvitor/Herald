-- One row per (tenant, identity the tenant may send as).

CREATE TABLE sender_identities (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      UUID NOT NULL REFERENCES tenants(id),
    -- The EMAIL_ prefix is kept: these values go out on the wire as
    -- SenderIdentityResponse.kind, so dropping it would break clients for
    -- nothing.
    kind           TEXT NOT NULL CHECK (kind IN ('EMAIL_SHARED_ADDRESS', 'EMAIL_CUSTOM_DOMAIN')),
    -- EMAIL_SHARED_ADDRESS: full address (slug@send.root), lowercase.
    -- EMAIL_CUSTOM_DOMAIN:  bare domain (acme.com), lowercase.
    identifier     TEXT NOT NULL,
    status         TEXT NOT NULL DEFAULT 'PENDING'
                   CHECK (status IN ('PENDING', 'VERIFYING', 'VERIFIED', 'FAILED')),
    -- Resend domain id. NULL means operator-trusted: the operator verified the
    -- domain in the provider dashboard by hand and Herald takes their word.
    provider_ref   TEXT,
    -- The provider's DNS "records" array, verbatim JSON. TEXT on purpose: it is
    -- returned to the caller untouched and never queried.
    dns_records    TEXT,
    last_error     TEXT,
    check_attempts INT NOT NULL DEFAULT 0,
    next_check_at  TIMESTAMPTZ,
    verified_at    TIMESTAMPTZ,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, identifier)
);

-- A self-service custom domain belongs to exactly one tenant across the whole
-- system. Operator-trusted rows (provider_ref NULL) are exempt: the operator
-- may legitimately point several tenants at domains they control.
CREATE UNIQUE INDEX idx_sender_custom_domain ON sender_identities (identifier)
    WHERE kind = 'EMAIL_CUSTOM_DOMAIN' AND provider_ref IS NOT NULL;

-- The verifier's poll: only rows awaiting a check.
CREATE INDEX idx_sender_check_due ON sender_identities (next_check_at)
    WHERE status = 'VERIFYING';
