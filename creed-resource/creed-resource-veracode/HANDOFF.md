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
| `POST /confluence/export` `{uploadId, parentPageId, project?, version?, jiraVersions}` + both tokens | a fresh analysis as a child page of `parentPageId` (its space), the report files attached and linked from its last line: `{id, title, spaceKey, parentId, url, attachments, attachmentError}`; 404 `confluence_page_not_found` for a parent the user cannot see |
| `GET /analysis/export?uploadId=` | xlsx (sheets *Findings*, *SCA summary*, *SAST flaws*, *SAST summary*, *Source*) from a **fresh** analysis; *Severity* filled by level (`ExcelExporter.SEVERITY_FILL`, the page's tag hues: Very High red … Very Low blue, Informational grey) |

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

2026-11-11 (latest, asked for): **closed issues may be filed again** - `veracode.jira.closed-statuses` (`Closed, Done`);
`FindingView` / `SastIssueView.recreatable` = every issue found is in one; `CreateIssuesRequest` /
`CreateSastIssuesRequest.recreateClosed` files that **one** id anyway (more ids = 400, re-checked against Jira under the
create lock). Page: red *Re-create* on such a row, `modal.confirm` listing the closed issues
(`pages/Veracode/recreateClosed.tsx`). **81 tests pass**
(`aFindingWhoseIssuesAreAllClosedMayBeFiledAgainOneAtATime`; SAST shares the code path, no test of its own - the mock
has no closed SAST issue).

2026-11-10 ( `sample/changes.jpg`, asked for): **application mapping** `veracode.applications` (`app-name` →
`name`, `url`; `config/ApplicationProperties`) replaces `veracode.jira.application-label` (removed): the mapped name
(else the `app_name`) is the Jira label, the Jira description's linked *Veracode application*, the Confluence export's
linked *Repository*, and `AnalysisResponse.applications` for the page. **Issue source**: `ReportView.sourceName` /
`sourceUrl` (the file a report came in; the Confluence attachment URL when downloaded) + `sourcePageTitle` / `sourcePageUrl` (the page holding it); the
description's *Source* links a downloaded file and its page (`on page [title|url]`), an uploaded one is attached to every issue created from it (`JiraClient.addAttachments`, mock
`POST /mock/jira/rest/api/2/issue/{key}/attachments`, `MockJiraStore.attachments`); attach failure = the result's
message, never a failed create. **Confluence export** rows sorted by repository (first mapped name), SAST *Details* =
`SastIssueView.byApplication` (`<name>: <sast project-name> - <category>` / `CWE 117 <cwe name>` /
`CWE 117 (Flaw IDs: …)`, display only). Mock Confluence page 98765: the zip and PDF are now dated
(`*_09_Nov_2026.*`). Page: Confluence attachment filter (dated `dd_MMM_yyyy` + `.zip` by default, newest first, max
50; a report-date dropdown narrows to one date, the newest preselected), full-screen `Spin` while an upload / Confluence download is processed, SAST *Jira summary* column = the
per-application blocks (*Open flaws* column merged in). **80 tests pass**; over real HTTP on :18197 (mock, Apache
transport): mapped label + linked description + the uploaded XML attached; Confluence zip → `sourceUrl`; export rows
sorted, SAST blocks. Page typechecks; **not seen in a browser**.

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
(StAX, SCA section only, mitigated skipped - SAST flaws are read since 2026-11-08, filed per CWE + category); Affects Version = root `version` or the upload's `affectedVersion`;
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
  user to upload again. The parsed rows are kept, and the files as received **gzipped** (for the Confluence export's
  attachments - a few MB per upload rather than the 50MB multipart limit).
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
- **Versions are the page's, not the report's** (2026-11-06, asked for): `jiraVersions` on every request, `affectedVersion
  in (...)` (none = any), create files under the one chosen (else 400 `jira_version_required`). `FindingView.jiraVersion`,
  `counts.versionNotInJira` and outcome `VERSION_NOT_IN_JIRA` are gone; the response has `selectedVersions` /
  `createVersion`, the connection `versionNames`. The page caches its state in sessionStorage (`pageCache`).
- **Findings per application + CVE + jar** (2026-11-06, asked for): summary `{project} - {cve}|{cwe} - {file}`
  (`project-name`), labels + `project-name` + application label (`application-label` regex), several XML files per
  upload (`reports`, `uploadId`), a CVE + jar another application already filed is labelled (`LABELED`), not
  duplicated. The mock answers `PUT /rest/api/2/issue/{key}` labels; seeds use the new summary and carry the sample
  application's label. A report without `version` is no longer refused (the version is display only).
- **Export to Confluence** (2026-11-08, asked for): `ConfluenceExportService` renders
  `templates/veracode/confluence-report.html` (Thymeleaf, `spring-boot-starter-thymeleaf` - used as a string renderer,
  no MVC views) to storage-format XHTML in the layout of `sample/confluence-template.png` and
  `ConfluenceService.createChildPage` POSTs `/rest/api/content` (parent read first for its space). Title
  `veracode.confluence.export-title` (`{project} - {version} - Reports (SAST,SCA) - {timestamp}`, `export-timestamp`
  `yyyy-MM-dd HH-mm-ss`, `export-time-zone` blank = server's); `{project}` / `{version}` = the reports' application(s) /
  version(s) unless the request names them. Interpretation of the template image (it is an Excel mock with a
  misaligned SAST row): the summary row "SAST (n)" counts **open flaws**, the SAST table has one Repository column
  (Veracode applications), Status = Jira status or "Not in Jira", Remarks empty. `FindingView.summary` (the summary a
  finding is filed under) is new, so a missing SCA finding's Description is what it would be filed as. Mock: `POST
  /mock/confluence/rest/api/content` keeps pages in memory (duplicate title in a space = 400, `?expand=body.storage`
  returns the body). Test: `VeracodeApiTest.exportsTheAnalysisAsAConfluenceChildPage` (title pattern, storage parses as
  XML, rows, overrides, 404, token). Checked in headless Chrome: dialog → created page, rendered body screenshot.
- **Report files attached to the exported page** (2026-11-09, asked for): the files the analysis read - as uploaded, or
  as downloaded from a Confluence page; a zip stays the zip - are kept with the upload (`UploadedReportStore.SourceFile`,
  gzipped) and, once the page exists, POSTed in one multipart `child/attachment` (`X-Atlassian-Token: nocheck`, else
  Confluence's XSRF 403). The last line "Veracode report(s): …" links each by name (`<ac:link><ri:attachment
  ri:filename/>`). Same-named files get ` (2)` (Confluence would make the second a new version of the first). A failed
  attach does not fail the export: the page exists, the answer carries `attachmentError` and the dialog shows it.
  `VeracodeApiTest.ClientsOverMockMvc` turns a multipart request into MockMvc's `multipart()` (MockMvcClientHttpRequestFactory
  passes raw bytes, which have no parts); checked against a live instance too (Tomcat multipart, Logbook logs `<multipart>`).
- **SAST labels as SCA's** (2026-11-09, asked for): a created SAST issue carries `sast.labels`, `sast.project-name` and
  the application label (`application-label`) of every application with open flaws in it. An existing issue lacking one
  is `needsLabel` (`missingLabels` names them, `counts.needsLabel`); `POST /jira/sast-issues` then adds them
  (`update.labels.add`, one update) and answers `LABELED` instead of `ALREADY_EXISTS`. The SAST tab mirrors SCA's tag,
  orange buttons and statistics card. Creating and labelling both need the required create fields, as for SCA.
- **SCA Issues on the page = one row per Jira issue** (2026-11-09, asked for): findings with the same issue key(s) - the
  same CVE + jar in several repositories - are one row, Repository listing each application on its own line (`<br />`,
  alphabetical, as the findings sort). Findings not in Jira stay a row each; the summary's "SCA (n)" still counts findings.
- **SAST in Jira** (2026-11-08, asked for, same page): `domain/SastIssue` = the open flaws (not fixed, not mitigated)
  grouped by CWE + the flaw's `categoryname`, across files and applications. Summary `sast.summary-template`
  (`{project} - {cwe} - {category}`, `{project}` = `sast.project-name` "IDB VERACODE STSA", also the label - spaces → `_`);
  found **by the summary alone** (`Jql.sastLookup`: CWE phrases, no version clause; `JiraLookupService.coversSast`: CWE and
  category as whole-word runs) and created with it, in `veracode.jira.sast.project-key` / `issue-type` (blank → SCA's).
  `JiraLookupService` reads a create screen per target (`Target`), so SAST has its own fields, versions and
  `sast.defaults`; Affects Version/s is optional for SAST (`CreateMeta.affectsVersion` / `versionRequired`: when the
  screen requires it, `createSast` needs exactly one). Every analysis carries `sast` (`SastJira`): issues with status,
  versions, fields, `defaultVersions`; a Jira failure there is `sast.error` and SCA still answers
  (`SastJiraMisconfiguredTest`). `POST /jira/sast-issues` {uploadId, issueIds, fields, jiraVersions}, same lock and
  re-check as SCA (`ALREADY_EXISTS`, a fixed / mitigated flaw's id = `NOT_IN_REPORT`). No application labels for SAST
  and not part of `autoCreate` (not asked for). xlsx *SAST flaws* gained Jira issue(s) / status. Page: tabs "SCA / SAST"
  (`?tab=sast`), `SastJiraPanel.tsx` (own version select + field bar, issues table expandable to flaws),
  `JiraFieldsBar.tsx` shared. Checked in headless Chrome (zh + en): defaults preselected, one created through the
  UI, found again by a new upload. Tests 76.
- **SAST flaws read** (2026-11-08, first step): the parser reads every
  `severity/category/cwe/staticflaws/flaw` → `domain/SastFlaw` (CWE + its `<cwe cwename>`, `sourcefilepath` +
  `sourcefile`, line, remediation / mitigation status; *mitigated* = `mitigation_status="accepted"`, *fixed* =
  `remediation_status="Fixed"`) and `SastSummary` per report (`<static-analysis>` rating / score, the root's
  `total_flaws` / `flaws_not_mitigated`, counted listed / open / mitigated / fixed / open-affecting-policy, open per
  severity). JSON: `sastFlaws` on the analysis, `reports[i].sast`; xlsx: *SAST flaws* (most severe first, CWE → MITRE
  link) and *SAST summary*. Not in `counts`, not in Jira. The sample gained three flaws (Very High open, Medium
  mitigated, Medium fixed; `total_flaws="3"`). Page: "SAST statistics" table + "SAST flaws" card (`SastPanel.tsx`;
  severity helpers moved to `severity.ts`). **If Jira is added later, the user chose CWE + file + line as a flaw's
  identity** (summary like `{project} - CWE-89 - ReportQueryBuilder.java:88`). Checked in headless Chrome with two
  reports (zh + en) and the export's sheet list.
- **SCA statistics** (2026-11-08, asked for): `VeracodeXmlParser` also reads `<software_composition_analysis>`'s
  `third_party_components` / `violate_policy` / `components_violated_policy` / `blacklisted_components` and counts
  vulnerable components, vulnerabilities per level, max CVSS and mitigated → `ScaSummary` on `ParsedReport` and
  `ReportView.sca` (JSON `reports[i].sca`). Counts are the report's (before `Finding.group`). A missing attribute is
  `null` (page "—", empty cell), a non-number one a warning. The page's "SCA statistics" table and the workbook's
  *SCA summary* sheet (second; severity headers in `SEVERITY_FILL`, a *Total* row with several reports, no sum for
  yes/no, Max CVSS = the highest) show the same thing. Tests: `VeracodeXmlParserTest.summarisesTheScaSection`,
  `VeracodeApiTest` (sample: 57 / 8 / 6 / 0, 10 vulnerabilities, 1 mitigated; two reports → Total 114 / 20).
  Checked in headless Chrome against a local instance on the mock Jira (zh + en).
- **Build on JDK 25**: javac 21.0.11 crashes compiling this module ("inconsistent stack types at join point", also
  without these changes) — set `JAVA_HOME` to the JDK 25 under `~/Desktop/workspace/tools/jdk/`.
- **Confluence attachments as a source** (2026-11-07, asked for): `ConfluenceClient` (`@HttpExchange`, own pool,
  Logbook, TLS unverified) + `ConfluenceService` (connection test, paged listing, download by attachment id from the
  server's own listing, redirects followed by hand with the token only under the base URL) + `ConfluenceToken`
  (`X-Confluence-Token` / `-User`, resolved by `JiraTokenArgumentResolver`). `POST /analysis/confluence` feeds the
  files to `analyzeUpload` (zip expansion included). `MockConfluenceController` (page 98765 / 98766). The page:
  tabs "上传文件 / Confluence 附件", `ConfluenceSource` + `ConfluenceTokenModal`; page id cached in `pageCache`.
- **Zip uploads** (2026-11-06, asked for): `ReportArchive` expands a `.zip` (by name or `PK\3\4`) into its `.xml`
  entries before parsing, `.xsd` skipped; inflated-size and count limits in `UploadProperties`; GB18030 retry on a
  non-UTF-8 entry name (that is a `ZipException` "bad entry name", not an `IllegalArgumentException`).
- **Configured preselections** (2026-11-06, asked for): `veracode.jira.defaults.affects-versions` → connection
  `defaultVersions` (only versions Jira has; the page selects them when no Jira version is chosen yet) and
  `defaults.fields` → `CreateField.defaults` (by field id or name, options by name or id; replaces Jira's default;
  unknown values dropped). Map keys lose spaces in relaxed binding, so names are compared without them.
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
