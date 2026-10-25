# creed-resource-veracode — handoff

**Purpose** Veracode report analysis: read the report table from a Confluence page, look every finding
up in Jira by CVE (or title) + affects version, list what exists, create what is missing, export xlsx.
**Skill** `creed-resource-veracode` · shared: `creed-platform` · frontend: `../../creed-env-matrix-design` (`/veracode`)

## Run

```bash
# plain HTTP on 18097, Confluence + Jira mocked in-process. No database, no PKI.
mvn -pl creed-resource/creed-resource-veracode spring-boot:run -Dspring-boot.run.workingDirectory="$PWD"

curl -s -XPOST localhost:18097/api/veracode/analysis -H 'Content-Type: application/json' -d '{}'
```

Against the real systems: `CREED_VERACODE_MOCK=false`, `CREED_CONFLUENCE_URL`, `CREED_JIRA_URL`,
`CREED_JIRA_BROWSE_URL=https://jira.example.com/browse/{key}`, `CREED_JIRA_PROJECT`,
`CREED_VERACODE_PAGE_ID`, and `CREED_{CONFLUENCE,JIRA}_AUTH=BEARER|BASIC` + `_TOKEN` (+ `_USER` for BASIC).

HTTPS (profile `ssl`, one-way TLS, `client-auth: none`) — keystore/truststore default to
`/etc/veracode/tls/*.p12` (override `CREED_VERACODE_SSL_KEYSTORE` / `_TRUSTSTORE`), password from
`CREED_VERACODE_SSL_PASSWORD` or a configtree file `veracode.ssl.password`. The mocks switch to
`https://localhost:${server.port}` and both clients trust through the same bundle. The cert must carry
SAN `localhost`; issuing one from the existing CA is in `openshift-learning/03-config-probes-resources.md`
exercise 1. Verified 2026-10-25: analysis over TLS gives the same 10 / 3 / 7 / 1 as plain HTTP.

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
| `GET /settings` | default page id, project, issue type, base URLs, mock flag — no credentials |
| `POST /analysis` `{pageId?, autoCreate?}` | findings with `status` EXISTS / MISSING / CREATED, `jiraVersion`, `issues`, `warnings`, `counts` |
| `POST /analysis/upload` multipart `file` + `?autoCreate=` | same, for an uploaded `.html/.htm/.csv/.xlsx/.xls`; `page.source=UPLOAD`, `page.id` = upload id |
| `POST /jira/issues` `{pageId?, uploadId?, findingIds[]}` | per-id `outcome`: CREATED / ALREADY_EXISTS / NOT_IN_REPORT / VERSION_NOT_IN_JIRA / FAILED |
| `GET /analysis/export?pageId=` or `?uploadId=` | xlsx (sheets *Findings*, *Source*) from a **fresh** analysis |

Errors: `{error, message, fields?, time}` — 404 `page_not_found`, 422 `report_unreadable`,
502 `confluence_error` / `jira_error`, 400 `bad_request` / `validation_failed` / `unsupported_file`,
404 `upload_expired`, 413 `file_too_large` (`CREED_VERACODE_MAX_UPLOAD`, default 10MB).
`uploadId` wins over `pageId` everywhere.

## Current state

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
- **Excel cells are read with `DataFormatter`** (text as displayed): a version typed as the number 2.3
  must come out as `2.3`, not a double like `2.2999…`.
- **CSV that is not valid UTF-8 is decoded as GB18030** (Excel on Chinese Windows); the delimiter is the
  most frequent of `, ; TAB` in the first line.

- **`affectedVersion in (…)` with a version the project lacks is a 400 for the whole JQL**, not "no
  match". The service reads `/project/{key}/versions` first and only searches known versions; the rest
  are `jiraVersion: null` and cannot be created.
- **`summary ~` is Lucene full text, not substring.** Phrases are tokenised and stop words dropped, so
  the query only narrows candidates; `JiraLookupService.covers` decides on the returned summary (CVE as a
  whole token — `CVE-2021-4422` must not match `CVE-2021-44228`; title as a whole phrase).
- **The finding is `(CVE or title, affected version)`, not a report row** — two libraries with one CVE
  are one Jira issue. A cell listing two CVEs is two findings.
- **Create re-reads page + Jira under a lock**, so a double click returns `ALREADY_EXISTS`. The lock is
  per process; two instances could still duplicate (Jira has no unique key to lean on).
- **Jira Cloud has retired `POST /rest/api/2/search`** (`/rest/api/3/search/jql`, token paging). The
  client is Data Center v2; Cloud needs that one method changed.
- **Clients trust a private CA only via `veracode.{confluence,jira}.ssl-bundle`.** The request factory
  is built in `RemoteClientsConfiguration`, so Boot's HTTP-client SSL properties never reach it. Blank =
  JDK cacerts → `PKIX` / `confluence_error` 502 against a Creed-signed cert.
- **The bundle is declared only in `application-ssl.yml`**: Boot opens every declared bundle at startup,
  so declaring it in `application.yml` breaks the plain-HTTP run. A missing password is not reported as an
  unresolvable placeholder — the literal `${veracode.ssl.password}` becomes the password and startup fails
  with the wrong-password `BadPaddingException`.
- **actuator `env`/`configprops` run with `show-values: always`**, which masks nothing by itself;
  `ActuatorSanitizingConfiguration` masks keys ending in password/secret/token (case-insensitive) and
  containing credentials. Not `ifLikelySensitive()` — it would also mask `project-key` and every URL.
- Redirects are not followed — a wrong Data Center base URL shows as its 302, not as "HTML is not JSON".
- Plain HTTP by default and **no auth on this service** (like the siblings' `permitAll`) — but it holds a Jira
  token that can create issues. Do not expose it beyond localhost as is.

## Open items

- Real Confluence/Jira trial; Jira Cloud search endpoint if that is the target.
- Merged cells (`rowspan`) in the report table are not expanded.
- Priority/assignee/components on created issues (project-specific names — not guessed).
- Not behind `creed-gateway-proxy` (only `/api/env-matrix/**` is proxied there); deploying the page
  needs a route for `/api/veracode/**`.
