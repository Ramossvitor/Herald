-- The outbox stops being email-shaped. Everything the dispatch loop reads --
-- status, attempt_count, next_attempt_at -- is the same whatever the channel,
-- so it stays in columns; everything only one channel understands moves into
-- payload. A new channel is then a provider and a payload shape, not a second
-- copy of the claim/retry/recover machinery.

CREATE TABLE messages (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID NOT NULL REFERENCES tenants(id),
    channel             TEXT NOT NULL CHECK (channel IN ('EMAIL', 'WHATSAPP')),
    idempotency_key     TEXT,
    recipient           TEXT NOT NULL,
    -- EMAIL: lowercased, Gmail dots/+tags stripped. WHATSAPP: E.164.
    -- Quota windows count destinations, not spellings, on every channel.
    recipient_canonical TEXT NOT NULL,
    -- Snapshot of the identity taken at submission: EMAIL a from address,
    -- WHATSAPP a phone number id. Editing tenant settings must not change
    -- messages already queued.
    sender              TEXT NOT NULL,
    -- EMAIL: {subject, html, text, replyTo}. WHATSAPP: {template, params}.
    -- The NOT NULLs the email columns carried live in request validation; the
    -- database can no longer express "required for this channel only".
    payload             JSONB NOT NULL,
    limit_keys          TEXT[] NOT NULL DEFAULT '{}',
    status              TEXT NOT NULL DEFAULT 'PENDING'
                        CHECK (status IN ('PENDING', 'SENDING', 'SENT', 'FAILED')),
    attempt_count       INT  NOT NULL DEFAULT 0,
    next_attempt_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    provider_message_id TEXT,
    last_error          TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    sent_at             TIMESTAMPTZ
);

-- Ids carry over: they are the provider idempotency key, so a message in
-- flight during the deploy must keep the one the provider already saw.
INSERT INTO messages (id, tenant_id, channel, idempotency_key, recipient, recipient_canonical,
                      sender, payload, limit_keys, status, attempt_count, next_attempt_at,
                      provider_message_id, last_error, created_at, updated_at, sent_at)
SELECT id, tenant_id, 'EMAIL', idempotency_key, recipient, recipient_canonical,
       from_address,
       jsonb_strip_nulls(jsonb_build_object(
           'subject', subject,
           'html',    html_body,
           'text',    text_body,
           'replyTo', reply_to)),
       limit_keys, status, attempt_count, next_attempt_at,
       provider_message_id, last_error, created_at, updated_at, sent_at
FROM email_messages;

-- Kept, not dropped: until this deploy has held, rolling the service back to
-- the previous image must not mean hand-writing SQL under pressure. The
-- indexes move with it so the way back is a pure rename, never a rebuild.
-- V7 drops it.
ALTER INDEX idx_outbox_pending   RENAME TO idx_outbox_pending_pre_v6;
ALTER INDEX idx_quota_tenant_day RENAME TO idx_quota_tenant_day_pre_v6;
ALTER INDEX idx_quota_recipient  RENAME TO idx_quota_recipient_pre_v6;
ALTER INDEX idx_limit_keys       RENAME TO idx_limit_keys_pre_v6;
ALTER INDEX idx_idempotency      RENAME TO idx_idempotency_pre_v6;
ALTER TABLE email_messages RENAME TO email_messages_pre_v6;

-- The worker's poll, now per channel: one channel's backlog must not make the
-- others scan past it.
CREATE INDEX idx_outbox_pending ON messages (channel, next_attempt_at)
    WHERE status = 'PENDING';

-- Quota windows. Channel sits ahead of the time range because every quota
-- question is asked about one channel at a time.
CREATE INDEX idx_quota_tenant_day ON messages (tenant_id, channel, created_at);
CREATE INDEX idx_quota_recipient  ON messages (tenant_id, channel, recipient_canonical, created_at);
CREATE INDEX idx_limit_keys       ON messages USING GIN (limit_keys);

-- Idempotency is scoped per channel on purpose: "order-42-confirmation" sent
-- to both email and WhatsApp is two deliveries a caller legitimately wants,
-- not a replay.
CREATE UNIQUE INDEX idx_idempotency ON messages (tenant_id, channel, idempotency_key)
    WHERE idempotency_key IS NOT NULL;
