---
name: creed-resource-veracode
description: creed-resource-veracode — Veracode report analysis backend (Confluence page → findings → Jira lookup/create → xlsx), its @HttpExchange Confluence/Jira clients, the in-process Confluence/Jira mocks, and the /veracode page of creed-env-matrix-design. Use for report parsing, JQL/matching rules, Jira issue creation, the mocks, or the Veracode UI.
---

# creed-resource-veracode

Plain HTTP **18097** (HTTPS with profile `ssl`), no DB, stateless. Frontend page `/veracode` in `creed-env-matrix-design`
(Vite proxy `/api/veracode` → `VITE_VERACODE_TARGET`). See [[creed-platform]] for build/run, and
[[creed-env-matrix]] for the frontend's conventions (one root ConfigProvider, no `.ant-*`, i18n keys
typed from `en-US.ts`, numeric `scroll.x` with a fixed-right column).

## Shape

- `client/confluence/ConfluenceClient`, `client/jira/JiraClient` — `@HttpExchange` interfaces;
  `config/RemoteClientsConfiguration` builds one `RestClient` per system from Boot's prototype
  `RestClient.Builder` (JDK HttpClient, **no redirects**, `Authorization` from `RemoteAuth`
  NONE/BEARER/BASIC). Static `confluenceClient(RestClient)` / `jiraClient(RestClient)` exist so tests
  can put the same proxy on another transport.
- `service/ReportParser` — every source (page storage, uploaded `.html/.csv/.xlsx/.xls`) is first reduced
  to `Table`s of cell text, then one shared header/row rule (`read`) → `ReportRow`s → `domain/Finding.group` →
  `service/JiraLookupService` (versions, JQL search, match, create) → `VeracodeAnalysisService`
  (analyse / create, re-reads everything per request) → `ExcelExporter` (POI).
- `mock/` — `MockConfluenceController` (pages = `classpath:mock/confluence/<id>.html`, 98765 = report,
  98766 = no report table), `MockJiraController` + `MockJiraStore` (project CREED, versions
  2.2.1/2.3.0/2.4.0, four seeded issues, keys from CREED-105). `@ConditionalOnProperty
  veracode.mock.enabled`. The base URLs default to `http://localhost:${server.port}/mock/...`.

## Rules that are easy to break

- **Uploads: one source abstraction, not a second code path.** `ReportSourceRef(pageId, uploadId)` goes
  through analyse/create/export; `uploadId` wins. `UploadedReportStore` holds parsed rows in memory
  (TTL, max entries) → `upload_expired` 404 after restart / on another instance. Over-size uploads need the
  unscoped `UploadLimitExceptionHandler` (multipart fails before a handler is chosen). Excel via
  `DataFormatter`; CSV UTF-8 else GB18030.

- **Finding = (CVE or normalised title, affected version)**; id `CVE-…@2.3.0` /
  `title:<normalised>@2.3.0`. Create requests carry ids only; the server re-reads page + Jira under a
  per-process lock → `ALREADY_EXISTS` instead of duplicates. Don't switch to client-supplied finding data.
- **Never put an unknown version into JQL** — Jira 400s the whole query. Versions go through
  `projectVersions()` (lower-case → Jira spelling); unknown → `jiraVersion: null`, not searched, not creatable.
- **`summary ~` narrows, Java decides** (`covers`/`summaryNames`). Lucene reserved chars are replaced by
  spaces in phrases (`Jql.searchPhrase`), not escaped. The mock implements the same token semantics, so a
  substring-based "fix" in the mock would hide a real-Jira miss.
- Search pages until an **empty page or `startAt >= total`** — trusting `total` alone can spin.
- Exception handler is `@RestControllerAdvice(assignableTypes = VeracodeController.class)`: the mocks must
  keep answering in Confluence's/Jira's own error shapes, which `UpstreamException.detail` parses.
- **TLS goes through `ssl-bundle` per client, not Boot's HTTP-client properties** — `configure()` builds its
  own `JdkClientHttpRequestFactory`. Profile `ssl` (one-way, `client-auth: none`) serves HTTPS and points
  the self-calling mocks at `https://localhost` with the server's own bundle as trust; keep the bundle
  declaration out of `application.yml` (Boot opens declared bundles eagerly).
- actuator shows values (`show-values: always`); secrets are masked only by `ActuatorSanitizingConfiguration`.
  A new secret property must end in password/secret/token or it shows in `/actuator/env` in plain text.
- **`spring.config.additional-location` is a default property set in `main()` (`application()`)**, so
  `@SpringBootTest` never sees it — test it through `CreedResourceVeracodeApplication.application()`
  (`ExternalConfigDirTest`). It is not honoured from `application.yml`: Boot reads it before any config file is loaded.
- Jira Cloud: `/rest/api/2/search` is retired there (→ `/rest/api/3/search/jql`); the client is DC v2.

## Tests

`VeracodeApiTest` runs the full chain with `@SpringBootTest` + MockMvc: two `@Primary` client beans use a
`ClientHttpRequestFactory` that builds `MockMvcClientHttpRequestFactory` **lazily** (building MockMvc
while the context still creates these beans would read a half-built context). `application-test.yml`
sets `jql-batch-size: 3`, `page-size: 2` so batching and paging are exercised. `MockJiraStore.reset()`
in `@BeforeEach`.
