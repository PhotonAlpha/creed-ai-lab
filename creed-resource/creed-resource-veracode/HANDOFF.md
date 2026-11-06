# creed-resource-veracode — handoff

**Purpose** Veracode SCA report analysis: upload a Veracode detailed report (XML), look every SCA finding
up in Jira by CVE (or advisory id) + affects version, list what exists, create what is missing (with the
user's own Jira token), export xlsx.
**Skill** `creed-resource-veracode` · shared: `creed-platform` · frontend: `../../creed-env-matrix-design` (`/veracode`)

## Run

```bash
# plain HTTP on 18097, Jira mocked in-process. No database, no PKI.
mvn -pl creed-resource/creed-resource-veracode spring-boot:run -Dspring-boot.run.workingDirectory="$PWD"

# the bundled sample report (mock only), or any detailed report XML
curl -s -XPOST localhost:18097/api/veracode/analysis/sample
curl -s -F file=@detailedreport.xml localhost:18097/api/veracode/analysis/upload
```

Against a real Jira: `CREED_VERACODE_MOCK=false`, `CREED_JIRA_URL`,
`CREED_JIRA_BROWSE_URL=https://jira.example.com/browse/{key}`, `CREED_JIRA_PROJECT`; creating uses each
user's token from the page — there is no service account (`veracode.jira.auth` / `require-user-token` removed
2026-11-06): every call, reads included, is the user's. **TLS to Jira is not verified** (certificate and hostname,
`TrustAllTls`).

HTTPS for this service (profile `ssl`, one-way TLS, `client-auth: none`) — keystore/truststore default to
`/etc/veracode/tls/*.p12` (override `CREED_VERACODE_SSL_KEYSTORE` / `_TRUSTSTORE`), password from
`CREED_VERACODE_SSL_PASSWORD` or a configtree file `veracode.ssl.password`. The Jira mock switches to
`https://localhost:${server.port}`; the client needs no trust material for it.

```bash
CREED_VERACODE_SSL_KEYSTORE=file:tmp/ocp03/pki/veracode-keystore.p12 \
CREED_VERACODE_SSL_TRUSTSTORE=file:tmp/ocp03/pki/veracode-truststore.p12 CREED_VERACODE_SSL_PASSWORD=changeit \
mvn -pl creed-resource/creed-resource-veracode spring-boot:run -Dspring-boot.run.profiles=ssl -Dspring-boot.run.workingDirectory="$PWD"
```

External config: `application.yml` / `application-<profile>.yml` in `CREED_VERACODE_CONFIG_DIR`
(default `/etc/veracode/config/`, the OpenShift ConfigMap mount) override every packaged file,
`application-ssl.yml` included; env vars still win. Read at startup only.

## API (`/api/veracode`)

| | |
|---|---|
| `GET /settings` | project, issue type, Jira base URL, mock flag, summary template — no credentials, the only call without a token |
| `POST /analysis/upload` multipart `file` + `?affectedVersion=&autoCreate=&fields=<json>` | findings with `status` EXISTS / MISSING / CREATED, `jiraVersion`, `issues`, `warnings`, `counts`, `createFields`; `report` (`id` = upload id, `appName`, `scanVersion`, `affectedVersion`, `versionOverridden`, policy, file) |
| `POST /analysis/sample` `?affectedVersion=&autoCreate=` | the same for the bundled sample XML — **mock only** (404 otherwise) |
| `POST /analysis` `{uploadId, autoCreate?, fields?}` | re-analyse an upload against Jira as it is now |
| `POST /jira/issues` `{uploadId, findingIds[], fields}` | per-id `outcome`: CREATED / ALREADY_EXISTS / NOT_IN_REPORT / VERSION_NOT_IN_JIRA / FAILED; `fields` = the create screen's other fields (`{"components":["Security"],"customfield_10100":"Production"}`) |
| `POST /jira/connection` | `{ok, projectKey, issueType, tokenAccepted, canCreate, versions, fields, status, message}` from the create metadata — a refused token is `ok:false` + Jira's status, not an error |
| `GET /analysis/export?uploadId=` | xlsx (sheets *Findings*, *Source*) from a **fresh** analysis; *Severity* filled by level (`ExcelExporter.SEVERITY_FILL`, the page's tag hues: Very High red … Very Low blue, Informational grey) |

Every endpoint but `/settings` needs the user's Jira token in `X-Jira-Token` — no fallback. Jira Cloud adds the
Atlassian e-mail in `X-Jira-User` (→ Basic), Data Center sends the PAT alone (→ Bearer); the page asks which
(2026-11-06), both REST API v2. `veracode.jira.cloud` is gone: the e-mail decides, `/settings.cloud` only
preselects the dialog (`*.atlassian.net`). Tests pin `project-key: CREED` in `application-test.yml` because
`application.yml` now points at a real Cloud site (and `issue-type: Bug`, since it says Task).
Issue type per user (2026-11-06): `X-Jira-Issue-Type`, default `issue-type`; `/jira/connection` reports
`problem` + `issueTypes[{name, affectsVersion, versions}]` instead of one "cannot create". The mock's Task has no
Affects Version/s (like the Cloud Scrum project that prompted it) and refuses `versions` on create.
Issues in the
response carry `status`, `priority`, `assignee` (null = unassigned) for the page's filters.

Errors: `{error, message, fields?, time}` — 400 `unsupported_file` (not XML) / `bad_request` /
`validation_failed` / `jira_token_required`, 422 `report_unreadable` (XML but not a `<detailedreport>`, or
no `version` and no override), 404 `upload_expired`, 413 `file_too_large` (`CREED_VERACODE_MAX_UPLOAD`,
default 50MB), 502 `jira_error`.

## Current state

2026-11-06 (latest): **every Jira call is the page user's** — `RemoteAuth`, `veracode.jira.auth` and
`require-user-token` removed, `JiraClient`'s `Authorization` header required, no default header; no token → 400
`jira_token_required` on every endpoint but `/settings` (the page opens its token dialog). **Required create fields
come from the page**: `createMeta` turns Component/s and every required field without a Jira default (other than the
six the service sets) into `CreateField`s (`OPTIONS` with Jira's options / `USER` / `NUMBER` / `TEXT`, `multiple`,
`defaults` = Jira's default, else a required field's only option) — returned as `createFields` (analysis) and
`fields` (connection test); create / `autoCreate` take `fields` and `resolveFields` checks them before the first
create (`jira_fields_required`, `bad_request`). A required field no longer fails the connection test or an analysis
(reads only). The mock now requires a Bearer everywhere and, like the Jira this was written against, Affects
Version/s + Component/s + a required custom select `customfield_10100` "Environment Found". Page: a "Jira fields"
bar (selects / inputs, defaults preselected), create disabled until required fields are filled. **44 tests pass**;
over HTTP on :18197: no token → 400, connection lists both fields, create without fields → `jira_fields_required`,
with them → created with reporter = the token's user. The page typechecks; not seen in a browser.

2026-11-06 (later the same day superseded for lookups by the batched search, see Gotchas): **Jira calls are the issue resource only** (`/rest/api/2/issue/**`, asked for) and the RestClient runs on
**Apache HttpClient 5** (`httpclient5`, Boot-managed; pool bean closed by Spring, no redirects, **no cookie store**).
Lookup = `GET /issue/picker` per finding (`currentJQL` = project + `affectedVersion = "v"` + `summary ~`) + `GET
/issue/{key}` per candidate (read once per analysis; another project or 404 skipped); versions and the connection test =
`GET /issue/createmeta/{project}/issuetypes[/{id}]` (paged, `page-size`). Removed: `/search`, `/myself`,
`/project/{key}/versions`, `jql-batch-size`; new `max-connections`. The connection result has no user any more
(`tokenAccepted`, `canCreate`, `issueType`, `versions`). A refused token now fails a create request as a whole (502,
on the create metadata) instead of per finding. **41 tests pass**; over real HTTP on :18197 (Apache transport): test
connection ok / 401, the example XML created 8 and found them again, every outbound URI under `/rest/api/2/issue`.
Not run against a real Jira.
Same day, later: **Logbook** on the Jira client (`logbook-spring-boot-starter` + `logbook-httpclient5` 4.2.0,
`LogbookHttpExecHandler` first in the HC5 exec chain): request + response with headers and body (`max-body-size`
4096) at TRACE on `org.zalando.logbook.Logbook`; `Authorization` / cookies masked (checked: the token never appears
in the log); inbound filter off. Knobs: `CREED_VERACODE_LOGBOOK_LEVEL` (`OFF` silences), `_STYLE`
(http/splunk/json/curl), `_MAX_BODY`.

2026-11-05 (latest): an example report for testing Jira creation by hand, `.support/veracode/veracode-detailedreport-example.xml`
(app creed-shop, 2.4.0: 8 findings, all missing in the mock Jira; verified: all 8 created, then found again). A local
DefectDojo is in `.support/defectdojo` for importing the same XML in its UI - this service does **not** call DefectDojo
(an integration was built and rolled back on request). The bundled sample now also satisfies DefectDojo's Veracode
parser (`last_update_time`, `recommendations/para`, `mitigations/mitigation/@date`) - checked by importing it there.

2026-11-05 (later): **the report is a Veracode detailed report XML, uploaded** — Confluence removed (client,
mock page, page id, `confluence.*` config), the table parser (html/csv/xlsx) removed with jsoup. `VeracodeXmlParser`
(StAX, SCA section only, mitigated skipped); Affects Version = root `version` or the upload's `affectedVersion`;
`{project}` = `app_name`; `POST /analysis/sample` (mock only) analyses `sample/veracode-detailedreport.xml`
(9 findings: 3 exist, 6 missing). **Jira TLS unverified** (`TrustAllTls`, `ssl-bundle` removed). **38 tests
pass** after `clean` (incl. `TrustAllTlsTest`: self-signed cert for another host accepted, refused by the JDK
default). Not run against a real Jira or a real Veracode export yet; the page was not seen in a browser.

2026-11-05: **user's Jira token** (page dialog, test connection, `X-Jira-Token`; create requires it; the mock
needs a Bearer on `/myself` + create, refuses the token `invalid`, records the reporter), **title template**
`{project} {component} {cve}` (replaces `summary-prefix`), **loose title match** (CVE with any separators;
title words in any order; JQL searches title words unquoted, lower-cased), issue `priority` / `assignee`
(mock seeds both). Page: Jira status / priority / assignee / version filters, fuzzy keyword search over
issue titles. **41 tests pass**; endpoints checked over real HTTP on a second instance (:18197). The page
typechecks and builds but was **not seen in a browser** — the extension could not attach to any page of
the dev server (other routes neither).

Complete against the mocks. **38 tests pass** (parser, matching/JQL, actuator masking, external config dir, and the whole chain over MockMvc
through the real `@HttpExchange` proxies). Verified over real HTTP on :18097: the sample page gives
10 findings — 3 in Jira, 7 missing, 1 with a version Jira lacks; create returned `CREED-105`; the
export downloads. Upload (2026-10-16): the sample page saved as `.html`, the module's own export
re-uploaded as `.xlsx`, and a `.csv` all give the same answer as the page; 11 MB → 413; multipart passes
the Vite proxy. **Never run against a real Confluence/Jira yet.** The frontend page typechecks;
it was not checked in a browser (the browser extension could not attach to any page that session).

## Landmines

- **An upload lives in this process's memory** (`UploadedReportStore`, `veracode.upload.ttl` 2h,
  `max-entries` 50, oldest dropped). Restart or a second instance → `upload_expired`; the page tells the
  user to upload again. The parsed rows are kept, not the bytes.
- **`MaxUploadSizeExceededException` needs the unscoped `UploadLimitExceptionHandler`** — multipart is
  resolved before a handler is chosen, so the controller-scoped advice never sees it (whitelabel 500).
- **The Affects Version is the report's root `version`, never `component/@version`** — the latter is the
  library's version (it goes into the component label `log4j-core 2.14.1`). A scan named like
  `16 Oct 2026 Static` is no Jira version: the page's override, sent with the upload, is stored with it.
- **The XML is streamed (StAX), DTD and external entities off.** A detailed report also carries every static
  flaw; only `software_composition_analysis/vulnerable_components` is read, elements by local name (any
  namespace version). Static flaws are not findings here. Mitigated (`mitigation="true"`) vulnerabilities
  are skipped with a warning.
- **No CVE = an SCA advisory (`SRCCLR-SID-…`)**: its id is the title, so it is keyed `title:srcclr sid …`
  and found again by those words in a summary.

- **Lookup is a batched JQL search** (2026-11-06, asked for: the picker was one call per finding plus a read per
  candidate, and its `hs` history section ignored `currentJQL`). `Jql.lookup` ORs the findings' `summary ~` terms,
  `search-batch-size` (50) per call; DC `/rest/api/2/search`, Cloud `/rest/api/3/search/jql` (v2 search retired there).
  A match also needs every Component(s) in the summary (`componentsNamed`) while the template has `{component}`.
- **`affectedVersion = "x"` with a version the project lacks is a 400**, not "no match". Versions come from the
  issue type's create screen (`versions.allowedValues`; archived ones are absent) and only those are searched; the
  rest are `jiraVersion: null` and cannot be created. No *Affects Version/s* on the create screen, or the issue
  type not creatable → 502 with the reason (`UpstreamException.setup`).
- **`summary ~` is Lucene full text, not substring.** Phrases are tokenised and stop words dropped, so
  the query only narrows candidates; `JiraLookupService.covers` decides on the returned summary (CVE with any
  separators but not inside a longer id — `CVE-2021-4422` must not match `CVE-2021-44228`; title words, any order).
- **The finding is `(CVE or title, affected version)`, not a vulnerability element** — two libraries with one
  CVE are one Jira issue (both components in its title).
- **Create re-reads the upload + Jira under a lock**, so a double click returns `ALREADY_EXISTS`. The lock is
  per process; two instances could still duplicate (Jira has no unique key to lean on).
- **Logbook logs the wire, so it sees the user's token**: `logbook.obfuscate.headers` must keep `Authorization`.
  It writes synchronously from the exec chain (log I/O counts in the Jira call's time); `logbook.filter.enabled:
  false` keeps uploads and `X-Jira-Token` out of it. The picker's `currentJQL` shows URL-decoded in the log.
- **Apache HttpClient 5 keeps cookies by default — turned off.** One client serves every user's token and Jira
  answers with `JSESSIONID`; kept, the next user's request (or a tokenless one) would run in the previous session.
- **The Jira client verifies neither certificate nor hostname** (asked for, 2026-11-05; `ssl-bundle` removed).
  `TrustAllTls` is a no-op `X509ExtendedTrustManager` (JSSE's endpoint identification runs inside the extended
  one, so it is skipped too; a plain `X509TrustManager` would be wrapped and still check the name), and the TLS
  strategy has policy `CLIENT` + `NoopHostnameVerifier` for HttpClient's own check.
  Anyone on the path can read the user's token.
- **The server bundle is declared only in `application-ssl.yml`**: Boot opens every declared bundle at startup,
  so declaring it in `application.yml` breaks the plain-HTTP run. A missing password is not reported as an
  unresolvable placeholder — the literal `${veracode.ssl.password}` becomes the password and startup fails
  with the wrong-password `BadPaddingException`.
- **actuator `env`/`configprops` run with `show-values: always`**, which masks nothing by itself;
  `ActuatorSanitizingConfiguration` masks keys ending in password/secret/token (case-insensitive) and
  containing credentials. Not `ifLikelySensitive()` — it would also mask `project-key` and every URL.
- Redirects are not followed — a wrong Data Center base URL shows as its 302, not as "HTML is not JSON".
- Plain HTTP by default and **no auth on this service** (like the siblings' `permitAll`) — it relays the
  user's token and may hold a configured one. Do not expose it beyond localhost as is; over anything but
  localhost the `X-Jira-Token` header needs HTTPS (profile `ssl`).
- **A token header is only sent when non-null** (`@RequestHeader(required = false)` on `JiraClient`): a null
  argument leaves the client's default `Authorization`; a sent one replaces it. Don't make it required —
  analysis without a user token relies on the default.
- **Changing `summary-template` loses no issues**: lookup never reads the template, only the CVE / title
  words. Removing `{cve}` from it would, though — then a created issue cannot be found again.

## Open items

- Real Jira trial, and a real Veracode export (the sample follows the export schema 1.x from memory of its
  shape, not from a downloaded file); Jira Cloud search endpoint if that is the target.
- Fetching the report from the Veracode API (HMAC-signed `getdetailedreport.do`) instead of an upload.
- Priority/assignee/components on created issues (project-specific names — not guessed).
- Not behind `creed-gateway-proxy` (only `/api/env-matrix/**` is proxied there); deploying the page
  needs a route for `/api/veracode/**`.
