# creed-resource-veracode

Reads a Veracode report from a Confluence page, finds the matching Jira issues, creates the missing
ones and exports the result to Excel. UI: the `/veracode` page of `creed-env-matrix-design`.

```
Confluence page (storage XHTML)            Jira project
  └─ report <table>  ── parse ──► findings ──► GET /project/{key}/versions
                                    │          POST /search   (JQL per batch)
                                    │          POST /issue    (missing ones, on request)
                                    ▼
                        EXISTS / MISSING / CREATED  ──► UI table · xlsx
```

Both remote calls are Spring `@HttpExchange` interfaces (`ConfluenceClient`, `JiraClient`) built with
`HttpServiceProxyFactory` over a `RestClient` each (own base URL, credentials, timeouts).

## Report table

Any table on the page whose header row (first row, `th` or `td`) has an **affected version** column and
a **CVE** or **title** column. Header names are configurable (`veracode.report.*`, case-insensitive):

| Field | Accepted headers (default) |
|---|---|
| cve | CVE, CVE ID |
| title | Title, Vulnerability, Flaw |
| component | Component, Library |
| severity | Severity |
| cvss | CVSS, CVSS Score |
| affectedVersion | Affected Version, Affects Version, Version — **our** release, = Jira *Affects Version/s* |
| fixedVersion | Fixed Version, Fix Version |
| description | Description, Details |

Rows without a version, or without both CVE and title, are skipped with a warning.

The same rules apply to an **uploaded file** (`POST /api/veracode/analysis/upload`): `.html`/`.htm`,
`.csv`, `.xlsx`/`.xls` (every sheet; the header row is searched in the first 10 rows, so title rows above
it are fine). The service's own Excel export can be uploaded back.

## Matching

An issue covers a finding when its *Affects Version/s* contain the finding's version **and** its summary
names the CVE (whole token) — or, without a CVE, contains the title as a phrase. Created issues get
summary `[Veracode] <CVE> <title>`, the version, labels `veracode` + the CVE, and a wiki-markup
description linking back to the page.

## Configuration

See `application.yml`. Everything external is `${CREED_…}`; locally the defaults point at the
in-process mocks (`/mock/confluence`, `/mock/jira`; sample page `98765`, project `CREED`).
