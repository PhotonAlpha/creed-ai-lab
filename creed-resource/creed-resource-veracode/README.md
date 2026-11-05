# creed-resource-veracode

Reads an uploaded **Veracode detailed report (XML)**, finds the Jira issues that cover its SCA findings,
creates the missing ones with the user's own Jira token and exports the result to Excel. UI: the
`/veracode` page of `creed-env-matrix-design`.

```
Veracode detailed report (XML upload)       Jira project — /rest/api/2/issue/** only
  └─ software_composition_analysis                  GET  /issue/createmeta/{key}/issuetypes[/{id}]  (versions)
       └─ component / vulnerability ─► findings ──► GET  /issue/picker   (one per finding, currentJQL)
                                         │          GET  /issue/{key}    (each candidate)
                                         │          POST /issue          (missing ones, user's token)
                                         ▼
                             EXISTS / MISSING / CREATED  ──► UI table · xlsx
```

Jira is a Spring `@HttpExchange` interface (`JiraClient`) built with `HttpServiceProxyFactory` over its own
`RestClient`, on **Apache HttpClient 5** (pooled, no redirects, no cookies). Every Jira request and response is
logged by **Logbook** (logger `org.zalando.logbook.Logbook`, TRACE; `Authorization` masked; the service's own
inbound requests are not logged). Only the **Issue** group of the
Jira Data Center REST API (11.0.2 reference) is used — no `/search`, `/myself` or `/project/{key}/versions`.
**TLS to Jira is not verified** — neither the certificate nor the hostname.

## Report

`POST /api/veracode/analysis/upload` takes the Detailed Report XML (`<detailedreport>`, Veracode export
schema 1.x) and reads only `software_composition_analysis/vulnerable_components`:

| Finding field | From |
|---|---|
| cve | `vulnerability/@cve_id` when it is a `CVE-…`; else none (an `SRCCLR-SID-…` advisory) |
| title | first sentence of `@cve_summary`; the advisory id when there is no CVE |
| component | `component/@library` + `@version` (the **library's** version), e.g. `log4j-core 2.14.1` |
| severity | `@severity` 0–5 → Informational … Very High |
| cvss | `@cvss_score` |
| affected version | root `detailedreport/@version` (scan / build name), or `affectedVersion` on the upload — Jira's *Affects Version/s* |
| description | `@cve_summary` |

Static flaws are not read. `mitigation="true"` vulnerabilities are skipped with a warning. The parsed report
stays in memory for 2 h (`veracode.upload.ttl`); create, export and re-analysis name it by upload id.

## Matching

An issue covers a finding when its *Affects Version/s* contain the finding's version **and** its summary
(title) names it, matched loosely: the CVE anywhere in the summary, case and separators ignored
(`CVE-2021-44228` = `cve 2021 44228` = `CVE_2021_44228`) but never inside a longer id; without a CVE,
every word of the title (the advisory id) in any order. Candidates come from the issue picker (its
`currentJQL` = project + `affectedVersion = "…"` + `summary ~ …`, its `query` = the CVE / title words); each
is read with `GET /issue/{key}` and the match is decided on that. The project's versions are the
*Affects Version/s* `allowedValues` of the configured issue type's create screen. Created issues get the summary
`veracode.jira.summary-template` (default `{project} {component} {cve}`, `{project}` = the report's
`app_name`, e.g. `creed-platform spring-beans 5.3.17 CVE-2022-22965`), the version, labels `veracode` + the
CVE, and a wiki-markup description naming the Veracode application, scan and policy.

## Jira token

Issues are created with **the user's own Jira access token** (Data Center personal access token), set on
the page and sent per request as `X-Jira-Token` → `Authorization: Bearer` to Jira. The service never stores
it. Without it, create (and `autoCreate`) answer 400 `jira_token_required`
(`veracode.jira.require-user-token: false` falls back to the configured `veracode.jira.auth`). Searches use
the token when sent, else the configured auth. `POST /api/veracode/jira/connection` tests it on the create
metadata: whether Jira accepts it, may create the issue type in the project, and how many versions it can be
filed against (no user name — the issue resource has no "who am I").

## Configuration

See `application.yml`. Everything external is `${CREED_…}`; locally Jira is the in-process mock
(`/mock/jira`, project `CREED`) and `POST /api/veracode/analysis/sample` analyses the bundled
`sample/veracode-detailedreport.xml`.
