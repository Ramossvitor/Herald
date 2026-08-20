-- WhatsApp is bring-your-own: the tenant owns the number, the WhatsApp Business
-- Account and the templates, and Herald dispatches under them.
--
-- That is why there is no sender_identities row for this channel. Email needs
-- one because a tenant may send from many addresses and each has to be checked
-- against what it actually verified; a tenant here has exactly one number, the
-- one in this table, so the credential row *is* the identity and a second
-- record of it could only drift.

CREATE TABLE tenant_whatsapp_settings (
    tenant_id                  UUID PRIMARY KEY REFERENCES tenants(id),
    -- What Meta calls the sending number, and what goes into messages.sender.
    phone_number_id            TEXT NOT NULL,
    -- The account the number belongs to. Templates hang off it, and inbound
    -- webhooks name it — it is how a delivery receipt finds its tenant.
    waba_id                    TEXT NOT NULL,
    -- Both AES-GCM ciphertext (see Secrets), never plaintext. The token is
    -- presented to Meta on every send; the app secret verifies the HMAC on
    -- every webhook. Both have to come back out, so neither can be hashed.
    access_token               TEXT NOT NULL,
    app_secret                 TEXT NOT NULL,
    -- Credentials are taken on trust until a call to Meta proves them, the way
    -- a sender domain is PENDING until DNS is read.
    status                     TEXT NOT NULL DEFAULT 'PENDING'
                               CHECK (status IN ('PENDING', 'VERIFIED', 'FAILED')),
    last_error                 TEXT,
    -- Per channel, because the cost per message is an order of magnitude apart
    -- from email's. Here the daily limit is a spending cap, not a spam guard.
    daily_limit                INT  NOT NULL DEFAULT 90,
    -- Zero by default: on this channel the traffic is transactional, and a
    -- second verification code a minute later is the user asking for it, not
    -- abuse. Operators who want a gap set one.
    recipient_cooldown_seconds INT  NOT NULL DEFAULT 0,
    templates_synced_at        TIMESTAMPTZ,
    created_at                 TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                 TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- One WABA, one tenant. The webhook carries a WABA id and nothing else that
-- identifies the sender, so this uniqueness is what makes routing an inbound
-- payload to the right tenant — and to the right app secret — unambiguous.
CREATE UNIQUE INDEX idx_whatsapp_waba ON tenant_whatsapp_settings (waba_id);

-- A local mirror of what Meta approved, never the source of truth. Herald reads
-- it to refuse a send that would fail anyway, so the caller learns at
-- submission instead of finding a FAILED row minutes later.
CREATE TABLE whatsapp_templates (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        UUID NOT NULL REFERENCES tenants(id),
    name             TEXT NOT NULL,
    language         TEXT NOT NULL,
    -- MARKETING costs roughly 8x UTILITY in Brazil, and Meta may reclassify a
    -- template after approving it. Storing the category is what lets an
    -- operator notice that happening.
    category         TEXT NOT NULL,
    status           TEXT NOT NULL,
    -- How many {{n}} placeholders the body carries: an arity mismatch is the
    -- one template error Herald can catch before spending a send.
    body_param_count INT  NOT NULL DEFAULT 0,
    -- The template wants a value somewhere Herald never sends one — a header, a
    -- dynamic button URL. Approved, and still undeliverable, so submission has
    -- to refuse it rather than let Meta do it after an attempt is spent.
    unsupported_params BOOLEAN NOT NULL DEFAULT false,
    provider_ref     TEXT,
    rejected_reason  TEXT,
    synced_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Meta keys a template by name and language; the same name in pt_BR and
    -- en_US are two templates, approved separately.
    UNIQUE (tenant_id, name, language)
);

-- No separate index on tenant_id: the UNIQUE above already leaves a b-tree with
-- tenant_id leftmost, which serves every tenant-scoped read of this table.

-- Delivery receipts arrive keyed by the provider's own id, not Herald's. On
-- this channel that lookup is the difference between "Meta accepted it" and
-- "it reached the handset", so it runs on every failure notification.
CREATE INDEX idx_messages_provider_ref ON messages (tenant_id, channel, provider_message_id)
    WHERE provider_message_id IS NOT NULL;
