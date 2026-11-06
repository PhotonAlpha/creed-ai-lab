import { parseError, requestAt } from './client';

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

export interface Analysis {
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
  /** What creating needs from the page, read through the user's token. */
  createFields: CreateField[];
  findings: Finding[];
  warnings: string[];
  counts: AnalysisCounts;
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

/** Spelled out because `requestAt` drops its own Content-Type as soon as `init.headers` is set. */
export interface UploadOptions {
  /** Shown instead of the report's version; display only. */
  affectedVersion?: string;
  /** Jira versions to look up in (none = any); creating files under the one chosen. */
  jiraVersions?: string[];
  autoCreate?: boolean;
  /** Create-screen values for `autoCreate`. */
  fields?: CreateFieldValues;
}

/** Bytes of the report sent so far. */
export interface UploadProgress {
  loaded: number;
  total: number;
}

/** Repeated `jiraVersions=` parameters — what Spring binds to a `List<String>`. */
const withVersions = (params: URLSearchParams, jiraVersions: string[] = []) => {
  jiraVersions.forEach((v) => params.append('jiraVersions', v));
  return params;
};

const uploadQuery = ({ affectedVersion, autoCreate, fields, jiraVersions }: UploadOptions) => withVersions(new URLSearchParams({
  autoCreate: String(!!autoCreate),
  ...(affectedVersion?.trim() ? { affectedVersion: affectedVersion.trim() } : {}),
  ...(autoCreate && fields ? { fields: JSON.stringify(fields) } : {}),
}), jiraVersions);

const json = (body: unknown, auth: JiraAuth): RequestInit => ({
  method: 'POST',
  body: JSON.stringify(body),
  headers: { 'Content-Type': 'application/json', ...tokenHeader(auth) },
});

export const veracodeApi = {
  settings: () => requestAt<VeracodeSettings>(BASE, '/settings'),

  /** Re-analyse an upload against Jira as it is now. */
  analyze: (uploadId: string, auth: JiraAuth, jiraVersions: string[], autoCreate = false, fields?: CreateFieldValues) =>
    requestAt<Analysis>(BASE, '/analysis', json({ uploadId, autoCreate, fields, jiraVersions }, auth)),

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
    requestAt<ConfluenceConnection>(BASE, '/confluence/connection', { method: 'POST', headers: confluenceHeader(auth) }),

  confluenceAttachments: (pageId: string, auth: ConfluenceAuth) =>
    requestAt<ConfluencePageAttachments>(BASE, `/confluence/pages/${encodeURIComponent(pageId)}/attachments`,
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
      }),
      headers: { 'Content-Type': 'application/json', ...tokenHeader(auth), ...confluenceHeader(confluence) },
    }),

  /** The backend's bundled sample report, as if uploaded — only served with the mocks. */
  analyzeSample: (options: UploadOptions, auth: JiraAuth) =>
    requestAt<Analysis>(BASE, `/analysis/sample?${uploadQuery(options)}`, { method: 'POST', headers: tokenHeader(auth) }),

  /**
   * Findings go by id: the server re-reads the page and re-checks Jira before creating, so a stale
   * screen or a double click comes back ALREADY_EXISTS instead of a duplicate.
   */
  createIssues: (uploadId: string, findingIds: string[], fields: CreateFieldValues, jiraVersions: string[], auth: JiraAuth) =>
    requestAt<{ results: CreateResult[] }>(BASE, '/jira/issues', json({ uploadId, findingIds, fields, jiraVersions }, auth)),

  /** What the token may create in the project, and the create-screen fields the page has to ask for. */
  testConnection: (auth: JiraAuth) =>
    requestAt<JiraConnection>(BASE, '/jira/connection', { method: 'POST', headers: tokenHeader(auth) }),

  /**
   * A fresh analysis as .xlsx. Fetched rather than opened as a link so a failure shows the server's
   * message instead of a browser download of an error page.
   */
  exportXlsx: async (uploadId: string, auth: JiraAuth, jiraVersions: string[]) => {
    const query = withVersions(new URLSearchParams({ uploadId }), jiraVersions);
    const response = await fetch(`${BASE}/analysis/export?${query}`, { headers: tokenHeader(auth) });
    if (!response.ok) {
      throw await parseError(response);
    }
    const disposition = response.headers.get('Content-Disposition') ?? '';
    const filename = /filename="?([^";]+)"?/.exec(disposition)?.[1] ?? 'veracode.xlsx';
    return { blob: await response.blob(), filename };
  },
};
