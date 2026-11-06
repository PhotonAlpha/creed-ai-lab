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
  url: string;
}

export interface Finding {
  /** `CVE-2021-44228@2.3.0`, or `title:<normalised title>@<version>` for a finding without a CVE. */
  id: string;
  cve: string | null;
  title: string;
  components: string[];
  severity: string | null;
  cvss: number | null;
  /** The report's version (or the page's override): shown only, never searched or filed under. */
  affectedVersion: string;
  fixedVersion: string | null;
  description: string | null;
  /** "log4j-core 2.14.1 (app.war#zip:WEB-INF/lib/log4j-core-2.14.1.jar)" — the components in the report. */
  locations: string[];
  status: FindingStatus;
  issues: IssueRef[];
}

/**
 * The uploaded Veracode detailed report (XML). `id` is the upload id every follow-up request names; it lives
 * in the backend's memory for `veracode.upload.ttl` (2 h) and not across a restart — then 404 `upload_expired`.
 */
export interface ReportView {
  id: string;
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

/** What the upload control accepts: a Veracode detailed report exported as XML. */
export const UPLOAD_ACCEPT = '.xml';

export interface AnalysisCounts {
  total: number;
  exists: number;
  missing: number;
  created: number;
}

export interface Analysis {
  report: ReportView;
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

export type CreateOutcome = 'CREATED' | 'ALREADY_EXISTS' | 'NOT_IN_REPORT' | 'FAILED';

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
}

/**
 * A create-screen field the page asks for (Component/s, required fields Jira has no default for), read with
 * the user's token — so the options are the ones that user may choose. `defaults` are preselected: Jira's
 * default, else the only option of a required field (option ids for OPTIONS).
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
  analyzeUpload: (file: File, options: UploadOptions, auth: JiraAuth, onProgress?: (p: UploadProgress) => void) =>
    new Promise<Analysis>((resolve, reject) => {
      const form = new FormData();
      form.append('file', file);
      const xhr = new XMLHttpRequest();
      xhr.open('POST', `${BASE}/analysis/upload?${uploadQuery(options)}`);
      Object.entries(tokenHeader(auth)).forEach(([name, value]) => xhr.setRequestHeader(name, value));
      xhr.upload.onprogress = (e) => {
        onProgress?.({ loaded: e.loaded, total: e.lengthComputable ? e.total : file.size });
      };
      xhr.upload.onload = () => onProgress?.({ loaded: file.size, total: file.size });
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
