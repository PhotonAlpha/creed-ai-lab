# creed-resource-order — handoff

**Purpose** OAuth2 resource server serving orders. Structural twin of `creed-resource-catalog`.
**Skill** `creed-resource-order` · shared: `creed-platform`

## Run

```bash
mvn -pl creed-resource/creed-resource-order spring-boot:run \
  -Dspring-boot.run.profiles=primary -Dspring-boot.run.workingDirectory="$PWD"
curl -k https://localhost:18091/api/order/ping
```

| Profile | Port |
|---|---|
| `primary` | 18091 |
| `secondary` | 18092 |
| `cloud` | **18082** — see open items |

## Current state

- `api/OrderController` (`/api/order`): full CRUD plus several endpoints that exist to exercise
  *other* modules' edge cases:
  - `GET /ping` — the LB health-check path (sleeps 500 ms deliberately).
  - `GET /items` — sleeps a random 0–8 s, for timeout/latency testing.
  - `GET /bulk?size=&itemRange=&fail=` — generates a large random order feed, deliberately producing
    orders the aggregator's fulfillment filter must reject; `fail=true` fault-injects a 500.
  - `POST /checkout` — the strict variant of create (400 with an error body instead of defaulting),
    used by the `creed-simple-metrics` checkout chain.
  - `POST /session` + `POST /echo` — hand-built `Set-Cookie` headers reproducing the
    "`Max-Age` without `Expires` ⇒ `HttpCookie.parse()` guesses version 1" corruption that
    `creed-simple-metrics`' cookie-relay demonstrates.
- In-memory store seeded with two orders; ids `ORD-<seq>` from 900.

## Landmines

- **`GET /items` sleeps up to 8 seconds and `/ping` sleeps 500 ms.** This is intentional (latency
  testing) but will look like a hang or a broken health check if you don't know. Don't "optimize" it
  away without checking what depends on it.
- **`/session` builds `Set-Cookie` by hand on purpose.** `ResponseCookie.toString()` always pairs
  `Max-Age` with `Expires`, which masks the very bug this endpoint reproduces. Do not refactor it to
  use `ResponseCookie`.
- Security is `permitAll` with the JWT decoder commented out — same as the catalog twin; re-enable
  both together.
- `file:${creed.rootPath}` bundle → run with `-Dspring-boot.run.workingDirectory="$PWD"`.

## Open items

- OAuth2 validation is off (mesh-wide).

**Note on the `cloud` port**: 18082 is **not** a slip. `cloud` runs one instance per service on a
contiguous range — catalog 18081, order 18082, payment 18083, env-matrix 18084 — deliberately
separate from the 1809x primary/secondary pair. All four are distinct, so the `cloud` topology has no
collision.
