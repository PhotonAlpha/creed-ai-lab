---
name: creed-resource-veracode
description: creed-resource-veracode — Veracode SCA report analysis backend (uploaded Veracode detailed report XML → SCA findings → Jira lookup/create with the user's own token → xlsx), its @HttpExchange Jira client (TLS unverified), the in-process Jira mock and sample XML, and the /veracode page of creed-env-matrix-design. Use for XML parsing, JQL/matching rules, Jira issue creation and tokens, the mock, or the Veracode UI.
---

# creed-resource-veracode

Plain HTTP **18097** (HTTPS with profile `ssl`), no DB, stateless. Frontend page `/veracode` in `creed-env-matrix-design`
(Vite proxy `/api/veracode` → `VITE_VERACODE_TARGET`). See [[creed-platform]] for build/run, and
[[creed-env-matrix]] for the frontend's conventions (one root ConfigProvider, no `.ant-*`, i18n keys
typed from `en-US.ts`, numeric `scroll.x` with a fixed-right column).

## Shape

- **The only source is an uploaded Veracode detailed report (XML)** (since 2026-11-05, asked for): Confluence,
  the page id and the html/csv/xlsx table parser are gone.
- `client/jira/JiraClient` — `@HttpExchange`; `config/RemoteClientsConfiguration` builds its `RestClient` from
  Boot's prototype `RestClient.Builder` (JDK HttpClient, **no redirects**, `TrustAllTls`, default
  `Authorization` from `RemoteAuth` NONE/BEARER/BASIC). Static `jiraClient(RestClient)` lets tests put the same
  proxy on another transport.
- `service/VeracodeXmlParser` (StAX) → `ReportRow`s (affected version filled in once known: root `version` or
  the upload's override) → `UploadedReportStore` → `domain/Finding.group` → `service/JiraLookupService`
  (versions, JQL search, match, create) → `VeracodeAnalysisService` (analyse / create by upload id, re-reads
  Jira per request) → `ExcelExporter` (POI).
- `mock/` — `MockJiraController` + `MockJiraStore` (project CREED, versions 2.2.1/2.3.0/2.4.0, four seeded
  issues, keys from CREED-105). `@ConditionalOnProperty veracode.mock.enabled`; the Jira URLs default to
  `http://localhost:${server.port}/mock/jira`. `sample/veracode-detailedreport.xml` + `POST /analysis/sample`
  (mock only): 9 findings, 3 exist — `VeracodeApiTest` pins them, so changing the sample changes the test.

## Rules that are easy to break

- **Every follow-up names the upload id** (`report.id`): analyse / create / export. `UploadedReportStore`
  holds parsed rows in memory (TTL, max entries) → `upload_expired` 404 after restart / on another instance.
  Over-size uploads need the unscoped `UploadLimitExceptionHandler` (multipart fails before a handler is chosen).
- **Affects Version = `detailedreport/@version`**, never `component/@version` (the library's). The override is
  stored with the upload, so create re-reads the same version. No version and no override → 422.
- **XML: StAX, DTD + external entities off, local names only.** Only
  `software_composition_analysis/vulnerable_components/component/vulnerabilities/vulnerability` is read; static
  flaws are ignored; `mitigation="true"` skipped with a warning; a non-CVE id (`SRCCLR-SID-…`) is the title.

- **Finding = (CVE or normalised title, affected version)**; id `CVE-…@2.3.0` /
  `title:srcclr sid 29587@2.3.0`. Create requests carry ids only; the server re-reads the upload + Jira under a
  per-process lock → `ALREADY_EXISTS` instead of duplicates. Don't switch to client-supplied finding data.
- **Never put an unknown version into JQL** — Jira 400s the whole query. Versions go through
  `projectVersions()` (lower-case → Jira spelling); unknown → `jiraVersion: null`, not searched, not creatable.
- **The user's Jira token** (`service/JiraToken`, header `X-Jira-Token`) is passed into every `JiraClient`
  method as an optional `Authorization` argument — null keeps the configured default header, so searches
  still work without one; create requires it (`require-user-token`, 400 `jira_token_required`). Never log it,
  never store it server-side. The page keeps it in sessionStorage (localStorage when "remember").
- **Summary = `summary-template`** (`{project} {component} {cve}`; `{project}` = the report's `app_name`, else the
  Jira key; `{jiraProject}` = the key; `{cve}` = title without a CVE). Matching
  must never depend on it: a CVE is found anywhere in a summary, separators / case ignored, not inside a
  longer id; a title by all its words, any order. JQL: CVE as a phrase, title as lower-cased plain words
  (upper-case AND/OR/NOT would be Lucene operators). The mock evaluates quoted = phrase, unquoted = words.
- **`summary ~` narrows, Java decides** (`covers`/`summaryNames`). Lucene reserved chars are replaced by
  spaces in phrases (`Jql.searchPhrase`), not escaped. The mock implements the same token semantics, so a
  substring-based "fix" in the mock would hide a real-Jira miss.
- Search pages until an **empty page or `startAt >= total`** — trusting `total` alone can spin.
- Exception handler is `@RestControllerAdvice(assignableTypes = VeracodeController.class)`: the mock must
  keep answering in Jira's own error shape, which `UpstreamException.detail` parses.
- **Jira TLS is unverified on purpose** (asked for): `TrustAllTls` = a no-op `X509ExtendedTrustManager`, which
  skips the certificate *and* the hostname check (the JDK runs the hostname check inside an extended TM; a plain
  `X509TrustManager` gets wrapped and still checks it). Per client — never the JVM-wide
  `jdk.internal.httpclient.disableHostnameVerification`. `TrustAllTlsTest` proves both directions. Profile
  `ssl` (one-way, `client-auth: none`) serves HTTPS; keep the server bundle out of `application.yml` (Boot
  opens declared bundles eagerly).
- actuator shows values (`show-values: always`); secrets are masked only by `ActuatorSanitizingConfiguration`.
  A new secret property must end in password/secret/token or it shows in `/actuator/env` in plain text.
- **`spring.config.additional-location` is a default property set in `main()` (`application()`)**, so
  `@SpringBootTest` never sees it — test it through `CreedResourceVeracodeApplication.application()`
  (`ExternalConfigDirTest`). It is not honoured from `application.yml`: Boot reads it before any config file is loaded.
- Jira Cloud: `/rest/api/2/search` is retired there (→ `/rest/api/3/search/jql`); the client is DC v2.

## Tests

`VeracodeApiTest` uploads the sample XML and runs the full chain with `@SpringBootTest` + MockMvc: a `@Primary`
Jira client bean uses a `ClientHttpRequestFactory` that builds `MockMvcClientHttpRequestFactory` **lazily**
(building MockMvc while the context still creates the bean would read a half-built context). `application-test.yml`
sets `jql-batch-size: 3`, `page-size: 2` so batching and paging are exercised. `MockJiraStore.reset()`
in `@BeforeEach`.
