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

- **Conditional formatting never goes through JXLS** (2026-11-13, an export that never came back): JXLS copies it cell
  by cell (`PoiCellData.writeToCell` -> `updateConditionalFormatting`, no switch to turn it off) and each copy is checked
  against all before it - 40 rules x 400 rows = 15 s, real reports minutes. `ReportTemplateService.fill` uses
  `TemplateWorkbook.forFill` (markup + every sheet-with-a-data-row's `<conditionalFormatting>` taken off as XML) and
  `withConditionalFormatting` (put back, then per list bottom-up POI's `XSSFRowShifter.updateConditionalFormatting` by
  `n - 1` from the row below + ranges ending on the data row stretched to its last copy). Pinned against JXLS's own
  output (same cells per rule, n = 0/1/3/20) and by `ConditionalFormattingFillTest`. JXLS's row layout it relies on: n
  items = n rows, below moves `n - 1`, none removes the row. A missing `jx:each` does not hang (cells left empty).
- **Jira / Confluence never wait out a long Retry-After** (2026-11-13): HttpClient 5's default retry re-sends a 429 /
  503 once after `Retry-After` however long - one rate-limited call held the request for as long as Jira asked.
  `RemoteClientsConfiguration.BoundedRetry` retries only up to `veracode.http.max-retry-wait` (5s); `UpstreamException`
  names a 429 with its Retry-After. `BoundedRetryTest` runs it against a local server.
- **The JXLS area is the content, never POI's used range** (2026-11-13, an export hung): `getLastRowNum` /
  `getLastCellNum` count every formatted blank cell - whole-row formatting reaches XFD, and JXLS copies each one per
  data row. `TemplateWorkbook.extent` (values, formulas, merges; fills too for the grid) bounds both. Datasets: `sca`,
  `sast`, `scaStats`, `sastStats` (`TemplateFields.isList`), `summary`. `.xlsm` goes through unchanged (POI keeps
  `vbaProject.bin`); the export's extension and content type follow the upload's file name.

- **Labelling an existing issue rewrites its description** (2026-11-13, asked for): `addLabels` sends labels +
  `fields.description` in one PUT. It is read first (`JiraClient.issue`) and rewritten **per application**,
  SCA and SAST alike (asked for: not appended; an application not in this analysis must keep its part - the analysis
  may hold only the newest application). SCA `JiraDescription.mergeSca`: tables keyed by their `|Veracode
  application|` row. SAST `mergeSast`: head blocks by `<name>: `, flaw rows by their first cell, `Source (<name>): `
  lines - so never drop the application from a SAST source line; an untagged (pre-2026-11-13) one is not kept.
  Applications compare by display name (`displayName` strips a `[name|url]` link). Other text is dropped. The mock has
  `GET /issue/{key}` and its PUT honours `fields.description`.

- **Create version = `fields.versions`** (2026-11-13): `createVersion(meta, fields, lookup)`; `resolveFields` lets the
  `versions` key through without treating it as a screen field. The lookup's `jiraVersions` stay optional / several.

- **Report templates store the upload and the mapping, never the JXLS file** (2026-11-12): the markup is written at
  each export, so fixing `TemplateWorkbook.withJxlsMarkup` fixes every template. `TemplateFields` keys are the JXLS
  property names (`${r.cveId}`) and live in saved mappings: renaming one breaks them - add, don't rename. A data
  row's `jx:each` sits on column A and spans to the sheet's last used column, so the whole row (unbound cells and
  styles too) repeats. The rows are plain maps with every key present (`""` for none) - a missing key would leave
  the `${...}` text in the cell. The DB is the module's first: H2 in PostgreSQL mode by default (one migration
  folder for both), in-memory in `application-test.yml`.

- **Filing a closed issue again is single and confirmed** (2026-11-11, asked for): `recreateClosed` is honoured only
  with exactly one id and only while every matched issue is in `veracode.jira.closed-statuses`; it is re-checked
  server-side under `createLock`. Never thread it into bulk create or `autoCreate` - those must keep treating a closed
  issue as filed, or every re-analysis duplicates the project's history.

- **Only create screens are cached, never issues** (2026-11-12): `meta-cache-ttl` keeps Jira's **raw createmeta reads**
  in a Caffeine cache (bean in `config/JiraMetaCacheConfiguration`, injected; `expireAfterWrite`, max 500; concurrent
  misses load once; TTL 0 = every entry expires on write): `<sha256 of the authorization header>|types|<project>` and
  `|fields|<project>|<issueTypeId>` - **not per SCA / SAST** (2026-11-13, asked for: SAST on SCA's project and type read
  the same screen a second time, in parallel). `CreateMeta` is rebuilt from them each time (cheap). `connection()` drops
  that token's entries and then fills them as it reads, so the analysis after a test reads no createmeta. `cached()` is
  only ever called around a raw read - nesting it (as the old per-kind `CreateMeta` wrapper would) is Caffeine's
  recursive-load error. Lookups and creates must
  keep reading Jira every time - the duplicate-free create path depends on it. `skipSast` must stay ignored with
  `autoCreate` (auto-create files SAST too).

- **Country is a lookup filter, not a create value** (2026-11-11): `countries` only adds `cf[N] in (...)` to the SCA JQL
  (`Jql.fieldRef`: a custom field by number - names can repeat or be translated). Creating takes Country from `fields`
  like any create-screen field. `covers()` does not re-check it (the search reads no Country), so the mock's `matching`
  must honour the clause - it parses `cf[10300] in (...)` literally; another field id needs the mock pattern changed.

- **Confluence downloads: ids in, URLs never.** `POST /analysis/confluence` takes attachment ids; the server reads
  **each id** (`ConfluenceClient.attachment`, `expand=version,container`; `attachmentOf` checks `type` = attachment and
  `container.id` = the page - one 400 for every other case) and downloads its own `_links.download` under
  `veracode.confluence.base-url`. **Never list the page to download** (2026-11-13: 2500+ attachments = dozens of listing
  calls per analysis, Confluence 429) - `VeracodeApiTest` asserts the mock's `attachmentListings()` stays 0. Redirects are followed in
  `ConfluenceService.fetch` (the client has redirects off, like Jira's) and the token goes only to URLs **under the
  base URL** (`withinBase`: origin + path prefix) — Cloud redirects to its media service, which must not get it. The
  mock's media endpoint lives outside `/mock/confluence` and 403s a token, so the test catches a leak.

- **Tracing = Brave, printed by Boot's default correlation pattern** (2026-11-13, asked for): Boot 4 needs both
  `spring-boot-micrometer-tracing-brave` (the auto-configuration) and `micrometer-tracing-bridge-brave` (optional in it);
  one alone = no Tracer, empty MDC. No exporter on purpose. Don't set `logging.pattern.correlation` (`[%X{traceId}...]`
  prints `[,]` on every non-request line; Boot's pads blank). **Every background thread must take the context along**:
  `Concurrently.inContext` = `ContextSnapshotFactory.captureAll().wrap(call)` - Logbook logs the Jira exchange on the
  thread that makes it. `ConcurrentlyTest` pins it with a registered ThreadLocal (no tracer needed).
  **`X-Trace-Id` response header** = `web/TraceIdResponseFilter`, `@Order(HIGHEST_PRECEDENCE + 2)`: it must run after
  Boot's `ServerHttpObservationFilter` (`+ 1`, starts the span - before it `currentSpan()` is null) and set the header
  before the chain (one added after the body is written is dropped). `TraceIdResponseFilterTest` uses
  `@AutoConfigureMockMvc` (filters applied); `VeracodeApiTest`'s MockMvc does not run filters.
- **Page reads are shared while in flight** (2026-11-13, Jira answered 429): StrictMode's dev double mount sent every
  load effect twice (connection test, settings, restore-analysis, Confluence lists). `api/veracode.ts` `read()` /
  `shared()` hands an identical request in flight (method + path + headers + body) the first one's promise; the entry
  is dropped on settle - not a cache. A new read-only call goes through `read`; anything that creates or writes
  (`createIssues`, `analyze` with `autoCreate`, uploads, saves) must not - two clicks are two requests.
- **Every follow-up names the upload id** (`report.id`): analyse / create / export. `UploadedReportStore`
  holds parsed rows in memory (TTL, max entries) → `upload_expired` 404 after restart / on another instance.
  Over-size uploads need the unscoped `UploadLimitExceptionHandler` (multipart fails before a handler is chosen).
- **Affects Version = `detailedreport/@version`**, never `component/@version` (the library's). The override is
  stored with the upload, so create re-reads the same version. No version and no override → 422.
- **XML: StAX, DTD + external entities off, local names only.** Only
  `software_composition_analysis/vulnerable_components/component/vulnerabilities/vulnerability` is read; static
  flaws are ignored; `mitigation="true"` skipped with a warning; a non-CVE id (`SRCCLR-SID-…`) is the title.

- **Finding = (application, `cve_id` or Unknown+`cwe_id`, component `file_name`)** (2026-11-06, asked for) — id
  `creed-platform|CVE-2021-44228@log4j-core-2.14.1.jar`; `Finding.issueKey` is the same without the application: one
  Jira issue per CVE + jar across applications, each application's label added (`addApplicationLabel`, outcome
  `LABELED`, `needsLabel` on the view) instead of a duplicate. `Filing` in `VeracodeAnalysisService.file` carries
  that within one request: the issue created / labelled per `issueKey` (its labels as they are now - the analysis'
  `matches` are stale after the first update), and labelling takes along every other finding *of the request* sharing
  the issue - one GET + one PUT per issue, each of those findings answered `LABELED` from it (2026-11-13, perf). Never
  label a finding the request did not name. Several files per upload (`UploadedReportStore.ReportFile`). Create requests carry ids only; the server re-reads the upload + Jira under a
  per-process lock → `ALREADY_EXISTS` instead of duplicates. Don't switch to client-supplied finding data.
- **Lookup = batched JQL search** (2026-11-06, replaced the picker + per-candidate `GET`, asked for: too many calls).
  `Jql.lookup(project, versions, findings)` ORs the `summary ~` terms (a CVE as the quoted id itself,
  `"\"CVE-2021-44228\""` — asked for; unquoted, its `-` would be Lucene's NOT), `search-batch-size` findings per call, fields
  inline. The batches run `search-concurrency` (4) at a time and the SAST half (screen + searches) beside the SCA
  half (`Concurrently`, virtual threads; 2026-11-13, perf): `max-connections` must stay above twice that, and a test
  fake recording calls must be thread-safe and not assert their order. Matching builds each finding's CVE pattern
  once per batch (`Wanted`) and normalises a summary once (`Summary`) - don't go back to compiling per issue x finding.
  Cloud has no `/rest/api/2/search` — `token.cloud()` picks `/rest/api/3/search/jql`. Still no `/myself`,
  `/project/{key}/versions`. Keep the JQL to the three clauses the mock parses (project, affectedVersion, summary ~).
- **Match = a chosen Jira version (any, if none chosen) + `cve_id` (Unknown: "Unknown" + CWE) + the `file_name` as a
  whole-word run** (`named`; only when `summary-template` has `{file}`, components likewise with `{component}`).
  `{project}` is `project-name`, never the Veracode application — tests pin `project-name: creed`. `summary ~` cannot express "these words in this order next to that CVE",
  so it narrows and Java decides — the search returns another library's issue for the same CVE by design.
- **Versions = `versions.allowedValues` of the issue type's create screen** (`createMeta()`, two paged calls).
  They are the page's version list. **The report's version is display only** (2026-11-06, asked for): Jira is
  searched by the page's `jiraVersions` (`affectedVersion in (...)`, none = no version clause) and created under
  the one chosen (`createVersion`; none / several → 400 `jira_version_required`, also for `autoCreate`). Names are
  resolved through `selectedVersions` — an unknown one is a 400 before any JQL (Jira would 400 the search).
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
- **Logbook logs JSON bodies only** (2026-11-13, asked for, as creed-simple-metrics): `config/JsonBodyOnlyStrategy`
  (bean in `LogbookConfiguration`, so Logbook's own `Strategy` and the `logbook.strategy` property back off) leaves out -
  and never buffers - a body whose type is not in `veracode.logbook.allowed-content-types` (`application/json`): the
  Confluence download (zip / XML) was printed byte for byte, Logbook's default replacer covers neither type. The exchange
  is still logged (unlike simple-metrics' request gate) so a failed attachment upload stays visible. A request's type
  comes from its entity: Logbook runs before HttpClient's protocol step adds the header. `JsonBodyOnlyStrategyTest`.
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
- **Confluence export = storage-format XHTML from Thymeleaf** (`ConfluenceExportService`, template
  `templates/veracode/confluence-report.html`): every element closed, only XML entities — the test parses the output as
  XML. Rendered with Boot's `SpringTemplateEngine` (SpEL); a plain `TemplateEngine` would need OGNL. Written with the
  user's Confluence token, under the parent's space (the parent is read first); never a URL from the request.
  Attribute names with a prefix (`ri:filename`) cannot go through `th:attr` ("Could not parse as assignation
  sequence") - write `th:ri:filename`. The report files are attached after the page is created
  (`ConfluenceService.attach`: multipart, `X-Atlassian-Token: nocheck`); build the parts as a `LinkedMultiValueMap` of
  `HttpEntity` - `MultipartBodyBuilder` needs Reactive Streams, absent here (`NoClassDefFoundError: Publisher`).
- **Application label = the application alias** (`domain/ApplicationAlias`: `appName` → `alias`, `repositoryUrl`, matched
  case-insensitively; none = the `app_name`) since 2026-11-10 - the `application-label` regex is gone. **Since 2026-11-13
  (asked for) the aliases are a table, not configuration**: `application_alias` (Flyway `V2`, key = `app_name`
  trimmed + lower-cased: H2 has no expression index), edited on `/veracode-templates`' *Application aliases* tab, saved
  whole (`PUT /api/veracode/application-aliases`, validated: app name required and once, an alias or a link, http(s)).
  Code reads them through the `ApplicationAliases` interface (`ApplicationAliases.of(list)` in unit tests); the bean
  is `alias/ApplicationAliasService`, which holds the table in memory and reloads it after a save - load and save under
  one lock, so a reader can never put the old list back. `db/defaults/V2_1` seeds what application.yml held (tests
  leave the location out). An old `veracode.applications` in a configuration is imported at start (app names the
  table lacks) with a WARN - application-test.yml keeps one on purpose, it is the import's test. The same name (linked)
  is shown on the page, in the Jira description and as the Confluence *Repository*. Changing an alias changes the label,
  so existing issues show "label missing" until labelled again.
- **Uploaded report files are attached to every created issue** (SCA and SAST, `JiraLookupService.attach`, multipart
  `file` parts + `X-Atlassian-Token: no-check`, `MultipartFiles` shared with the Confluence attach); a file downloaded
  from Confluence (`SourceFile.url`) is linked from the description's *Source* instead, followed by its page
  (`SourceFile.pageTitle` / `pageUrl` → `ReportView.sourcePage*`, `on page [title|url]`). `ReportFile.source` is the
  index of the received file a report came in (a zip holds several). Attach failure = the result's message, the issue
  stays. `VeracodeApiTest.ClientsOverMockMvc.overMockMvc` turns multipart into MockMvc `multipart()` for both clients.
- **SAST labels follow SCA's** (`JiraLookupService.sastLabels(issue)` / `missingSastLabels` / `addSastLabels`): every
  application of the issue gets its label (mapped name); an existing issue missing one is labelled (`LABELED`), never
  filed twice. `addLabels` is the shared update (`update.labels.add` per label) - SCA's `addApplicationLabel` uses it.
- **SAST issues = CWE + flaw `categoryname`** (`domain/SastIssue`, open flaws only — fixed = `remediation_status="Fixed"`,
  mitigated = `mitigation_status="accepted"`, never filed). Summary `{project} - CWE-89 - {category}` (asked for) is the
  identity: searched **without** a version clause and matched by `coversSast` (CWE and category as whole-word runs) —
  keep both placeholders in `sast.summary-template` or nothing will ever match. Own target (`veracode.jira.sast`:
  project-key, issue-type, project-name, labels, defaults; blank → SCA's) via `JiraLookupService.Target`; SAST never
  uses the token's `X-Jira-Issue-Type` (that is SCA's choice). SAST Jira failures go to `sast.error`, never fail the
  analysis. Not in `autoCreate`, no application labels. The `<cwe>`'s name is tracked on the element stack (reset at
  `</cwe>`).
- **SCA statistics = `ScaSummary` per report** (`ReportView.sca`, page table + xlsx *SCA summary*): stated
  attributes of `<software_composition_analysis>` are `Integer`/`Boolean`, `null` when absent — never default them
  to 0, which would claim SCA found nothing. Counted values are the report's own (mitigated excluded, before
  `Finding.group`), so `vulnerabilities` ≥ the finding count. `bySeverity` always lists all six levels, Very High
  first, so every report has the same columns. An analysis cached in the page before this has `sca` absent → the
  table skips it.
- **No "Fixed version" field (removed 2026-11-06)**: the detailed report XML's SCA `component` / `vulnerability`
  carry no fix / safe version, so the column was always empty since the XML became the only source. Bringing it
  back needs another source (e.g. Veracode's SCA REST API), not a parser tweak.

## Tests

`VeracodeApiTest` uploads the sample XML and runs the full chain with `@SpringBootTest` + MockMvc: a `@Primary`
Jira client bean uses a `ClientHttpRequestFactory` that builds `MockMvcClientHttpRequestFactory` **lazily**
(building MockMvc while the context still creates the bean would read a half-built context). `application-test.yml`
sets `page-size: 2` so createmeta paging is exercised. MockMvc bypasses Apache HttpClient — the transport is
covered by `TrustAllTlsTest` and a real-HTTP run. `JiraLookupServiceTest` stubs `JiraClient` for shapes the mock
lacks (issue type not creatable, no versions field, foreign / vanished picks). `MockJiraStore.reset()`
in `@BeforeEach`.
