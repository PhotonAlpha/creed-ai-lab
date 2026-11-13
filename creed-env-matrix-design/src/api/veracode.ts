import { ApiError, parseError, requestAt } from './client';

/**
 * creed-resource-veracode (`com.creed.resource.veracode.api.dto`). A separate service from env-matrix,
 * reached through its own Vite proxy entry (`VITE_VERACODE_TARGET`). Keep these types in step with the
 * Java DTOs; there is no node mock for this contract — the Java module mocks Jira itself and bundles a
 * sample Veracode XML report.
 */

const BASE = '/api/veracode';

export type FindingStatus = 'EXISTS' | 'MISSING' | 'CREATED';

export interface IssueRef {
  key: string;
  summary: string;
  status: string | null;
  priority: string | null;
  /** Display name; null = unassigned. */
  assignee: string | null;
  versions: string[];
  labels: string[];
  url: string;
}

/**
 * One vulnerability (`cve_id`) in one component file (`file_name`) of one Veracode application. The same CVE in the
 * same jar of two applications is one Jira issue, labelled with both (`needsLabel` until it is).
 */
export interface Finding {
  /** `creed-platform|CVE-2021-44228@log4j-core-2.14.1.jar`. */
  id: string;
  /** The report's Veracode application (`app_name`). */
  application: string | null;
  /** `cve_id|cwe_id`, "Unknown" for a missing part. */
  title: string;
  /** The report's `cve_id` (a CVE or an advisory id); null = Unknown. */
  cveId: string | null;
  /** `cveId` when it is a CVE — linked to NVD. */
  cve: string | null;
  cwe: string | null;
  /** The component's `file_name`. */
  fileName: string;
  components: string[];
  severity: string | null;
  cvss: number | null;
  /** The report's version (or the page's override): shown only, never searched or filed under. */
  affectedVersion: string;
  /** The full `cve_summary`. */
  description: string | null;
  /** "log4j-core 2.14.1 (app.war#zip:WEB-INF/lib/log4j-core-2.14.1.jar)" — the components in the report. */
  locations: string[];
  status: FindingStatus;
  issues: IssueRef[];
  /** The issue exists (another application filed it) but lacks this application's label: "create" adds it. */
  needsLabel: boolean;
  /** The summary it is filed under (`summary-template`), whether the issue exists or not. */
  summary?: string;
  /** Its issues are all finished (Closed / Done): it may be filed again, one at a time, after a confirmation. */
  recreatable?: boolean;
}

/** One uploaded Veracode detailed report (XML); an upload may hold several, one per file. */
export interface ReportView {
  fileName: string;
  appName: string | null;
  appId: string | null;
  /** The report's own `version` (scan / build name). */
  scanVersion: string | null;
  /** What findings are looked up and filed under: `scanVersion`, or the page's override. */
  affectedVersion: string;
  versionOverridden: boolean;
  policyName: string | null;
  policyCompliance: string | null;
  generatedAt: string | null;
  /** `null` only in an analysis cached before the field existed. */
  sca: ScaSummary | null;
  /** `null` only in an analysis cached before the field existed. */
  sast: SastSummary | null;
  /** The file it came in (a `.zip`, or the report itself). */
  sourceName?: string | null;
  /** The Confluence attachment that file was downloaded from; `null` for an upload. */
  sourceUrl?: string | null;
  /** The Confluence page holding that attachment, and its URL - both linked from the Jira description's Source. */
  sourcePageTitle?: string | null;
  sourcePageUrl?: string | null;
}

/**
 * A report's static-analysis statistics: the scan's rating / score and the root's stated `total_flaws` /
 * `flaws_not_mitigated` (`null` when absent), then what was counted. `bySeverity` counts **open** flaws.
 */
export interface SastSummary {
  rating: string | null;
  score: number | null;
  totalFlaws: number | null;
  flawsNotMitigated: number | null;
  /** Listed in the report, fixed and mitigated included. */
  flaws: number;
  open: number;
  mitigated: number;
  fixed: number;
  /** Open flaws that affect policy compliance. */
  affectsPolicy: number;
  bySeverity: { severity: string; count: number }[];
  unknownSeverity: number;
}

/** One static (SAST) flaw; open ones are filed in Jira grouped by CWE + category (`SastIssue`). */
export interface SastFlaw {
  application: string | null;
  issueId: string | null;
  severity: string | null;
  /** `CWE-89` */
  cwe: string | null;
  cweName: string | null;
  category: string | null;
  module: string | null;
  /** `com/creed/report/ReportQueryBuilder.java` */
  sourceFile: string | null;
  line: number | null;
  scope: string | null;
  remediationStatus: string | null;
  mitigationStatus: string | null;
  /** An approved mitigation. */
  mitigated: boolean;
  /** Fixed in this scan, listed for the record. */
  fixed: boolean;
  affectsPolicy: boolean | null;
  firstFound: string | null;
  description: string | null;
}

/**
 * A report's SCA statistics: what `<software_composition_analysis>` states (`null` when the report does not), and
 * what was counted in its `vulnerable_components`. Counts are the report's, before one CVE listed twice for a jar
 * is merged into one finding — `vulnerabilities` can exceed the findings table.
 */
export interface ScaSummary {
  thirdPartyComponents: number | null;
  violatesPolicy: boolean | null;
  componentsViolatedPolicy: number | null;
  blacklistedComponents: number | null;
  vulnerableComponents: number;
  /** Mitigated ones excluded — they are `mitigated`. */
  vulnerabilities: number;
  mitigated: number;
  /** Very High first; all six levels, 0 included. */
  bySeverity: { severity: string; count: number }[];
  unknownSeverity: number;
  maxCvss: number | null;
}

/**
 * What the upload control accepts: a Veracode detailed report exported as XML, or a zip of them (the backend reads
 * the `.xml` files in it and skips the `.xsd` beside them).
 */
export const UPLOAD_ACCEPT = '.xml,.zip';

export interface AnalysisCounts {
  total: number;
  exists: number;
  missing: number;
  created: number;
  /** Existing issues still without the finding's application label. */
  needsLabel: number;
}

/**
 * One SAST Jira issue: a CWE + flaw category across files and applications (open flaws only), found and created by its
 * summary `{project} - CWE-89 - {category}`.
 */
export interface SastIssue {
  /** `SAST|CWE-89|<category>` — what a create request names. */
  id: string;
  summary: string;
  cwe: string | null;
  cweName: string | null;
  category: string | null;
  severity: string | null;
  flaws: SastFlaw[];
  applications: string[];
  /** `null` when Jira could not be read for SAST (`SastJira.error`). */
  status: FindingStatus | null;
  issues: IssueRef[];
  /** As SCA: the issue exists but lacks the label of some application with open flaws in it (`missingLabels`). */
  needsLabel: boolean;
  missingLabels: string[];
  /** As SCA: its issues are all finished, it may be filed again after a confirmation. */
  recreatable?: boolean;
  /**
   * The issue per application, as the page and the Confluence export show it (display only; the Jira summary stays
   * `summary`). Absent in an analysis cached before it existed.
   */
  byApplication?: SastApplicationBlock[];
}

/**
 * Three lines per application: `Creed-CCS: IDB VERACODE STSA - CRLF Injection` / `CWE 117 Improper Output
 * Neutralization for Logs` / `CWE 117 (Flaw IDs: 257, 258)`.
 */
export interface SastApplicationBlock {
  appName: string | null;
  /** The application's mapped name (`veracode.applications`), else its `app_name`. */
  name: string;
  /** Where `name` links to; `null` without a mapping. */
  url: string | null;
  /** What follows `name: ` — `{sast project-name} - {category}`. */
  heading: string;
  cwe: string;
  flawIds: string;
}

/** A Veracode application under its mapped name and link (`veracode.applications` in the backend config). */
export interface ApplicationView {
  appName: string;
  /** The mapped name, else `appName`. */
  name: string;
  url: string | null;
}

/** The SAST half of an analysis: its own Jira project, issue type and create screen. */
export interface SastJira {
  projectKey: string;
  /** `{project}` of the SAST summaries (`sast.project-name`). */
  projectName?: string;
  issueType: string;
  /** The versions the SAST issue type can be filed against; empty without Affects Version/s. */
  jiraVersions: string[];
  affectsVersion: boolean;
  /** Creating needs exactly one version. */
  versionRequired: boolean;
  /** `sast.defaults.affects-versions` this issue type has: preselected. */
  defaultVersions: string[];
  createFields: CreateField[];
  issues: SastIssue[];
  counts: AnalysisCounts;
  /** Why Jira could not be read for SAST (wrong project / issue type); SCA is unaffected. */
  error: string | null;
}

export interface Analysis {
  /** Every report's static flaws; absent in an analysis cached before SAST was read. */
  sastFlaws?: SastFlaw[];
  /** Absent in an analysis cached before SAST went to Jira; null in a `skipSast` answer (the page keeps its own). */
  sast?: SastJira | null;
  /**
   * What every follow-up request names; the upload lives in the backend's memory for `veracode.upload.ttl` (2 h) and
   * not across a restart — then 404 `upload_expired`.
   */
  uploadId: string;
  reports: ReportView[];
  projectKey: string;
  /** Every version the issue type can be filed against — the page's Jira version list. */
  jiraVersions: string[];
  /** The chosen ones, Jira's spelling: searched (`affectedVersion in`); empty = any version. */
  selectedVersions: string[];
  /** What a created issue is filed under: the one chosen version; null with none or several chosen. */
  createVersion: string | null;
  /** The Country field's options; absent in an analysis cached before Country was searched. */
  countries?: string[];
  /** The chosen ones, Jira's spelling: searched (`cf[…] in`); empty = any country. */
  selectedCountries?: string[];
  /** What creating needs from the page, read through the user's token. */
  createFields: CreateField[];
  findings: Finding[];
  warnings: string[];
  counts: AnalysisCounts;
  /** The reports' applications under their mapped names and links; absent in an analysis cached before it existed. */
  applications?: ApplicationView[];
  analyzedAt: string;
}

export type CreateOutcome = 'CREATED' | 'ALREADY_EXISTS' | 'LABELED' | 'NOT_IN_REPORT' | 'FAILED';

export interface CreateResult {
  findingId: string;
  outcome: CreateOutcome;
  issues: IssueRef[];
  message: string | null;
}

export interface VeracodeSettings {
  projectKey: string;
  issueType: string;
  jiraBaseUrl: string;
  mock: boolean;
  /** Created issue title, e.g. `{project} {component} {cve}`. */
  summaryTemplate: string;
  /** The deployment the token dialog preselects (the base URL is `*.atlassian.net`); the user can switch it. */
  cloud: boolean;
  /** `null` = no Confluence configured: the page offers uploads only. */
  confluenceBaseUrl: string | null;
  /** The Confluence token dialog's preselected deployment. */
  confluenceCloud: boolean;
  /** The exported page's title template: `{project} - {version} - Reports (SAST,SCA) - {timestamp}`. */
  confluenceExportTitle: string;
  /** `veracode.confluence.parent-page-id`: the page whose child pages are offered; `null` = type a page id. */
  confluenceParentPageId?: string | null;
}

/**
 * A create-screen field the page asks for (Component/s, required fields Jira has no default for), read with
 * the user's token — so the options are the ones that user may choose. `defaults` are preselected:
 * `veracode.jira.defaults.fields` as far as Jira offers them, else Jira's default, else the only option of a
 * required field (option ids for OPTIONS).
 */
export interface CreateField {
  fieldId: string;
  name: string;
  required: boolean;
  kind: 'OPTIONS' | 'USER' | 'NUMBER' | 'TEXT';
  multiple: boolean;
  options: { id: string; label: string }[];
  defaults: string[];
}

/** fieldId -> option ids (OPTIONS) or the typed value(s). */
export type CreateFieldValues = Record<string, string[]>;

/** The connection test's checks in order; `problem` names the first that failed. */
export type JiraProblem = 'UNREACHABLE' | 'TOKEN_REJECTED' | 'PROJECT' | 'ISSUE_TYPE' | 'AFFECTS_VERSION' | 'NO_VERSIONS';

/** An issue type the token may create, and whether findings can be filed as it (Affects Version/s on its screen). */
export interface JiraIssueTypeOption {
  id: string;
  name: string;
  affectsVersion: boolean;
  versions: number;
}

/**
 * `POST /jira/connection`: a rejected token is `ok: false` with Jira's status, not an error.
 * The "test connection" answer, from Jira's create metadata (no "who am I" is called: the result names what the
 * token may do, not whose it is).
 */
export interface JiraConnection {
  ok: boolean;
  baseUrl: string;
  projectKey: string;
  issueType: string;
  /** Jira took the token (answered, and not 401); `ok` can still be false — project or issue type. */
  tokenAccepted: boolean;
  canCreate: boolean;
  /** The issue type's create screen has Affects Version/s. */
  affectsVersion: boolean;
  /** Versions the issue type can be filed against. */
  versions: number;
  /** Their names — the page's Jira version list before the first analysis. */
  versionNames: string[];
  /**
   * `veracode.jira.defaults.affects-versions` that this Jira has, in its spelling: selected when the page has no
   * Jira version chosen yet. Empty = the page keeps its selection.
   */
  defaultVersions: string[];
  /** The create-screen fields the page asks for. */
  fields: CreateField[];
  /** The Country field's options (`veracode.jira.country-field`) — the page's Country select; empty without one. */
  countries?: string[];
  /** Every type the token may create — the page's issue type choice. */
  issueTypes: JiraIssueTypeOption[];
  /** The first failed check, `null` when `ok`. */
  problem: JiraProblem | null;
  /** Jira's HTTP status; 0 = Jira unreachable. */
  status: number;
  message: string | null;
}

/**
 * The user's Jira access token travels with every request in this header — the backend has no Jira account of
 * its own and never stores it, so a shared page reads and files as whoever is using it.
 */
export const JIRA_TOKEN_HEADER = 'X-Jira-Token';
/** Jira Cloud only: the Atlassian account e-mail that goes with the API token. */
export const JIRA_USER_HEADER = 'X-Jira-User';
/** The issue type the user files as; absent = the backend's `veracode.jira.issue-type`. */
export const JIRA_ISSUE_TYPE_HEADER = 'X-Jira-Issue-Type';

export type JiraDeployment = 'cloud' | 'dataCenter';

/**
 * How the user signs in to Jira. Both kinds go through REST API v2; the backend sends Cloud's e-mail + API token as
 * `Basic base64(email:token)` and Data Center's personal access token as `Bearer`.
 */
export interface JiraAuth {
  deployment: JiraDeployment;
  token: string;
  /** Cloud only. */
  email: string;
  /** Not a credential, but the user's choice too, sent with every call; '' = the backend's configured type. */
  issueType: string;
}

/** Everything the chosen kind needs is filled in. */
export const jiraAuthComplete = (auth: JiraAuth) =>
  !!auth.token.trim() && (auth.deployment === 'dataCenter' || !!auth.email.trim());

const tokenHeader = (auth: JiraAuth): Record<string, string> => ({
  [JIRA_TOKEN_HEADER]: auth.token,
  ...(auth.deployment === 'cloud' && auth.email ? { [JIRA_USER_HEADER]: auth.email } : {}),
  ...(auth.issueType ? { [JIRA_ISSUE_TYPE_HEADER]: auth.issueType } : {}),
});

/**
 * The user's Confluence sign-in, the same two kinds as Jira's (Cloud: e-mail + API token, Data Center: personal
 * access token) — read with it are the page's attachments, downloaded by the backend. Never stored there.
 */
export const CONFLUENCE_TOKEN_HEADER = 'X-Confluence-Token';
export const CONFLUENCE_USER_HEADER = 'X-Confluence-User';

export interface ConfluenceAuth {
  deployment: JiraDeployment;
  token: string;
  /** Cloud only. */
  email: string;
}

export const confluenceAuthComplete = (auth: ConfluenceAuth) =>
  !!auth.token.trim() && (auth.deployment === 'dataCenter' || !!auth.email.trim());

const confluenceHeader = (auth: ConfluenceAuth): Record<string, string> => ({
  [CONFLUENCE_TOKEN_HEADER]: auth.token,
  ...(auth.deployment === 'cloud' && auth.email ? { [CONFLUENCE_USER_HEADER]: auth.email } : {}),
});

/** The page `exportToConfluence` created; `url` opens it. */
export interface ConfluenceCreatedPage {
  id: string;
  title: string;
  spaceKey: string;
  parentId: string;
  url: string;
  /** The report files (as uploaded, or as taken from Confluence) attached to the page. */
  attachments: string[];
  /** Why attaching failed - the page exists either way. */
  attachmentError?: string | null;
}

/** `POST /confluence/connection`: a rejected token is `ok: false`, not an error. */
export interface ConfluenceConnection {
  ok: boolean;
  baseUrl: string;
  /** Whom the token signs in as. */
  user: string | null;
  problem: 'UNREACHABLE' | 'TOKEN_REJECTED' | null;
  status: number;
  message: string | null;
}

export interface ConfluenceAttachment {
  id: string;
  title: string;
  mediaType: string | null;
  /** Bytes, as Confluence lists it. */
  size: number | null;
  version: number;
  /** When this version was uploaded (ISO-8601). */
  created: string | null;
  author: string | null;
  /** `.xml` or `.zip`: the only ones that can be chosen. */
  supported: boolean;
}

export interface ConfluencePageAttachments {
  pageId: string;
  title: string;
  spaceKey: string | null;
  /** The page as a person opens it. */
  url: string;
  attachments: ConfluenceAttachment[];
}

export interface ConfluenceChildPage {
  id: string;
  title: string;
  /** The page as a person opens it. */
  url: string;
  /** Last edited (ISO-8601). */
  modified: string | null;
}

export interface ConfluencePageChildren {
  pageId: string;
  title: string;
  url: string;
  children: ConfluenceChildPage[];
}

/** Spelled out because `requestAt` drops its own Content-Type as soon as `init.headers` is set. */
export interface UploadOptions {
  /** Shown instead of the report's version; display only. */
  affectedVersion?: string;
  /** Jira versions to look up in (none = any); creating files under the one chosen. */
  jiraVersions?: string[];
  /** Country options to look up in (none = any). */
  countries?: string[];
  autoCreate?: boolean;
  /** Create-screen values for `autoCreate`. */
  fields?: CreateFieldValues;
}

/** Bytes of the report sent so far. */
export interface UploadProgress {
  loaded: number;
  total: number;
}

/** Repeated `jiraVersions=` / `countries=` parameters — what Spring binds to a `List<String>`. */
const withVersions = (params: URLSearchParams, jiraVersions: string[] = [], countries: string[] = []) => {
  jiraVersions.forEach((v) => params.append('jiraVersions', v));
  countries.forEach((c) => params.append('countries', c));
  return params;
};

const uploadQuery = ({ affectedVersion, autoCreate, fields, jiraVersions, countries }: UploadOptions) => withVersions(new URLSearchParams({
  autoCreate: String(!!autoCreate),
  ...(affectedVersion?.trim() ? { affectedVersion: affectedVersion.trim() } : {}),
  ...(autoCreate && fields ? { fields: JSON.stringify(fields) } : {}),
}), jiraVersions, countries);

const json = (body: unknown, auth: JiraAuth): RequestInit => ({
  method: 'POST',
  body: JSON.stringify(body),
  headers: { 'Content-Type': 'application/json', ...tokenHeader(auth) },
});

/**
 * One request for identical reads in flight (2026-11-13: every call was sent twice on the first load). React's
 * StrictMode mounts each component twice in development, so every load effect fires twice - and the Jira ones
 * (connection test, analysis) ran Jira into 429 Too Many Requests. The second caller gets the first one's promise.
 *
 * Reads only: a create must stay one request per click. The entry is gone once the request settles, so a later
 * identical call asks again (nothing is cached), and the key - which holds the token headers - never outlives it.
 */
const inFlight = new Map<string, Promise<unknown>>();
const shared = <T>(path: string, init: RequestInit | undefined, send: () => Promise<T>): Promise<T> => {
  const key = JSON.stringify([init?.method ?? 'GET', path, init?.headers ?? null, init?.body ?? null]);
  const running = inFlight.get(key);
  if (running) return running as Promise<T>;
  const promise = send().finally(() => inFlight.delete(key));
  inFlight.set(key, promise);
  return promise;
};
/** `requestAt(BASE, ...)` through `shared`. */
const read = <T>(path: string, init?: RequestInit) => shared(path, init, () => requestAt<T>(BASE, path, init));

export const veracodeApi = {
  settings: () => read<VeracodeSettings>('/settings'),

  /** Re-analyse an upload against Jira as it is now. Shared while in flight unless it creates (`autoCreate`). */
  analyze: (uploadId: string, auth: JiraAuth, jiraVersions: string[], autoCreate = false, fields?: CreateFieldValues,
    countries: string[] = [], skipSast = false) => {
    const init = json({ uploadId, autoCreate, fields, jiraVersions, countries, skipSast }, auth);
    return autoCreate ? requestAt<Analysis>(BASE, '/analysis', init) : read<Analysis>('/analysis', init);
  },

  /**
   * Multipart, so not through `requestAt`: that sets a JSON Content-Type whenever there is a body, and
   * a multipart request must let the browser write its own boundary. `affectedVersion` overrides the
   * report's `version` (blank = the report's).
   *
   * XMLHttpRequest rather than fetch: fetch has no upload progress. `onProgress` gets the bytes sent; once the
   * last byte is out it is called with `loaded === total` and the server is parsing and asking Jira.
   */
  analyzeUpload: (files: File[], options: UploadOptions, auth: JiraAuth, onProgress?: (p: UploadProgress) => void) =>
    new Promise<Analysis>((resolve, reject) => {
      const form = new FormData();
      // one part per file, all named `file`: the backend reads them as one upload
      files.forEach((file) => form.append('file', file));
      const size = files.reduce((n, f) => n + f.size, 0);
      const xhr = new XMLHttpRequest();
      xhr.open('POST', `${BASE}/analysis/upload?${uploadQuery(options)}`);
      Object.entries(tokenHeader(auth)).forEach(([name, value]) => xhr.setRequestHeader(name, value));
      xhr.upload.onprogress = (e) => {
        onProgress?.({ loaded: e.loaded, total: e.lengthComputable ? e.total : size });
      };
      xhr.upload.onload = () => onProgress?.({ loaded: size, total: size });
      xhr.onload = () => {
        // through a Response, so the error envelope is unwrapped exactly as for every other call
        const response = new Response(xhr.responseText, { status: xhr.status, statusText: xhr.statusText });
        if (xhr.status < 200 || xhr.status >= 300) {
          void parseError(response).then(reject, reject);
          return;
        }
        response.json().then((body) => resolve(body as Analysis), reject);
      };
      xhr.onerror = () => reject(new Error(`upload failed: ${BASE} not reachable`));
      xhr.send(form);
    }),

  testConfluence: (auth: ConfluenceAuth) =>
    read<ConfluenceConnection>('/confluence/connection', { method: 'POST', headers: confluenceHeader(auth) }),

  /** The page's child pages, all of them: the page filters them by title itself. */
  confluenceChildren: (pageId: string, auth: ConfluenceAuth) =>
    read<ConfluencePageChildren>(`/confluence/pages/${encodeURIComponent(pageId)}/children`,
      { headers: confluenceHeader(auth) }),

  confluenceAttachments: (pageId: string, auth: ConfluenceAuth) =>
    read<ConfluencePageAttachments>(`/confluence/pages/${encodeURIComponent(pageId)}/attachments`,
      { headers: confluenceHeader(auth) }),

  /**
   * The backend downloads the chosen attachments with the Confluence token and analyses them as an upload of the
   * same files would be (a zip is the reports in it); Jira is read with the Jira token as always.
   */
  analyzeConfluence: (pageId: string, attachmentIds: string[], options: UploadOptions, auth: JiraAuth,
    confluence: ConfluenceAuth) =>
    requestAt<Analysis>(BASE, '/analysis/confluence', {
      method: 'POST',
      body: JSON.stringify({
        pageId,
        attachmentIds,
        affectedVersion: options.affectedVersion?.trim() || null,
        autoCreate: !!options.autoCreate,
        fields: options.autoCreate ? options.fields : undefined,
        jiraVersions: options.jiraVersions ?? [],
        countries: options.countries ?? [],
      }),
      headers: { 'Content-Type': 'application/json', ...tokenHeader(auth), ...confluenceHeader(confluence) },
    }),

  /** The backend's bundled sample report, as if uploaded — only served with the mocks. */
  analyzeSample: (options: UploadOptions, auth: JiraAuth) =>
    requestAt<Analysis>(BASE, `/analysis/sample?${uploadQuery(options)}`, { method: 'POST', headers: tokenHeader(auth) }),

  /**
   * Findings go by id: the server re-reads the page and re-checks Jira before creating, so a stale
   * screen or a double click comes back ALREADY_EXISTS instead of a duplicate. `recreateClosed` files the one finding
   * named although its issues exist, when they are all finished (Closed / Done).
   */
  createIssues: (uploadId: string, findingIds: string[], fields: CreateFieldValues, jiraVersions: string[], auth: JiraAuth,
    recreateClosed = false, countries: string[] = []) =>
    requestAt<{ results: CreateResult[] }>(BASE, '/jira/issues',
      json({ uploadId, findingIds, fields, jiraVersions, countries, recreateClosed }, auth)),

  /**
   * The analysis (fresh) as a new Confluence page under `parentPageId`, as the user: the Jira token reads the issues,
   * the Confluence token writes the page. `project` / `version` fill the title; blank = the reports' own.
   */
  exportToConfluence: (request: { uploadId: string; parentPageId: string; project: string; version: string; jiraVersions: string[];
    countries: string[] },
    auth: JiraAuth, confluence: ConfluenceAuth) =>
    requestAt<ConfluenceCreatedPage>(BASE, '/confluence/export', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', ...tokenHeader(auth), ...confluenceHeader(confluence) },
      body: JSON.stringify(request),
    }),

  /** SAST issues by id, into the SAST project; `jiraVersions` = the version chosen for SAST (0 or 1). */
  createSastIssues: (uploadId: string, issueIds: string[], fields: CreateFieldValues, jiraVersions: string[], auth: JiraAuth,
    recreateClosed = false, countries: string[] = []) =>
    requestAt<{ results: CreateResult[] }>(BASE, '/jira/sast-issues',
      json({ uploadId, issueIds, fields, jiraVersions, countries, recreateClosed }, auth)),

  /** What the token may create in the project, and the create-screen fields the page has to ask for. */
  testConnection: (auth: JiraAuth) =>
    read<JiraConnection>('/jira/connection', { method: 'POST', headers: tokenHeader(auth) }),

  /**
   * A fresh analysis as .xlsx. Fetched rather than opened as a link so a failure shows the server's
   * message instead of a browser download of an error page.
   */
  exportXlsx: async (uploadId: string, auth: JiraAuth, jiraVersions: string[], countries: string[] = []) => {
    const query = withVersions(new URLSearchParams({ uploadId }), jiraVersions, countries);
    const response = await fetch(`${BASE}/analysis/export?${query}`, { headers: tokenHeader(auth) });
    if (!response.ok) {
      throw await parseError(response);
    }
    const disposition = response.headers.get('Content-Disposition') ?? '';
    const filename = /filename="?([^";]+)"?/.exec(disposition)?.[1] ?? 'veracode.xlsx';
    return { blob: await response.blob(), filename };
  },
};

// ---- Report templates: a company .xlsx, its cells bound to SCA / SAST fields, filled at export (JXLS) ----

/**
 * `sca` = the SCA findings table, `sast` = the SAST Jira table, `scaStats` / `sastStats` = the SCA / SAST statistics
 * (one row per report), `summary` = one value of the analysis.
 */
export type TemplateDataset = 'sca' | 'sast' | 'scaStats' | 'sastStats' | 'summary';
/** What a data row can repeat. */
export type ListDataset = Exclude<TemplateDataset, 'summary'>;

export interface TemplateField {
  key: string;
  /** English; the page translates the keys it knows. */
  label: string;
}

export interface TemplateCell {
  col: number;
  text: string;
  bold: boolean;
  /** `#RRGGBB` of a solid fill. */
  fill: string | null;
}

export interface TemplateSheet {
  name: string;
  /** Rows / columns sent: the used range, cut at 500 × 60 (`truncated`). */
  rows: number;
  cols: number;
  truncated: boolean;
  data: { row: number; cells: TemplateCell[] }[];
  merges: { firstRow: number; lastRow: number; firstCol: number; lastCol: number }[];
  /** Column widths in pixels. */
  widths: number[];
  /**
   * Cells below the content that show nothing (a formula with an empty result, whitespace) - a helper column filled down
   * the whole sheet. The export leaves them out, but every open of the workbook still reads them. Absent when none.
   */
  hidden?: { cells: number; lastRow: number; example: string } | null;
  /** What shows, uncut: `truncated` = more than 500 × 60, and then bindings cannot be saved. */
  usedRows: number;
  usedCols: number;
}

/**
 * A template upload's progress: the bytes sent (`upload`), then the server reading the workbook - `parse` (POI reading
 * the file, no finer step), `sheet` (`row` of `rows` scanned in sheet `index` of `sheets`), `store`.
 */
export type TemplateUploadProgress =
  | { phase: 'upload'; loaded: number; total: number }
  | { phase: 'parse' | 'store' }
  | { phase: 'sheet'; sheet: string; index: number; sheets: number; row: number; rows: number };

/** 0-based rows and columns, as POI counts them. */
export interface TemplateMapping {
  sheets: {
    sheet: string;
    /** Data rows: repeated once per item of the dataset at export, what is below moving down. */
    lists: { dataset: ListDataset; row: number; cells: { col: number; field: string }[] }[];
    /** Single cells showing a summary field. */
    values: { row: number; col: number; field: string }[];
  }[];
}

export interface ReportTemplateSummary {
  id: string;
  name: string;
  fileName: string;
  lists: number;
  values: number;
  updatedAt: string;
}

export interface ReportTemplateDetail {
  id: string;
  name: string;
  fileName: string;
  sheets: TemplateSheet[];
  mapping: TemplateMapping;
  updatedAt: string;
}

/** A download as the browser should save it; `filename*` (UTF-8) first - template names may be Chinese. */
const download = async (response: Response, fallback: string) => {
  if (!response.ok) {
    throw await parseError(response);
  }
  const disposition = response.headers.get('Content-Disposition') ?? '';
  const encoded = /filename\*=UTF-8''([^;]+)/i.exec(disposition)?.[1];
  const filename = encoded ? decodeURIComponent(encoded) : /filename="?([^";]+)"?/.exec(disposition)?.[1] ?? fallback;
  return { blob: await response.blob(), filename };
};

const TEMPLATES = '/report-templates';

export const reportTemplateApi = {
  list: () => read<ReportTemplateSummary[]>(TEMPLATES),
  fields: () => read<Record<TemplateDataset, TemplateField[]>>(`${TEMPLATES}/fields`),
  get: (id: string) => read<ReportTemplateDetail>(`${TEMPLATES}/${encodeURIComponent(id)}`),
  /**
   * The upload with its progress: XMLHttpRequest for the bytes sent (fetch has none), and the answer as Server-Sent
   * Events (`Accept: text/event-stream`) read as it streams in - `progress` events while the server reads the workbook,
   * then `done` (the template) or `error` (`{error, message}`). A refusal before the stream starts (a file over the
   * size limit) is the usual JSON error.
   */
  uploadWithProgress: (file: File, onProgress: (p: TemplateUploadProgress) => void, name?: string) =>
    new Promise<ReportTemplateDetail>((resolve, reject) => {
      const form = new FormData();
      form.append('file', file);
      if (name?.trim()) form.append('name', name.trim());
      const xhr = new XMLHttpRequest();
      xhr.open('POST', `${BASE}${TEMPLATES}`);
      xhr.setRequestHeader('Accept', 'text/event-stream');
      xhr.upload.onprogress = (e) => onProgress({ phase: 'upload', loaded: e.loaded, total: e.lengthComputable ? e.total : file.size });
      let read = 0;
      let settled = false;
      const settle = (fn: () => void) => {
        if (!settled) {
          settled = true;
          fn();
        }
      };
      // frames are "event: x\ndata: {...}\n\n"; responseText grows as the stream arrives, a frame may be cut anywhere
      const frames = () => {
        if (xhr.status !== 200) return;
        let end = xhr.responseText.indexOf('\n\n', read);
        while (end >= 0) {
          const frame = xhr.responseText.slice(read, end);
          read = end + 2;
          const event = /^event:\s*(.*)$/m.exec(frame)?.[1]?.trim();
          const data = frame.split('\n').filter((l) => l.startsWith('data:')).map((l) => l.slice(5).trimStart()).join('\n');
          if (data) {
            const body = JSON.parse(data);
            if (event === 'progress') onProgress(body as TemplateUploadProgress);
            else if (event === 'done') settle(() => resolve(body as ReportTemplateDetail));
            else if (event === 'error') settle(() => reject(new ApiError(400, body.message ?? 'upload failed', body)));
          }
          end = xhr.responseText.indexOf('\n\n', read);
        }
      };
      xhr.onprogress = frames;
      xhr.onload = () => {
        if (xhr.status < 200 || xhr.status >= 300) {
          const response = new Response(xhr.responseText, { status: xhr.status, statusText: xhr.statusText });
          void parseError(response).then((e) => settle(() => reject(e)), (e) => settle(() => reject(e)));
          return;
        }
        frames();
        settle(() => reject(new Error('the upload ended without an answer')));
      };
      xhr.onerror = () => settle(() => reject(new Error(`upload failed: ${BASE} not reachable`)));
      xhr.send(form);
    }),

  /** Multipart: `headers: {}` keeps `requestAt` from claiming JSON, so the browser writes the boundary. */
  upload: (file: File, name?: string) => {
    const form = new FormData();
    form.append('file', file);
    if (name?.trim()) form.append('name', name.trim());
    return requestAt<ReportTemplateDetail>(BASE, TEMPLATES, { method: 'POST', body: form, headers: {} });
  },
  saveMapping: (id: string, mapping: TemplateMapping) =>
    requestAt<ReportTemplateDetail>(BASE, `${TEMPLATES}/${encodeURIComponent(id)}/mapping`,
      { method: 'PUT', body: JSON.stringify(mapping) }),
  remove: (id: string) => requestAt<void>(BASE, `${TEMPLATES}/${encodeURIComponent(id)}`, { method: 'DELETE' }),
  /** The workbook with its JXLS markup (jx: comments, `${...}` cells), as export fills it. */
  jxls: async (id: string) =>
    download(await fetch(`${BASE}${TEMPLATES}/${encodeURIComponent(id)}/jxls`), 'template-jxls.xlsx'),
  /** A fresh analysis written into the template, with the page's lookup conditions. */
  exportWith: async (id: string, uploadId: string, auth: JiraAuth, jiraVersions: string[], countries: string[]) => {
    const query = withVersions(new URLSearchParams({ uploadId }), jiraVersions, countries);
    return download(await fetch(`${BASE}${TEMPLATES}/${encodeURIComponent(id)}/export?${query}`,
      { headers: tokenHeader(auth) }), 'report.xlsx');
  },
};

/**
 * A Veracode application under a name of its own (was `veracode.applications` in the backend's configuration): the alias
 * is what the Veracode page, Jira (description and label) and the Confluence export show, linked to the repository.
 */
export interface ApplicationAlias {
  /** The report's `app_name`, matched in any case. */
  appName: string;
  /** Blank = the `app_name`. */
  alias?: string | null;
  /** Blank = no link. */
  repositoryUrl?: string | null;
}

const ALIASES = '/application-aliases';

export const applicationAliasApi = {
  list: () => read<ApplicationAlias[]>(ALIASES),
  /** The whole table: the backend replaces what it holds. */
  save: (aliases: ApplicationAlias[]) =>
    requestAt<ApplicationAlias[]>(BASE, ALIASES, { method: 'PUT', body: JSON.stringify(aliases) }),
};

/** Saves a downloaded file the way the page's other exports do. */
export const saveBlob = ({ blob, filename }: { blob: Blob; filename: string }) => {
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  a.click();
  URL.revokeObjectURL(url);
};
