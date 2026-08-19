-- The from address becomes a per-message snapshot taken at submission: editing
-- tenant settings must not silently change messages already queued.

ALTER TABLE email_messages ADD COLUMN from_address TEXT;

UPDATE email_messages m
SET from_address = s.from_address
FROM tenant_email_settings s
WHERE s.tenant_id = m.tenant_id;

-- Safety net for rows whose tenant somehow has no settings row.
UPDATE email_messages SET from_address = 'unknown@invalid' WHERE from_address IS NULL;

ALTER TABLE email_messages ALTER COLUMN from_address SET NOT NULL;
