# Operations

Day-two tasks for a running Herald instance. All examples assume:

```bash
HERALD=https://<your-instance>          # e.g. https://herald-xxxx.onrender.com
ADMIN="Authorization: Bearer $ADMIN_API_KEY"
```

## Provisioning a tenant

```bash
# 1. Create the tenant with its email settings
curl -sS -X POST "$HERALD/admin/v1/tenants" -H "$ADMIN" -H "Content-Type: application/json" -d '{
  "slug": "acme",
  "name": "Acme App",
  "email": {
    "fromAddress": "Acme <notify@acme.example>",
    "dailyLimit": 90,
    "recipientCooldownSeconds": 600
  }
}'
# → {"id":"<tenant-id>", ...}

# 2. Optional per-key caps (e.g. cap each inviter at 40/day)
curl -sS -X PUT "$HERALD/admin/v1/tenants/<tenant-id>/limit-policies" \
  -H "$ADMIN" -H "Content-Type: application/json" \
  -d '[{"keyPrefix":"inviter","dailyCap":40}]'

# 3. Issue the API key — the plaintext appears ONLY in this response
curl -sS -X POST "$HERALD/admin/v1/tenants/<tenant-id>/api-keys" \
  -H "$ADMIN" -H "Content-Type: application/json" -d '{"label":"production"}'
# → {"id":"<key-id>","apiKey":"hrl_live_...","keyPrefix":"hrl_live_xxxx"}
```

Smoke-test the tenant end to end:

```bash
curl -sS -X POST "$HERALD/v1/emails" \
  -H "Authorization: Bearer hrl_live_..." -H "Content-Type: application/json" \
  -d '{"to":"you@example.com","subject":"Herald smoke test",
       "html":"<p>It works.</p>","text":"It works."}'
# → 202 {"id":"<message-id>","status":"PENDING",...}

curl -sS "$HERALD/v1/emails/<message-id>" -H "Authorization: Bearer hrl_live_..."
# → {"status":"SENT","providerMessageId":"...","sentAt":"..."}  within seconds
```

## Rotating a key

Issue the new key, deploy it to the client app, then revoke the old one —
both keys work during the overlap:

```bash
curl -sS -X POST "$HERALD/admin/v1/tenants/<tenant-id>/api-keys" -H "$ADMIN" \
  -H "Content-Type: application/json" -d '{"label":"production 2026-09"}'
curl -sS -X DELETE "$HERALD/admin/v1/api-keys/<old-key-id>" -H "$ADMIN"
```

Rotating `ADMIN_API_KEY` is an environment-variable change on the host.

## Dead letters (status FAILED)

A message goes FAILED when the provider rejected it (4xx: bad address, bad
payload, bad provider config) or when retryable failures exhausted
`herald.outbox.max-attempts`. `GET /v1/emails/{id}` shows `lastError`.

To retry a dead letter after fixing the cause, requeue it in SQL:

```sql
UPDATE email_messages
SET status = 'PENDING', attempt_count = 0, next_attempt_at = now()
WHERE id = '<message-id>' AND status = 'FAILED';
```

Watch the `herald.emails.failed` counter (`/actuator/metrics`, admin key) —
it should stay flat in normal operation.

## Pauses and kill switches

- **No `RESEND_API_KEY`**: dispatch pauses; submissions still accept and
  queue. Setting the variable (and restarting) drains the backlog — mind the
  provider's daily quota when the backlog is large.
- **No `ADMIN_API_KEY`**: the whole `/admin/v1/**` surface answers 401/403.
- **Suspend a tenant** (no API in v1 — SQL hatch):
  `UPDATE tenants SET status = 'SUSPENDED' WHERE slug = 'acme';`
  Its keys immediately answer 403. Set back to `ACTIVE` to lift.

## Hosting notes (Render free + Neon + cron-job.org)

- Render's free plan spins the service down after ~15 min without traffic,
  and a JVM boot on 0.1 vCPU is slow even with the CDS archive. An external
  uptime ping — e.g. cron-job.org hitting `GET /actuator/health` every 10
  minutes — keeps the instance warm; the 750 free instance-hours per month
  cover one always-on service.
- Neon's free plan bills compute seconds. The worker's poll backs off to a
  slow cadence when the queue is idle to keep that low; raise
  `herald.outbox.poll-interval` if compute usage ever becomes a concern.
- Migrations run automatically at boot (Flyway). Rolling back a deploy whose
  migration already ran requires a manual, compensating migration — prefer
  additive schema changes.
- Emergency SQL access is the Neon console's SQL editor; every quota decision
  is explainable from `email_messages` (`created_at`, `recipient_canonical`,
  `limit_keys`).
