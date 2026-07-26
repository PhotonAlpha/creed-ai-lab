# creed-config-server — handoff

**Purpose** Spring Cloud Config Server serving the mesh's externalized config.
**Skill** `creed-config-server` · shared: `creed-platform`

## Run

```bash
mvn -pl creed-config-server spring-boot:run          # no workingDirectory flag needed — see below
curl -k -u creed-config:configpass https://localhost:8443/config-server/creed-gateway/cloud
```

HTTPS `8443`, context-path `/config-server`, active profiles `native,ssl`.

## Current state

- **Native repo** at `classpath:/config-repo/`: `application.yml` (shared defaults),
  `creed-author-server.yml`, `creed-gateway.yml`, `creed-resource-catalog.yml`,
  `creed-resource-order.yml`. Add `{app}.yml` / `{app}-{profile}.yml` to extend.
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
