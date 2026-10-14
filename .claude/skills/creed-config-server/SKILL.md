---
name: creed-config-server
description: The creed-config-server module — Spring Cloud Config Server (native/classpath repo) on HTTPS 8443 context-path /config-server, with HTTP Basic auth, asymmetric {cipher} decryption, a static SimpleDiscoveryClient registry, and the shared programmatic-HTTPS pattern. Use when working on served config (config-repo), encryption/{cipher} secrets, config-client TLS trust, or this module's security/HTTP-client wiring.
---

# creed-config-server

Spring Cloud **Config Server** serving the mesh's externalized config. HTTPS `8443`, context-path `/config-server`. Module path `creed-config-management/creed-config-server` (aggregator pom). Active profiles `native,ssl` (see [[creed-platform]] for the HTTPS/SSL-bundle pattern). Tests use `@ActiveProfiles({"native","test"})` to skip `ssl` and set `creed.https.enabled=false` so the context loads without certs on disk.

## What it serves
- **native repo, six layers** (`config-repo/README.md` is the operator doc):
  `common → type → env-group → env → app → app-env`, later wins. `search-locations` lists one template
  per layer — `${creed.config-repo.root}/type/{profile}/` etc., root `${CREED_CONFIG_REPO_ROOT:classpath:/config-repo}`.
  `NativeEnvironmentRepository.getLocations` expands each template once per comma-separated profile (and
  app), keeps the directories that exist, layer-major — so the request profile `<type>,<env-group>,<env>`
  (`ms,sit,sit1`) picks one directory per layer and the location order *is* the precedence. Only
  `application.yml` per directory (Boot ranks by file name before location — measured). The catalogue of
  types/groups/envs lives in `ConfigRepositoryLayoutTest`. `add-label-locations: false`.
- Clients fetch via `spring.config.import=configserver:https://localhost:8443/config-server` in their **`cloud` profile** only (the resource/gateway modules ship a `truststore.p12` on the classpath to trust the config server's self-signed CA).

## Security & encryption
- **HTTP Basic**: `ConfigServerSecurityConfiguration`; creds `spring.security.user.{name,password}` = `${CREED_CONFIG_USER:creed-config}` / `${CREED_CONFIG_PASSWORD:configpass}`, role `CONFIG_CLIENT`. Clients pass `spring.cloud.config.{username,password}`.
- **Asymmetric `{cipher}` decryption** (Requirement 6): RSA key in `classpath:certs/config-encrypt.p12` (alias `config-key`, declared under `encrypt.key-store.*`). config-repo values stored as `{cipher}<base64>` are decrypted transparently before serving — clients receive plaintext. Encrypt with `curl -k -u creed-config:configpass https://localhost:8443/config-server/encrypt -d '<value>'`. Needs `spring-security-rsa` + bouncycastle (in this module's pom).

## Layout (`com.creed.config`)
- `web/TomcatHttpsConfiguration` — shared programmatic HTTPS listener (bundle `creed-pem-server`, via `creed.https.bundle`). Certs are PEM-based here (`classpath:certs/`), unlike the PKCS12-from-pki modules.
- `security/ConfigServerSecurityConfiguration` — HTTP Basic chain.
- `client/HttpClientsConfiguration` — outbound `RestTemplate`/`RestClient` over a chosen SSL bundle; `@ConfigurationProperties("creed.http-client")` (`enabled`, `ssl-bundle=creed-pem-client`, `connect-timeout`, `read-timeout`). See [[creed-platform]] HttpClient 5 conventions.

## Notes
- Also carries a static `spring.cloud.discovery.client.simple.instances` registry (`catalog-cluster` 18081/18082, `order-cluster` 18091/18092, with `zone` metadata) — present for discovery/LB experiments.
- SSL bundles here are **PEM** (`creed-pem-server`/`creed-pem-client`) shipped on the classpath, so it runs without `-Dspring-boot.run.workingDirectory` unlike the `file:${creed.rootPath}` modules.
