# creed-resource-payment — handoff

**Purpose** OAuth2 resource server serving payments, modelled around an explicit state machine
rather than plain CRUD.
**Skill** `creed-resource-payment` · shared: `creed-platform`

## Run

```bash
mvn -pl creed-resource/creed-resource-payment spring-boot:run \
  -Dspring-boot.run.profiles=primary -Dspring-boot.run.workingDirectory="$PWD"
curl -k https://localhost:18093/api/payment/ping
```

| Profile | Port |
|---|---|
| `primary` | 18093 |
| `secondary` | 18094 |
| `cloud` | 18083 |

## Current state

Lifecycle `PENDING → AUTHORIZED → CAPTURED → REFUNDED`, with `CANCELLED` as a terminal side-exit from
`PENDING`/`AUTHORIZED` only.

| Endpoint | Effect |
|---|---|
| `GET /ping` | LB health-check path |
| `GET`, `GET /{id}` | list (optional `orderId`/`status` filter), fetch (404 if missing) |
| `POST` | create → `PENDING`; 400 on bad amount or unknown method (`CARD`/`BANK_TRANSFER`/`WALLET`) |
| `PUT /{id}` | partial replace |
| `POST /{id}/authorize` · `/capture` · `/refund` | state transitions |
| `DELETE /{id}` | cancel |

**Transitions return `409 Conflict`** — not 400/404 — when the payment is not in the required
predecessor state. That is deliberate: it gives aggregators and tests a realistic error path the
catalog/order twins don't have.

In-memory `ConcurrentHashMap`, ids `PAY-<seq>` from 700, seeded with two payments.

## Landmines

- **The 409s are the feature.** `creed-simple-metrics` and its tests depend on that status code; do
  not "normalize" transition failures to 400.
- **Sticky routing lives in the caller, not here.** `creed-simple-metrics` adds cookie-based sticky LB
  for this cluster specifically — this module is a plain two-instance cluster and knows nothing about
  it. Look there when investigating "why did my request go to the other instance".
- Security is `permitAll` with `CachedJwtDecoderConfiguration` commented out — re-enable both
  together; the decoder resolves the issuer eagerly at startup.
- `file:${creed.rootPath}` bundle → run with `-Dspring-boot.run.workingDirectory="$PWD"`.

## Open items

- OAuth2 validation is off (mesh-wide).
- State lives in memory, so a restart mid-flow loses every in-flight payment. Fine for local testing;
  `creed-resource-env-matrix` is the module that shows the DB-backed alternative if this ever needs
  persistence.
