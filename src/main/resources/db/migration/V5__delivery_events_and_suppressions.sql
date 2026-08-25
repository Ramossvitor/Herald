-- What the provider tells us *after* it accepted a message.
--
-- These columns are deliberately not `status`. That one is the outbox's own
-- state machine — the column OutboxStore.claimDueBatch reads to decide what to
-- dispatch. A webhook writing to it would put an already-sent message back in
-- the queue. Delivery state is a parallel, read-only-to-the-worker fact.
ALTER TABLE messages
    ADD COLUMN delivery_state TEXT
        CHECK (delivery_state IN ('DELIVERED', 'BOUNCED', 'COMPLAINED', 'DELAYED', 'FAILED_AT_PROVIDER')),
    ADD COLUMN delivery_detail TEXT,
    ADD COLUMN delivery_updated_at TIMESTAMPTZ;

-- Every webhook matches its message by the provider's id. Without an index
-- that is a sequential scan over the largest table in the schema, once per
-- event — and events arrive at roughly the rate mail is sent.
--
-- UNIQUE because MessageRepository.findByProviderMessageId returns an Optional,
-- which is an assertion that at most one row can match. A second row would make
-- that lookup throw, and the 500 it turns into would have the provider redeliver
-- the same event until it gave up. Nothing should ever produce a duplicate — a
-- crash-retry reuses the message id as the provider's idempotency key and so
-- gets the same provider id back on the same row — and this is what keeps
-- "should" out of it.
CREATE UNIQUE INDEX idx_messages_provider_id ON messages (provider_message_id)
    WHERE provider_message_id IS NOT NULL;

-- Addresses that must not be mailed again. Scoped per tenant on purpose: each
-- tenant sends from its own domain with its own reputation, so a bounce for
-- one is not evidence about another. The key is recipient_canonical — the same
-- form the recipient cooldown counts by, so Gmail dot/+tag spellings collapse
-- into one entry for free.
CREATE TABLE suppressions (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID NOT NULL REFERENCES tenants(id),
    recipient_canonical TEXT NOT NULL,
    reason              TEXT NOT NULL CHECK (reason IN ('BOUNCED', 'COMPLAINED')),
    -- The provider's own words about why. Read by a human deciding whether to
    -- lift the suppression, never matched on.
    detail              TEXT,
    -- The message that caused it, for tracing back. No FK action: purging old
    -- messages must not silently drop suppressions.
    source_message_id   UUID,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, recipient_canonical)
);

-- The submission path asks "is this recipient suppressed" on every accepted
-- email; the unique constraint above already indexes exactly that lookup.

-- Delivered-at-least-once is the webhook contract too: the provider retries
-- until it gets a 2xx, so the same event arrives more than once. svix_id is
-- the provider's per-delivery id — inserting it first makes replays, malicious
-- or otherwise, a no-op instead of a second suppression.
CREATE TABLE webhook_events (
    svix_id     TEXT PRIMARY KEY,
    event_type  TEXT NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- This table grows without bound and nothing reads rows older than the
-- provider's retry window. Purge it alongside the messages retention policy:
--   DELETE FROM webhook_events WHERE received_at < now() - interval '30 days';
CREATE INDEX idx_webhook_events_received ON webhook_events (received_at);
