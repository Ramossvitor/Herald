-- One row per (tenant, channel, identity the tenant may send as). Email only
-- today; the same table takes WHATSAPP (identifier = phone number id) and SMS
-- later — widen the CHECKs when those channels actually land.

CREATE TABLE sender_identities (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      UUID NOT NULL REFERENCES tenants(id),
    channel        TEXT NOT NULL CHECK (channel IN ('EMAIL')),
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
    UNIQUE (tenant_id, channel, identifier)
);

-- A self-service custom domain belongs to exactly one tenant across the whole
-- system. Operator-trusted rows (provider_ref NULL) are exempt: the operator
-- may legitimately point several tenants at domains they control.
CREATE UNIQUE INDEX idx_sender_custom_domain ON sender_identities (channel, identifier)
    WHERE kind = 'EMAIL_CUSTOM_DOMAIN' AND provider_ref IS NOT NULL;

-- The verifier's poll: only rows awaiting a check.
CREATE INDEX idx_sender_check_due ON sender_identities (next_check_at)
    WHERE status = 'VERIFYING';

-- Existing tenants had their from_address domain verified by hand in the
-- provider dashboard; grandfather those domains as operator-trusted.
INSERT INTO sender_identities (tenant_id, channel, kind, identifier, status, verified_at)
SELECT tenant_id, 'EMAIL', 'EMAIL_CUSTOM_DOMAIN',
       lower(split_part(regexp_replace(from_address, '^.*<|>\s*$', '', 'g'), '@', 2)),
       'VERIFIED', now()
FROM tenant_email_settings
WHERE position('@' in from_address) > 0
ON CONFLICT DO NOTHING;
