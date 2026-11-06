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

The page is shared, and the service has **no Jira account of its own**: every Jira call — lookups, the create
metadata, creating — uses **the user's own Jira sign-in**, typed on the page and sent per request. The page asks
which Jira it is; both go through the same REST API v2 resources:

| Page choice | Headers to this service | To Jira |
|---|---|---|
| Jira Cloud | `X-Jira-Token` = API token, `X-Jira-User` = Atlassian account e-mail | `Authorization: Basic base64(email:token)` |
| Jira Data Center | `X-Jira-Token` = personal access token | `Authorization: Bearer <token>` |

The kind travels with the token (an e-mail present = Cloud), not with the configuration: the dialog only
preselects Cloud when `base-url` is `*.atlassian.net`. On Cloud a user field takes an `accountId` instead of a
name, and the create metadata pages its items as `issueTypes` / `fields` instead of `values` (both read). A Cloud
API token "with scopes" is not accepted on `https://<site>.atlassian.net` — use a classic API token.

**Issue type and the connection test.** `veracode.jira.issue-type` is only the default: the page lets each user pick
one, sent as `X-Jira-Issue-Type` with the token (lookups read that type's versions, creating files as it). The test
answers check by check — `problem` is the first that failed: `UNREACHABLE`, `TOKEN_REJECTED` (401), `PROJECT`
(other Jira error), `ISSUE_TYPE` (not creatable), `AFFECTS_VERSION` (not on the type's create screen — common for
Task in Jira Cloud Scrum projects), `NO_VERSIONS` — and lists every creatable type (`issueTypes`) with whether it has
Affects Version/s, so the page can offer one that works. That costs one paged createmeta call per issue type, on the
test only; analyses read just the chosen one. So each user sees what they
may see and files issues as themselves (reporter = the token's owner). The service never stores it. Without it
every endpoint except `/settings` answers 400 `jira_token_required`. `POST /api/veracode/jira/connection` tests
it on the create metadata: whether Jira accepts it, may create the issue type in the project, how many versions
it can be filed against, and which fields the page has to ask for (no user name — the issue resource has no
"who am I").

## Required Jira fields

The create screen decides what else a new issue needs. Read with the user's token, `createFields` (in every
analysis, and `fields` in the connection test) lists **Component/s** and every **required field without a Jira
default** that the service does not set itself (it always sets project, issue type, summary, description,
Affects Version/s, labels). Each has `kind` OPTIONS (with Jira's `options`) / USER / NUMBER / TEXT, `multiple`,
and `defaults` — Jira's default, else the only option of a required field — which the page preselects. Create
and `autoCreate` take the choices as `fields` (`{"components": ["Security"], "customfield_10100": "Production"}`;
an option by id or label, any case); they are checked against the screen first: a required field left empty is
400 `jira_fields_required`, an unknown option 400 `bad_request`, before anything is created.

## Configuration

See `application.yml`. Everything external is `${CREED_…}`; locally Jira is the in-process mock
(`/mock/jira`, project `CREED`) and `POST /api/veracode/analysis/sample` analyses the bundled
`sample/veracode-detailedreport.xml`.
