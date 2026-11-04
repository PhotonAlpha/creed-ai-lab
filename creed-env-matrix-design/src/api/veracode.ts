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
  affectedVersion: string;
  fixedVersion: string | null;
  description: string | null;
  /** "log4j-core 2.14.1 (app.war#zip:WEB-INF/lib/log4j-core-2.14.1.jar)" — the components in the report. */
  locations: string[];
  status: FindingStatus;
  /** Jira's spelling of the version; null = the project has no such version (cannot be created). */
  jiraVersion: string | null;
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
  versionNotInJira: number;
}

export interface Analysis {
  report: ReportView;
  projectKey: string;
  jiraVersions: string[];
  findings: Finding[];
  warnings: string[];
  counts: AnalysisCounts;
  analyzedAt: string;
}

export type CreateOutcome = 'CREATED' | 'ALREADY_EXISTS' | 'NOT_IN_REPORT' | 'VERSION_NOT_IN_JIRA' | 'FAILED';

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
  /** Creating issues needs the user's own Jira token (sent as `X-Jira-Token`). */
  requireUserToken: boolean;
}

/** `POST /jira/connection`: a rejected token is `ok: false` with Jira's status, not an error. */
export interface JiraConnection {
  ok: boolean;
  baseUrl: string;
  projectKey: string;
  user: string | null;
  displayName: string | null;
  projectAccess: boolean;
  /** Jira's HTTP status; 0 = Jira unreachable. */
  status: number;
  message: string | null;
}

/** The user's Jira access token travels per request in this header; the backend never stores it. */
export const JIRA_TOKEN_HEADER = 'X-Jira-Token';

const tokenHeader = (token?: string): Record<string, string> => (token ? { [JIRA_TOKEN_HEADER]: token } : {});

/** Spelled out because `requestAt` drops its own Content-Type as soon as `init.headers` is set. */
export interface UploadOptions {
  affectedVersion?: string;
  autoCreate?: boolean;
}

const uploadQuery = ({ affectedVersion, autoCreate }: UploadOptions) => new URLSearchParams({
  autoCreate: String(!!autoCreate),
  ...(affectedVersion?.trim() ? { affectedVersion: affectedVersion.trim() } : {}),
});

const json = (body: unknown, token?: string): RequestInit => ({
  method: 'POST',
  body: JSON.stringify(body),
  headers: { 'Content-Type': 'application/json', ...tokenHeader(token) },
});

export const veracodeApi = {
  settings: () => requestAt<VeracodeSettings>(BASE, '/settings'),

  /** Re-analyse an upload against Jira as it is now. `autoCreate` needs `token`; the lookup uses it when set. */
  analyze: (uploadId: string, autoCreate = false, token?: string) =>
    requestAt<Analysis>(BASE, '/analysis', json({ uploadId, autoCreate }, token)),

  /**
   * Multipart, so not through `requestAt`: that sets a JSON Content-Type whenever there is a body, and
   * a multipart request must let the browser write its own boundary. `affectedVersion` overrides the
   * report's `version` (blank = the report's).
   */
  analyzeUpload: async (file: File, options: UploadOptions, token?: string) => {
    const form = new FormData();
    form.append('file', file);
    const response = await fetch(`${BASE}/analysis/upload?${uploadQuery(options)}`, {
      method: 'POST',
      body: form,
      headers: tokenHeader(token),
    });
    if (!response.ok) {
      throw await parseError(response);
    }
    return (await response.json()) as Analysis;
  },

  /** The backend's bundled sample report, as if uploaded — only served with the mocks. */
  analyzeSample: (options: UploadOptions, token?: string) =>
    requestAt<Analysis>(BASE, `/analysis/sample?${uploadQuery(options)}`, { method: 'POST', headers: tokenHeader(token) }),

  /**
   * Findings go by id: the server re-reads the page and re-checks Jira before creating, so a stale
   * screen or a double click comes back ALREADY_EXISTS instead of a duplicate.
   */
  createIssues: (uploadId: string, findingIds: string[], token?: string) =>
    requestAt<{ results: CreateResult[] }>(BASE, '/jira/issues', json({ uploadId, findingIds }, token)),

  /** Who Jira says the token belongs to, and whether it sees the project. */
  testConnection: (token: string) =>
    requestAt<JiraConnection>(BASE, '/jira/connection', { method: 'POST', headers: tokenHeader(token) }),

  /**
   * A fresh analysis as .xlsx. Fetched rather than opened as a link so a failure shows the server's
   * message instead of a browser download of an error page.
   */
  exportXlsx: async (uploadId: string, token?: string) => {
    const response = await fetch(`${BASE}/analysis/export?${new URLSearchParams({ uploadId })}`, { headers: tokenHeader(token) });
    if (!response.ok) {
      throw await parseError(response);
    }
    const disposition = response.headers.get('Content-Disposition') ?? '';
    const filename = /filename="?([^";]+)"?/.exec(disposition)?.[1] ?? 'veracode.xlsx';
    return { blob: await response.blob(), filename };
  },
};
