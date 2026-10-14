# creed-config-server — handoff

**Purpose** Spring Cloud Config Server serving the mesh's externalized config.
**Skill** `creed-config-server` · shared: `creed-platform`

## Run

```bash
mvn -pl creed-config-management/creed-config-server spring-boot:run   # no workingDirectory flag needed — see below
curl -k -u creed-config:configpass https://localhost:8443/config-server/creed-gateway/gateway,uat,uat2
```

HTTPS `8443`, context-path `/config-server`, active profiles `native,ssl`.

## Current state

- **Moved under the `creed-config-management` aggregator** (2026-10-14; was a root module).
- **Native repo in six layers** (2026-10-14, by request), `config-repo/README.md`:
  `common → type → env-group → env → app → app-env`, later wins. One `application.yml` per layer
  directory; `search-locations` are templates over `{profile}` / `{application}`. Clients send
  `profile=<type>,<env-group>,<env>` (`ms,sit,sit1`). Types `ms` / `gateway` / `proxy-gateway` /
  `aggregator-ms`; envs SIT1-3, UAT1-3, NFT, PDN in groups sit / uat / nft / pdn. The old flat files
  are now `app/<name>/application.yml`, so the `cloud` clients (profile `cloud`) still get
  common + app — unchanged for them. Root overridable: `CREED_CONFIG_REPO_ROOT=file:/…`.
  - Verified: `LayeredConfigRepositoryTest` (server order + a real config client resolving values,
    cross-layer placeholder, `{cipher}`), `ConfigRepositoryLayoutTest` (catalogue, names, depth), and
    a live HTTPS run with a `file:` root.
  - **No client sends the three-part profile yet** — the modules' `cloud` profiles are untouched.
- **HTTP Basic** (`security/ConfigServerSecurityConfiguration`):
  `${CREED_CONFIG_USER:creed-config}` / `${CREED_CONFIG_PASSWORD:configpass}`, role `CONFIG_CLIENT`.
- **Asymmetric `{cipher}` decryption**: RSA key in `classpath:certs/config-encrypt.p12` (alias
  `config-key`). Values stored as `{cipher}<base64>` are decrypted before serving. Encrypt with
  `curl -k -u creed-config:configpass https://localhost:8443/config-server/encrypt -d '<value>'`.
- Carries a static `spring.cloud.discovery.client.simple.instances` registry
  (`catalog-cluster` 18081/18082, `order-cluster` 18091/18092) with `zone` metadata, for
  discovery/LB experiments. These ports were stale (808x/809x, from before the resource servers
  moved) and have been corrected.

## Landmines

- **Only `application.yml` in a layer directory.** The server also reads `{app}.yml` there, and Boot
  ranks by file NAME before LOCATION: a `type/ms/creed-resource-order.yml` came out above `env/` and
  `env-group/` (measured). `ConfigRepositoryLayoutTest` rejects any other file name.
- **A misspelt layer directory is not an error**, just a layer that never applies; the catalogue in
  `ConfigRepositoryLayoutTest` is what catches it. The same holds for a client sending `sit01`.
- **A test that starts a config client in this module needs `--spring.cloud.config.enabled=true`**:
  the server jar's `ConfigServerBootstrapApplicationListener` switches the client off in every app on
  the classpath. And `@SpringBootTest` needs `classes=` once the test class has a nested `@Configuration`.
- The client tags a source "profile-specific" when its name contains `-<active profile>`; within one
  config-server response that does **not** reorder anything (pinned by a test, in case an upgrade changes it).

- **SSL bundles here are PEM on the classpath** (`creed-pem-server` / `creed-pem-client`), unlike the
  PKCS12-from-`file:${creed.rootPath}` modules — so this one runs **without**
  `-Dspring-boot.run.workingDirectory`. Don't "fix" that by aligning it with the others.
- Tests use `@ActiveProfiles({"native","test"})` to skip `ssl` and set `creed.https.enabled=false`, so
  the context loads with no certs on disk. Keep any new test on that path.
- `{cipher}` needs `spring-security-rsa` + Bouncy Castle, declared in this module's pom.

## Open items

- No module currently imports config from here by default — clients only do so under their `cloud`
  profile, which is not part of the normal local run. The `cloud` path is therefore the least
  exercised in the repo; treat it as unverified until someone runs the mesh that way end to end.
- The static discovery registry duplicates the one in `creed-gateway-partner`. If both are meant to
  be authoritative, they will drift.
