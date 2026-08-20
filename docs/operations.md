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
can send as another — the root itself is not registrable as an identity by
anyone, you included, because one identity on it would cover every tenant's
address at once. Leave the variable unset to keep the tier off; tenant
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

A registration that ends FAILED keeps its row — the tenant needs to read why —
but gives the domain back: the provider registration behind it is deleted, so
the name is free for whoever can prove they own it, and the tenant can register
it again once DNS is fixed. A tenant may hold at most ten domains that are not
verified yet, which is what stops registrations being used to squat names or to
drain your provider account's domain allowance.

A `from` is stored and sent in a canonical form, never as the caller spelled
it: a display name is quoted when RFC 5322 needs it, and anything with two
readings — a second `<addr>`, trailing text, a control character — is refused
outright rather than verified on one address and mailed from another.

**A dedicated subdomain for one tenant** (isolating a noisy or high-volume
sender's reputation from the shared pool) is the same flow driven by you,
since the DNS is yours — tenants are refused subdomains of the shared root:

```bash
curl -sS -X POST "$HERALD/admin/v1/tenants/<tenant-id>/sender-identities" \
  -H "$ADMIN" -H "Content-Type: application/json" \
  -d '{"domain":"acme.send.example.com"}'
# publish the returned records in your own zone, then .../verify
```

Setting a tenant's `fromAddress` also trusts that domain for it, promoting an
existing row for the same domain if there is one — so an identity that failed
self-service verification becomes usable the moment you vouch for it. Editing
only the limits leaves identities alone.

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
# 1. Create the tenant. Omit fromAddress (or leave it blank) to put it on the
#    shared tier; pass one to use a domain you verified at the provider yourself.
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

## WhatsApp (bring-your-own)

Herald never holds a WhatsApp identity of its own. The tenant owns the number,
the WhatsApp Business Account and the templates; Herald dispatches under them.
So most of this section is work the *client* does, and the operator's part is
receiving the result.

**One-time, on your side.** Two variables, then a restart:

```bash
openssl rand -base64 32          # → HERALD_SECRET_KEY
```

`HERALD_SECRET_KEY` encrypts every tenant's credentials at rest (AES-GCM).
Without it the channel stays off and registering credentials is refused rather
than storing them in the clear. **Back it up.** It is not in the database on
purpose, and losing it means asking every tenant for a new token.

Each value is also bound to the row and column it lives in, so a ciphertext
moved between tenants — or between `access_token` and `app_secret` — stops
decrypting rather than quietly working somewhere it should not. Practically:
never copy those columns between rows when repairing data, and re-register the
tenant instead.

`HERALD_WHATSAPP_VERIFY_TOKEN` is any random string. It answers Meta's
subscription handshake and is the same for every tenant — that GET carries only
the token and a challenge, nothing that says which tenant is subscribing, so it
cannot be per tenant. It guards the handshake only; every real notification is
authenticated by the tenant's own app secret.

**What the tenant does, in Meta's console.** Create a Business Portfolio, an
app, and a WhatsApp Business Account; pass Business Verification (2–4 days
typical — until then the number is capped at 250 business-initiated
conversations per 24h); register a dedicated phone number that is **not in use
in the WhatsApp app**; get a display name approved; publish a privacy policy;
create a permanent system user token carrying `whatsapp_business_messaging` and
`whatsapp_business_management`; and point their app's webhook callback at
`https://<your-instance>/webhooks/whatsapp` with your verify token, subscribed
to the `messages` field.

**What they hand you** — four values, two of them secrets:

| Value | What it is for |
|---|---|
| `phoneNumberId` | the sending number; it becomes the message's `sender` |
| `wabaId` | templates hang off it, and inbound receipts name it |
| `accessToken` | presented to Meta on every send |
| `appSecret` | verifies the HMAC on every webhook |

```bash
curl -sS -X POST "$HERALD/admin/v1/tenants/<tenant-id>/whatsapp" \
  -H "$ADMIN" -H "Content-Type: application/json" -d '{
  "phoneNumberId": "1555...", "wabaId": "1234...",
  "accessToken": "EAA...", "appSecret": "...",
  "dailyLimit": 500, "recipientCooldownSeconds": 0
}'
# → 201 {"status":"VERIFIED","templatesSyncedAt":"...", ...}
```

Registration proves the credentials against Meta before answering, so a typo in
a phone number id comes back on this request rather than as failed messages an
hour later. `FAILED` carries Meta's own reason in `lastError`. `PENDING` means
Meta could not be reached — the credentials are stored, so retry with
`POST …/whatsapp/verify` rather than collecting them again. The same endpoint is
what to call after the tenant rotates its token.

Nothing you send here is ever readable again: `GET …/whatsapp` answers with the
ids and the status, never the credentials.

`dailyLimit` and `recipientCooldownSeconds` are both required, on this endpoint
and on `PUT …/whatsapp/limits`; omitting one is a `422`. There is no default to
fall back on, because the only value an omitted number could take is zero — and
a zero daily limit is a tenant whose every send is refused by the quota check,
on credentials that reported `VERIFIED`.

**Templates.** Authored by the tenant in the WhatsApp Manager; Herald mirrors
them every 30 minutes and refuses a send whose template is unknown, not
`APPROVED`, carries the wrong number of arguments, or wants a value Herald never
sends — a `422` at submission instead of a FAILED row and a spent attempt. That
last case is worth knowing when a tenant asks why an approved template is
refused: Herald fills the body and nothing else, so a template with a variable
in its header or in a button URL cannot be completed, and Meta would reject the
delivery. Use one whose only `{{n}}` placeholders are in the body. The tenant reads the mirror at
`GET /v1/whatsapp-templates`. Mind the category: in Brazil a MARKETING template
costs roughly 8x a UTILITY one, and Meta may reclassify after approving, which
is why the category is mirrored too.

```bash
curl -sS -X POST "$HERALD/v1/whatsapp-messages" \
  -H "Authorization: Bearer hrl_live_..." -H "Content-Type: application/json" \
  -d '{"to":"+5511999990000","template":"order_update","language":"pt_BR",
       "params":["Acme","42"]}'
# → 202 {"id":"<message-id>","status":"PENDING",...}
```

`to` is E.164, with the plus. Herald will not infer a country code: a bare
national number does not fail on send, it delivers to whoever holds that number
somewhere else, and bills for it.

**Delivery receipts.** A 200 from Meta means "accepted", not "arrived". The
webhook is what closes that gap — a `failed` receipt flips the message to FAILED
with Meta's reason. Without the webhook configured, a number that never receives
anything looks exactly like one that does.

The endpoint answers `200` to anything it parsed, including a payload naming a
WhatsApp Business Account no tenant here owns — Meta retries everything else,
and one callback URL serves every tenant, so a deregistered account would
otherwise retry until the whole subscription is throttled. It also means the
response cannot be used to work out who is a customer. A `403` means the
signature did not verify; a `400` means the body named no account or more than
one; a `413` means it was over 1 MiB.

**One caveat worth knowing.** Meta's Cloud API takes no idempotency key, so a
crash between sending and recording leaves a message the recovery sweep will
send again — and Meta will deliver again. Email does not have this exposure
(the message id is its idempotency key there). Rare, but real.

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
stopped being valid at the provider — check `sender_identities` for email, or
`tenant_whatsapp_settings.status` for WhatsApp.

On WhatsApp an expired or revoked token does *not* produce that run. Meta
answers `401`/code `190`, Herald treats it as an outage rather than a rejection,
and the queued messages back off instead of dying — the tenant's backlog
survives the rotation window. Herald also flips `tenant_whatsapp_settings` to
`FAILED` on the first such answer, so submission stops accepting new work under
a token that no longer functions. `POST …/whatsapp/verify` after the tenant
re-registers is what resumes both. The same applies to a wrong
`HERALD_SECRET_KEY`: nothing can be decrypted, so nothing is sent, but nothing
is lost either — fix the variable and the backlog drains.

On WhatsApp a message can also go FAILED *after* being SENT: Meta accepted it,
then reported a delivery failure over the webhook. Those rows keep their
`sent_at` and carry `delivery failed code …` in `lastError` — deliberately
terminal, since a bad number will not become good on a retry.

To retry a dead letter after fixing the cause, requeue it in SQL:

```sql
UPDATE messages
SET status = 'PENDING', attempt_count = 0, next_attempt_at = now()
WHERE id = '<message-id>' AND status = 'FAILED';
```

Watch the `herald.messages.failed` counter (`/actuator/metrics`, admin key),
tagged by `channel` — it should stay flat in normal operation. Its siblings
`herald.messages.accepted`, `.sent` and `.rejected` carry the same tag, so a
problem on one channel is visible without reading the others.

## Pauses and kill switches

- **No `RESEND_API_KEY`**: email dispatch pauses; submissions still accept and
  queue. Setting the variable (and restarting) drains the backlog — mind the
  provider's daily quota when the backlog is large. Domain verification polls
  pause with it. Other channels are unaffected.
- **No `HERALD_SECRET_KEY`**: WhatsApp dispatch pauses the same way — no key,
  no credential can be decrypted. Queued messages wait without spending an
  attempt, and registering new credentials answers `409` rather than storing
  them unencrypted.
- **Suspend one tenant's WhatsApp** without touching its email:
  `DELETE /admin/v1/tenants/<id>/whatsapp`. Queued messages stay and fail at
  dispatch with a reason, which beats losing them silently.
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
- V6 (the shared outbox) follows that rule: it copies `email_messages` into
  `messages` and parks the original as `email_messages_pre_v6`, indexes and
  all. To roll back to a pre-V6 image before V7 has run, rename the five
  `idx_*_pre_v6` indexes and the table back, drop `messages`, and delete the V6
  row from `flyway_schema_history`. V7 drops the parked table and closes that
  window — hold it back until V6 has survived a deploy.
- Emergency SQL access is the Neon console's SQL editor; every quota decision
  is explainable from `messages` (`channel`, `created_at`, `recipient_canonical`,
  `limit_keys`). Budgets are per channel, so every such query wants a
  `channel = '...'` alongside the tenant.
