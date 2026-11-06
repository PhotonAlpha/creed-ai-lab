---
name: creed-resource-veracode
description: creed-resource-veracode — Veracode SCA report analysis backend (uploaded Veracode detailed report XML → SCA findings → Jira lookup/create with the user's own token → xlsx), its @HttpExchange Jira client (issue resource only, Apache HttpClient 5, TLS unverified), the in-process Jira mock and sample XML, and the /veracode page of creed-env-matrix-design. Use for XML parsing, JQL/matching rules, Jira issue creation and tokens, the mock, or the Veracode UI.
---

# creed-resource-veracode

Plain HTTP **18097** (HTTPS with profile `ssl`), no DB, stateless. Frontend page `/veracode` in `creed-env-matrix-design`
(Vite proxy `/api/veracode` → `VITE_VERACODE_TARGET`). See [[creed-platform]] for build/run, and
[[creed-env-matrix]] for the frontend's conventions (one root ConfigProvider, no `.ant-*`, i18n keys
typed from `en-US.ts`, numeric `scroll.x` with a fixed-right column).

## Shape

- **The only source is an uploaded Veracode detailed report (XML)** (since 2026-11-05, asked for): Confluence,
  the page id and the html/csv/xlsx table parser are gone.
- `client/jira/JiraClient` — `@HttpExchange`: `POST /rest/api/2/issue`, `GET /rest/api/2/issue/createmeta/{project}/issuetypes[/{id}]`,
  and the search: `GET /rest/api/2/search` (DC) / `GET /rest/api/3/search/jql` (Cloud, `nextPageToken`). `config/RemoteClientsConfiguration` builds its `RestClient`
  from Boot's prototype `RestClient.Builder` on **Apache HttpClient 5** (`jiraConnectionManager` pool bean,
  `destroyMethod=close` + `setConnectionManagerShared`; **no redirects, no cookies**; `TrustAllTls` + `CLIENT` /
  `NoopHostnameVerifier`), **no default `Authorization`**. Static `jiraClient(RestClient)` lets tests put the same
  proxy on another transport.
- `service/VeracodeXmlParser` (StAX) → `ReportRow`s (affected version filled in once known: root `version` or
  the upload's override) → `UploadedReportStore` → `domain/Finding.group` → `service/JiraLookupService`
  (createmeta versions, batched search, match, create) → `VeracodeAnalysisService` (analyse / create by upload id, re-reads
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
- **Lookup = batched JQL search** (2026-11-06, replaced the picker + per-candidate `GET`, asked for: too many calls).
  `Jql.lookup(project, version, findings)` ORs the `summary ~` terms, `search-batch-size` findings per call, fields
  inline. Cloud has no `/rest/api/2/search` — `token.cloud()` picks `/rest/api/3/search/jql`. Still no `/myself`,
  `/project/{key}/versions`. Keep the JQL to the three clauses the mock parses (project, affectedVersion, summary ~).
- **Match = version + CVE (or title words) + every Component(s) as a whole-word run** (`componentsNamed`; only when
  `summary-template` has `{component}`). `summary ~` cannot express "these words in this order next to that CVE",
  so it narrows and Java decides — the search returns another library's issue for the same CVE by design.
- **Versions = `versions.allowedValues` of the issue type's create screen** (`createMeta()`, two paged calls).
  Never put an unknown version into JQL — Jira 400s. Unknown → `jiraVersion: null`, not searched, not creatable.
  Issue type not creatable / no *Affects Version/s* field → `UpstreamException.setup` (502 with the reason).
- **Test connection = the create metadata** (`Connection`: `tokenAccepted` = answered and not 401, `canCreate`,
  `issueType`, `versions`, `fields` = what the page asks for) — no user name, the issue resource has no "who am I".
- **The user's Jira token is the only identity** (`service/JiraToken`, header `X-Jira-Token`; since 2026-11-06, asked
  for: a shared page, each user as themselves). Every `JiraClient` method has a *required* `Authorization`
  argument, every service entry calls `token.require()` (400 `jira_token_required`). Don't reintroduce a service
  account or a fallback header — reads would then show one account's view and the reporter would be wrong. Never
  log it (Logbook masks `Authorization`), never store it server-side. The page keeps it in sessionStorage
  (localStorage when "remember"), as JSON `{deployment, token, email}` (a bare string = an old DC token).
- **Cloud vs Data Center is the token's, not the config's**: `X-Jira-User` (e-mail) present → `Basic
  base64(email:token)` and `CreateMeta.cloud` (user fields → `accountId`); absent → `Bearer`. Cloud answers an API
  token sent as Bearer with a bare 401. Create and createmeta stay on v2 (v2 takes a plain-text description; v3
  would need ADF); only Cloud's search is v3, and none of the fields it reads differ. Cloud's createmeta pages say `issueTypes` / `fields` — `JiraPage`'s `@JsonAlias`.
- **Issue type is the page's** (`X-Jira-Issue-Type` → `JiraToken.issueType`, `issueTypeOr(config)`), so every
  service entry sees it with no extra parameter. A type without Affects Version/s is a named check
  (`Problem.AFFECTS_VERSION`), never a generic failure — don't fold the checks back into one message. Sub-task
  types are filtered out (no parent to create them under).
- **Required create fields come from the page** (`service/CreateField`, `JiraLookupService.resolveFields`): Component/s
  plus every `required && !hasDefaultValue` field outside `FILLED` (project, issuetype, summary, description,
  versions, labels). Options are by id in Jira's JSON (`{"id": ...}`; a list when `schema.type = array`), users
  `{"name": ...}`; the API also takes an option's label. Checked before the first create — never let a missing field
  turn into one FAILED per finding. A required field must not fail createmeta / the connection test: reading needs
  none of them. The mock's screen (versions, components, `customfield_10100`) is pinned by `VeracodeApiTest`.
- **Summary = `summary-template`** (`{project} {component} {cve}`; `{project}` = the report's `app_name`, else the
  Jira key; `{jiraProject}` = the key; `{cve}` = title without a CVE). Matching
  must never depend on it: a CVE is found anywhere in a summary, separators / case ignored, not inside a
  longer id; a title by all its words, any order. JQL: CVE as a phrase, title as lower-cased plain words
  (upper-case AND/OR/NOT would be Lucene operators). The mock evaluates quoted = phrase, unquoted = words.
- **`summary ~` narrows, Java decides** (`covers`/`summaryNames`). Lucene reserved chars are replaced by
  spaces in phrases (`Jql.searchPhrase`), not escaped. The mock implements the same token semantics, so a
  substring-based "fix" in the mock would hide a real-Jira miss.
- createmeta pages until an **empty page, `isLast`, or `startAt >= total`** — trusting `total` alone can spin.
- **Logbook** (`LogbookHttpExecHandler`, `addExecInterceptorFirst`) logs every Jira exchange at TRACE on
  `org.zalando.logbook.Logbook`. It sees the wire, i.e. the user's Bearer token: never drop `Authorization` from
  `logbook.obfuscate.headers`. The starter's inbound servlet filter is off (`logbook.filter.enabled: false`) —
  turning it on would log `X-Jira-Token` and whole uploads.
- **HttpClient 5 has a cookie store by default — keep `disableCookieManagement()`**: one client serves every
  user's token, and a kept `JSESSIONID` would carry one user's session into the next (tokenless) request.
- Exception handler is `@RestControllerAdvice(assignableTypes = VeracodeController.class)`: the mock must
  keep answering in Jira's own error shape, which `UpstreamException.detail` parses.
- **Jira TLS is unverified on purpose** (asked for): `TrustAllTls` = a no-op `X509ExtendedTrustManager` (skips the
  certificate and JSSE's endpoint identification; a plain `X509TrustManager` gets wrapped and still checks the
  name) and HttpClient's TLS strategy policy `CLIENT` + `NoopHostnameVerifier`. Per client. `TrustAllTlsTest`
  runs the real pool/client against a self-signed cert for another name, and HttpClient's default refusing it
  (it uses `127.0.0.1`: `localhost` may resolve to `::1` first and the test server binds IPv4). Profile
  `ssl` (one-way, `client-auth: none`) serves HTTPS; keep the server bundle out of `application.yml` (Boot
  opens declared bundles eagerly).
- actuator shows values (`show-values: always`); secrets are masked only by `ActuatorSanitizingConfiguration`.
  A new secret property must end in password/secret/token or it shows in `/actuator/env` in plain text.
- **`spring.config.additional-location` is a default property set in `main()` (`application()`)**, so
  `@SpringBootTest` never sees it — test it through `CreedResourceVeracodeApplication.application()`
  (`ExternalConfigDirTest`). It is not honoured from `application.yml`: Boot reads it before any config file is loaded.

## Tests

`VeracodeApiTest` uploads the sample XML and runs the full chain with `@SpringBootTest` + MockMvc: a `@Primary`
Jira client bean uses a `ClientHttpRequestFactory` that builds `MockMvcClientHttpRequestFactory` **lazily**
(building MockMvc while the context still creates the bean would read a half-built context). `application-test.yml`
sets `page-size: 2` so createmeta paging is exercised. MockMvc bypasses Apache HttpClient — the transport is
covered by `TrustAllTlsTest` and a real-HTTP run. `JiraLookupServiceTest` stubs `JiraClient` for shapes the mock
lacks (issue type not creatable, no versions field, foreign / vanished picks). `MockJiraStore.reset()`
in `@BeforeEach`.
