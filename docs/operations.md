# Operations

Day-two tasks for a running Herald instance. All examples assume:

```bash
HERALD=https://<your-instance>          # e.g. https://herald-xxxx.onrender.com
ADMIN="Authorization: Bearer $ADMIN_API_KEY"
```

## Sender identities

Mail leaves under the client's identity, never Herald's. Two tiers:

**The shared tier — one-time setup, then free for every tenant.** Register one
domain you own (a subdomain like `send.example.com` keeps your main domain's
reputation separate) at the provider, publish the SPF/DKIM records it hands
back into that zone, wait for it to read `verified`, then set
`HERALD_SHARED_ROOT_DOMAIN=send.example.com` and restart. From then on every
new tenant is created with a verified address of its own on it —
`Acme App <acme@send.example.com>` — with no DNS work on the client's side.
Each tenant holds exactly one mailbox there, never the domain, so no tenant
can send as another. Leave the variable unset to keep the tier off; tenant
creation then requires an explicit `fromAddress`.

**Custom domains — self-service.** The tenant registers its own domain, gets
the DNS records back, publishes them, and asks for verification:

```bash
TENANT="Authorization: Bearer hrl_live_..."
curl -sS -X POST "$HERALD/v1/sender-identities" -H "$TENANT" \
  -H "Content-Type: application/json" -d '{"domain":"acme.example"}'
# → 201 {"id":"<identity-id>","status":"PENDING","dnsRecords":[{"record":"DKIM",...}]}

# ...after publishing those records in the domain's zone:
curl -sS -X POST "$HERALD/v1/sender-identities/<identity-id>/verify" -H "$TENANT"
# → 202 {"status":"VERIFYING"}

curl -sS "$HERALD/v1/sender-identities" -H "$TENANT"   # PENDING → VERIFYING → VERIFIED
```

A poller re-asks the provider on an escalating ladder (a minute at first,
hourly later) and gives up after roughly three days with
`lastError: "verification timed out"`. Once VERIFIED, any address at that
domain is accepted as `from`; until then, sending from it is a `422` with
type `/errors/sender-not-verified`.

**A dedicated subdomain for one tenant** (isolating a noisy or high-volume
sender's reputation from the shared pool) is the same flow driven by you,
since the DNS is yours — tenants are refused subdomains of the shared root:

```bash
curl -sS -X POST "$HERALD/admin/v1/tenants/<tenant-id>/sender-identities" \
  -H "$ADMIN" -H "Content-Type: application/json" \
  -d '{"domain":"acme.send.example.com"}'
# publish the returned records in your own zone, then .../verify
```

**Identities you configured by hand** — including every tenant that existed
before this feature — are stored with `provider_ref` NULL, meaning Herald
trusts your word that the domain is verified at the provider rather than
having registered it itself. If that word was wrong, the provider rejects the
send and the message lands FAILED with the reason in `lastError`. To review
what a tenant may send as:

```sql
SELECT kind, identifier, status, provider_ref, last_error
FROM sender_identities WHERE tenant_id = '<tenant-id>';
```

## Provisioning a tenant

```bash
# 1. Create the tenant. Omit fromAddress to put it on the shared tier;
#    pass one to use a domain you have verified at the provider yourself.
curl -sS -X POST "$HERALD/admin/v1/tenants" -H "$ADMIN" -H "Content-Type: application/json" -d '{
  "slug": "acme",
  "name": "Acme App",
  "email": {
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
payload, an unverified sender domain) or when retryable failures exhausted
`herald.outbox.max-attempts`. `GET /v1/emails/{id}` shows `lastError`.

A sudden run of rejections on one tenant usually means its sender identity
stopped being valid at the provider — check `sender_identities` for it.

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
  provider's daily quota when the backlog is large. Domain verification polls
  pause with it.
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
