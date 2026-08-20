# Herald

Multi-tenant transactional notification service. Reliable email and WhatsApp
delivery with per-tenant API keys, per-tenant sender identities, quotas, and a
retrying outbox. The outbox is channel-agnostic — claim, retry, recovery and
quota are shared, and a new channel is a provider plus a payload shape. Web push
is next.

## How it works

```
client app ──POST /v1/emails──────────▶ quota check (sync) ──▶ outbox row (PENDING)
         └──POST /v1/whatsapp-messages─┘  + template gate           │
                                                                    │
                                     worker (scheduled, per channel) ◀┘
                                     claim batch (FOR UPDATE SKIP LOCKED)
                                     send via that channel's provider, paced
                                     record outcome / schedule retry
```

- **Accepting is synchronous, sending is not.** A submission answers
  `202 Accepted` (or a `429` with a machine-readable reason) immediately; a
  scheduled worker delivers and retries. Callers never see transient provider
  failures.
- **Quotas are enforced at the door**, per tenant **and per channel**: a
  cooldown per recipient (Gmail dot/plus-tag spellings count as one mailbox;
  phone-number spellings as one number), generic per-key daily caps
  (`limitKeys: ["inviter:123"]` against a configurable policy for the `inviter`
  prefix), and a daily ceiling. Rejections report which gate refused, in a
  contractual order. Budgets are per channel because the cost per message is an
  order of magnitude apart — an email daily limit is a spam guard, a WhatsApp
  one is a spending cap.
- **Channels fail independently.** Each is claimed, paced and drained on its
  own, with a ceiling on how long one may hold a pass, so a stalled provider
  cannot starve the others or take a tick down with it.
- **Delivery is at-least-once, and effectively-once where the provider allows
  it.** The outbox claim is crash-safe (`SKIP LOCKED` + a recovery sweep). On
  email the message id doubles as the provider idempotency key, so a retry after
  a crash cannot double-send. Meta's Cloud API has no equivalent, so a WhatsApp
  message caught by the recovery sweep may arrive twice — deliberately, because
  the alternative is failing a message that may never have been sent.
- **Keys are secrets done properly.** `hrl_live_…` bearer tokens, stored only
  as SHA-256, revocable, issued by an admin-only API guarded by a master key.
- **Mail goes out under the client's own identity**, not Herald's. Every
  tenant gets a free verified address on the operator's shared domain
  (`acme@send.example`, no DNS work at all), and can upgrade to its own
  domain: Herald registers it with the provider, hands back the DKIM/SPF
  records to publish, and polls until DNS checks out. A `from` is only
  accepted if it resolves to an identity that tenant actually verified — and
  it goes out in the canonical form Herald checked, so no address can be
  verified under one spelling and mailed under another.
- **WhatsApp is bring-your-own.** The tenant owns the number, the WhatsApp
  Business Account and the templates; Herald dispatches under them and never
  holds a WhatsApp identity of its own. There is no shared tier as there is for
  email — a number carries exactly one display name, so it cannot host several
  tenants the way one domain hosts several mailboxes, and its quality rating
  would be shared by everyone on it. The tenant's credentials are stored
  AES-GCM encrypted under a key that lives only in the environment.
- **A template gate refuses what Meta would refuse.** Herald mirrors the
  approved templates and checks name, language and argument count at
  submission, so a caller gets a `422` naming the problem instead of a FAILED
  row minutes later with an attempt already spent.

## API

Interactive documentation lives at `/swagger-ui.html` on a running instance.

| Method & path | Auth | Purpose |
|---|---|---|
| `POST /v1/emails` | tenant key | Accept an email for delivery |
| `GET /v1/emails/{id}` | tenant key | Delivery status of a message |
| `POST /v1/whatsapp-messages` | tenant key | Accept a WhatsApp template message |
| `GET /v1/whatsapp-messages/{id}` | tenant key | Delivery status of a message |
| `GET /v1/whatsapp-templates` | tenant key | Approved templates, languages and argument counts |
| `POST /v1/sender-identities` | tenant key | Register a domain; returns the DNS records to publish |
| `GET /v1/sender-identities` | tenant key | Identities, their status and DNS records |
| `POST /v1/sender-identities/{id}/verify` | tenant key | Ask the provider to re-check DNS |
| `DELETE /v1/sender-identities/{id}` | tenant key | Drop an identity (not the one it sends as) |
| `POST /admin/v1/tenants` | master key | Create a tenant (with email settings) |
| `GET /admin/v1/tenants` | master key | List tenants |
| `PUT /admin/v1/tenants/{id}/email-settings` | master key | Update sender/limits |
| `PUT /admin/v1/tenants/{id}/limit-policies` | master key | Replace per-key caps |
| `POST /admin/v1/tenants/{id}/api-keys` | master key | Issue a key (plaintext returned once) |
| `DELETE /admin/v1/api-keys/{id}` | master key | Revoke a key |
| `…/tenants/{id}/sender-identities…` | master key | The identity lifecycle for any tenant |
| `POST /admin/v1/tenants/{id}/whatsapp` | master key | Hand over a tenant's WhatsApp credentials; verifies them |
| `GET /admin/v1/tenants/{id}/whatsapp` | master key | Status and ids — never the credentials |
| `POST /admin/v1/tenants/{id}/whatsapp/verify` | master key | Re-prove stored credentials after a rotation |
| `PUT /admin/v1/tenants/{id}/whatsapp/limits` | master key | Update the channel's quota |
| `DELETE /admin/v1/tenants/{id}/whatsapp` | master key | Drop the credentials and the template mirror |
| `GET/POST /webhooks/whatsapp` | HMAC | Meta's delivery receipts; authenticated per tenant by app secret |
| `GET /actuator/health` | public | Health check / uptime ping target |

## Running locally

Requirements: Java 25, Docker.

```bash
./mvnw spring-boot:run   # starts Postgres via compose.yaml automatically
```

Set `ADMIN_API_KEY` to unlock the admin endpoints and `RESEND_API_KEY` to
actually deliver email; add `HERALD_SECRET_KEY` for WhatsApp. Without a
channel's credentials the service still accepts and queues for it — dispatch
pauses for that channel alone, and the others carry on.

## Tests

```bash
./mvnw verify
```

Unit tests cover the pure decision logic (retry policy, provider response
classification, address and phone-number canonicalization, template parsing,
verification backoff, key format, webhook signatures, encryption round-trips).
Integration tests run against a real Postgres (Testcontainers) and WireMock
providers — including concurrent outbox claims, the full quota contract, the
domain-verification lifecycle, per-channel isolation of budgets and failure,
the V6 migration against real pre-existing rows, and the webhook's refusal of
unsigned, tampered and cross-tenant payloads. No test ever talks to a real
provider.

## Configuration

| Variable | Required | Purpose |
|---|---|---|
| `SPRING_DATASOURCE_URL` | yes | JDBC URL (`jdbc:postgresql://…?sslmode=require`) |
| `SPRING_DATASOURCE_USERNAME` / `SPRING_DATASOURCE_PASSWORD` | yes | Database credentials |
| `ADMIN_API_KEY` | no | Master key for `/admin/v1/**`; absent → admin surface disabled |
| `RESEND_API_KEY` | no | Provider key; absent → dispatch paused, messages queue |
| `HERALD_SHARED_ROOT_DOMAIN` | no | Operator domain for the free sender tier (`send.example`); absent → tier disabled and tenants need an explicit `fromAddress`. [Setup](docs/operations.md#sender-identities) |
| `HERALD_SECRET_KEY` | no | Base64 256-bit key (`openssl rand -base64 32`) encrypting tenants' WhatsApp credentials; absent → the channel is off and registering credentials is refused rather than storing them in the clear. **Losing it means re-collecting every tenant's token.** |
| `HERALD_WHATSAPP_VERIFY_TOKEN` | no | Answers Meta's webhook subscription handshake; absent → the handshake is refused. [Setup](docs/operations.md#whatsapp-bring-your-own) |
| `PORT` | no | HTTP port (default 8080) |
| `SPRING_PROFILES_ACTIVE` | no | `prod` enables structured (ECS JSON) logs |

Tuning knobs (defaults in `application.yml`): `herald.outbox.poll-interval`,
`batch-size`, `max-attempts`, `send-interval`, `herald.resend.*` and
`herald.whatsapp.*` timeouts, per-channel `send-interval`, and
`herald.whatsapp.api-version` — a Graph API version lives about two years, so
that one is a deliberate bump, not a constant.

## Deployment

The `Dockerfile` builds a layered image with a CDS training run for fast cold
starts on small containers; `render.yaml` describes a free-tier web service
on Render with the database on Neon. Operational runbook — provisioning
tenants, setting up the shared sender domain, rotating keys, handling dead
letters — in [docs/operations.md](docs/operations.md).

## Roadmap

- Template authoring inside Herald: today the mirror is read-only and templates
  are written in the WhatsApp Manager. The tenant's token already carries
  `whatsapp_business_management`, so proxying create/edit needs nothing new from
  Meta — only the work.
- Web push channel (per-tenant VAPID key pair, subscription registry keyed by
  `(tenant, externalUserId)`)
- Stored templates with per-tenant variables
- Delivery webhooks

SMS was evaluated and left out: against WhatsApp in Brazil it costs more per
message, bills the operator rather than the tenant, and couples opt-out across
tenants on a shared sender ID. Per-tenant sender IDs fix that but cost weeks of
provisioning and a monthly lease each.
