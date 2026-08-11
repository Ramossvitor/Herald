-- The email outbox. Accepting a request inserts a PENDING row; a scheduled
-- worker claims batches (FOR UPDATE SKIP LOCKED), sends through the provider
-- and records the outcome. Quota counters are derived from this table — no
-- separate bucket bookkeeping, and a plain SELECT can answer "why was this
-- request blocked".

CREATE TABLE email_messages (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID NOT NULL REFERENCES tenants(id),
    idempotency_key     TEXT,
    recipient           TEXT NOT NULL,
    -- lowercased, and for Gmail domains with dots/+tag stripped: quota windows
    -- count mailboxes, not spellings.
    recipient_canonical TEXT NOT NULL,
    subject             TEXT NOT NULL,
    html_body           TEXT NOT NULL,
    text_body           TEXT NOT NULL,
    reply_to            TEXT,
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

-- The worker's poll: only unsent rows that are due.
CREATE INDEX idx_outbox_pending ON email_messages (next_attempt_at)
    WHERE status = 'PENDING';

-- Quota windows.
CREATE INDEX idx_quota_tenant_day ON email_messages (tenant_id, created_at);
CREATE INDEX idx_quota_recipient  ON email_messages (tenant_id, recipient_canonical, created_at);
CREATE INDEX idx_limit_keys       ON email_messages USING GIN (limit_keys);

-- Replaying an idempotency key returns the original message instead of
-- creating a duplicate.
CREATE UNIQUE INDEX idx_idempotency ON email_messages (tenant_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;
