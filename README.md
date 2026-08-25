# Herald

Multi-tenant transactional email service. Reliable delivery with per-tenant API
keys, per-tenant sender identities, quotas, and a retrying outbox.

## How it works

```
client app ──POST /v1/emails──▶ quota check (sync) ──▶ outbox row (PENDING)
                                                             │
                                     worker (scheduled) ◀─────┘
                                     claim batch (FOR UPDATE SKIP LOCKED)
                                     send via the provider, paced
                                     record outcome / schedule retry
```

- **Accepting is synchronous, sending is not.** A submission answers
  `202 Accepted` (or a `429` with a machine-readable reason) immediately; a
  scheduled worker delivers and retries. Callers never see transient provider
  failures.
- **Quotas are enforced at the door**, per tenant: a cooldown per recipient
  (Gmail dot/plus-tag spellings count as one mailbox), generic per-key daily
  caps (`limitKeys: ["inviter:123"]` against a configurable policy for the
  `inviter` prefix), and a daily ceiling. Rejections report which gate refused,
  in a contractual order.
- **Delivery is at-least-once, and effectively-once at the destination.** The
  outbox claim is crash-safe (`SKIP LOCKED` + a recovery sweep), and the message
  id doubles as the provider idempotency key, so a retry after a crash cannot
  double-send.
- **The provider reports back, and Herald acts on it.** A signed webhook carries
  delivery, bounce and complaint events; `deliveryState` on a message says
  whether it actually arrived, as opposed to `status`, which only says Herald
  handed it over. An address that bounces permanently or reports spam goes on
  that tenant's suppression list and is refused at the door from then on —
  which is what stops a dead address being retried forever and taking the
  sending domain's reputation with it.
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

## API

Interactive documentation lives at `/swagger-ui.html` on a running instance.

| Method & path | Auth | Purpose |
|---|---|---|
| `POST /v1/emails` | tenant key | Accept an email for delivery |
| `GET /v1/emails/{id}` | tenant key | Delivery status of a message |
| `GET /v1/suppressions` | tenant key | Addresses that bounced or reported spam |
| `DELETE /v1/suppressions/{email}` | tenant key | Lift a suppression |
| `POST /v1/webhooks/resend` | Svix signature | Delivery events from the provider |
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
| `GET /actuator/health` | public | Health check / uptime ping target |

## Running locally

Requirements: Java 25, Docker.

```bash
./mvnw spring-boot:run   # starts Postgres via compose.yaml automatically
```

Set `ADMIN_API_KEY` to unlock the admin endpoints and `RESEND_API_KEY` to
actually deliver email. Without the provider key the service still accepts and
queues — dispatch pauses, and nothing is lost.

## Tests

```bash
./mvnw verify
```

Unit tests cover the pure decision logic (retry policy, provider response
classification, address canonicalization, verification backoff, key format,
webhook event precedence). The webhook signature check is pinned to a published
Svix vector rather than to a signature the test computes itself — generating the
expected value with the code under test would pass just as happily if the
algorithm were wrong in both places.

Integration tests run against a real Postgres (Testcontainers) and a WireMock
provider — including concurrent outbox claims, the full quota contract, the
domain-verification lifecycle, and the delivery-feedback loop (a bounce
suppressing an address, a forged or replayed webhook changing nothing). No test
ever talks to a real provider.

## Configuration

| Variable | Required | Purpose |
|---|---|---|
| `SPRING_DATASOURCE_URL` | yes | JDBC URL (`jdbc:postgresql://…?sslmode=require`) |
| `SPRING_DATASOURCE_USERNAME` / `SPRING_DATASOURCE_PASSWORD` | yes | Database credentials |
| `ADMIN_API_KEY` | no | Master key for `/admin/v1/**`; absent → admin surface disabled |
| `RESEND_API_KEY` | no | Provider key; absent → dispatch paused, messages queue |
| `RESEND_WEBHOOK_SECRET` | no | Svix signing secret (`whsec_…`) for delivery events; absent → the webhook is closed and nothing is suppressed. [Setup](docs/operations.md#delivery-events-and-suppressions) |
| `HERALD_SHARED_ROOT_DOMAIN` | no | Operator domain for the free sender tier (`send.example`); absent → tier disabled and tenants need an explicit `fromAddress`. [Setup](docs/operations.md#sender-identities) |
| `PORT` | no | HTTP port (default 8080) |
| `SPRING_PROFILES_ACTIVE` | no | `prod` enables structured (ECS JSON) logs |

Tuning knobs (defaults in `application.yml`): `herald.outbox.poll-interval`,
`batch-size`, `max-attempts`, `send-interval`, and the `herald.resend.*`
timeouts.

## Deployment

The `Dockerfile` builds a layered image with a CDS training run for fast cold
starts on small containers; `render.yaml` describes a free-tier web service
on Render with the database on Neon. Operational runbook — provisioning
tenants, setting up the shared sender domain and the delivery webhook, rotating
keys, handling dead letters and suppressions — in
[docs/operations.md](docs/operations.md).

## Roadmap

- **Retention for `messages`.** The table grows without bound, and every quota
  counter derives from it on the hot path of each submission — so the cost of
  accepting an email slowly rises with the history behind it. A purge (or
  date partitioning) is the fix; `webhook_events` needs the same sweep.
- **Stored templates with per-tenant variables**, so callers stop shipping
  rendered HTML on every request.
- **Scheduled sends** (`sendAt`): the outbox already dispatches on
  `next_attempt_at`, so this is mostly an API and a validation question.
- **A requeue endpoint for dead letters**, which today is a documented SQL
  statement.
- **Pacing that survives a second instance.** `herald.outbox.send-interval` is
  per process, so N instances send at N times the intended rate. The claim
  itself is already safe to run concurrently (`SKIP LOCKED`); it is the rate
  limit that assumes one worker, and that is the current ceiling on scaling
  out.

Herald sends email and nothing else. Other channels were built and removed:
WhatsApp worked, but running it meant carrying Meta's template approval,
per-tenant credential encryption and a webhook surface for one more product to
support. SMS never shipped — in Brazil it costs more per message than WhatsApp,
bills the operator rather than the tenant, and couples opt-out across tenants on
a shared sender ID. The multi-channel outbox that carried them is in the history
at `0275da5` if it is ever wanted back.
