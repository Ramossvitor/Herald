-- The outbox. Accepting a request inserts a PENDING row; a scheduled worker
-- claims batches (FOR UPDATE SKIP LOCKED), sends through the provider and
-- records the outcome. Quota counters are derived from this table — no
-- separate bucket bookkeeping, and a plain SELECT can answer "why was this
-- request blocked".
--
-- The table is `messages`, not `email_messages`: what it holds is email-shaped,
-- but what it is is the outbox, and the machinery that owns it — claim, pace,
-- retry, recover — is not. The entity and its repository are named for the job,
-- and the table follows them.
--
-- V1–V4 were rewritten in place before Herald's first deploy, when the only
-- database that had run them was a developer's. What they replace — a JSONB
-- payload column, a channel discriminator, and the WhatsApp tables of V5–V8 —
-- is in the history at aab2dd5. That was a one-time licence taken against an
-- unreleased schema. From here the rule in docs/operations.md holds: a
-- migration that has run is immutable, and a change to it is a new version.

CREATE TABLE messages (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID NOT NULL REFERENCES tenants(id),
    idempotency_key     TEXT,
    recipient           TEXT NOT NULL,
    -- lowercased, and for Gmail domains with dots/+tag stripped: quota windows
    -- count mailboxes, not spellings.
    recipient_canonical TEXT NOT NULL,
    -- Snapshot taken at submission: editing tenant settings must not silently
    -- change messages already queued.
    from_address        TEXT NOT NULL,
    -- Content is columns, not a JSON blob: the database can express "required"
    -- here, and a typo in a field name becomes a compile error instead of a
    -- null reaching the provider.
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
CREATE INDEX idx_outbox_pending ON messages (next_attempt_at)
    WHERE status = 'PENDING';

-- Quota windows.
CREATE INDEX idx_quota_tenant_day ON messages (tenant_id, created_at);
CREATE INDEX idx_quota_recipient  ON messages (tenant_id, recipient_canonical, created_at);
CREATE INDEX idx_limit_keys       ON messages USING GIN (limit_keys);

-- Replaying an idempotency key returns the original message instead of
-- creating a duplicate.
CREATE UNIQUE INDEX idx_idempotency ON messages (tenant_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;
