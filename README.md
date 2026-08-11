# Herald

Multi-tenant transactional notification service. Reliable email delivery with
per-tenant API keys, quotas, and a retrying outbox. Web push is on the
roadmap; the tenant and limit model is already channel-agnostic.

## How it works

```
client app ──POST /v1/emails──▶ quota check (sync) ──▶ outbox row (PENDING)
                                                            │
                                    worker (scheduled) ◀────┘
                                    claim batch (FOR UPDATE SKIP LOCKED)
                                    send via provider, paced
                                    record outcome / schedule retry
```

- **Accepting is synchronous, sending is not.** `POST /v1/emails` answers
  `202 Accepted` (or a `429` with a machine-readable reason) immediately; a
  scheduled worker delivers and retries. Callers never see transient provider
  failures.
- **Quotas are enforced at the door**, per tenant: a cooldown per recipient
  mailbox (Gmail dot/plus-tag spellings count as one mailbox), generic
  per-key daily caps (`limitKeys: ["inviter:123"]` against a configurable
  policy for the `inviter` prefix), and a tenant daily ceiling. Rejections
  report which gate refused, in a contractual order.
- **Delivery is effectively-once.** The outbox claim is crash-safe
  (`SKIP LOCKED` + a recovery sweep), and the message id doubles as the
  provider idempotency key, so a retry after a crash cannot double-send.
- **Keys are secrets done properly.** `hrl_live_…` bearer tokens, stored only
  as SHA-256, revocable, issued by an admin-only API guarded by a master key.

## API

Interactive documentation lives at `/swagger-ui.html` on a running instance.

| Method & path | Auth | Purpose |
|---|---|---|
| `POST /v1/emails` | tenant key | Accept an email for delivery |
| `GET /v1/emails/{id}` | tenant key | Delivery status of a message |
| `POST /admin/v1/tenants` | master key | Create a tenant (with email settings) |
| `GET /admin/v1/tenants` | master key | List tenants |
| `PUT /admin/v1/tenants/{id}/email-settings` | master key | Update sender/limits |
| `PUT /admin/v1/tenants/{id}/limit-policies` | master key | Replace per-key caps |
| `POST /admin/v1/tenants/{id}/api-keys` | master key | Issue a key (plaintext returned once) |
| `DELETE /admin/v1/api-keys/{id}` | master key | Revoke a key |
| `GET /actuator/health` | public | Health check / uptime ping target |

## Running locally

Requirements: Java 25, Docker.

```bash
./mvnw spring-boot:run   # starts Postgres via compose.yaml automatically
```

Set `ADMIN_API_KEY` to unlock the admin endpoints and `RESEND_API_KEY` to
actually deliver; without the provider key the service accepts and queues,
but dispatch stays paused.

## Tests

```bash
./mvnw verify
```

Unit tests cover the pure decision logic (retry policy, provider response
classification, address canonicalization, key format). Integration tests run
against a real Postgres (Testcontainers) and a WireMock provider — including
concurrent outbox claims and the full quota contract. No test ever talks to
the real provider.

## Configuration

| Variable | Required | Purpose |
|---|---|---|
| `SPRING_DATASOURCE_URL` | yes | JDBC URL (`jdbc:postgresql://…?sslmode=require`) |
| `SPRING_DATASOURCE_USERNAME` / `SPRING_DATASOURCE_PASSWORD` | yes | Database credentials |
| `ADMIN_API_KEY` | no | Master key for `/admin/v1/**`; absent → admin surface disabled |
| `RESEND_API_KEY` | no | Provider key; absent → dispatch paused, messages queue |
| `PORT` | no | HTTP port (default 8080) |
| `SPRING_PROFILES_ACTIVE` | no | `prod` enables structured (ECS JSON) logs |

Tuning knobs (defaults in `application.yml`): `herald.outbox.poll-interval`,
`batch-size`, `max-attempts`, `send-interval`, `herald.resend.*` timeouts.

## Deployment

The `Dockerfile` builds a layered image with a CDS training run for fast cold
starts on small containers; `render.yaml` describes a free-tier web service
on Render with the database on Neon. Operational runbook — provisioning
tenants, rotating keys, handling dead letters — in
[docs/operations.md](docs/operations.md).

## Roadmap

- Web push channel (per-tenant VAPID key pair, subscription registry keyed by
  `(tenant, externalUserId)`)
- SMS / WhatsApp providers
- Stored templates with per-tenant variables
- Delivery webhooks
