import { parseError, requestAt } from './client';

/**
 * creed-resource-veracode (`com.creed.resource.veracode.api.dto`). A separate service from env-matrix,
 * reached through its own Vite proxy entry (`VITE_VERACODE_TARGET`). Keep these types in step with the
 * Java DTOs; there is no node mock for this contract — the Java module mocks Confluence and Jira itself.
 */

const BASE = '/api/veracode';

export type FindingStatus = 'EXISTS' | 'MISSING' | 'CREATED';

export interface IssueRef {
  key: string;
  summary: string;
  status: string | null;
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
  /** "table 2, row 4" — where on the Confluence page the finding's rows are. */
  locations: string[];
  status: FindingStatus;
  /** Jira's spelling of the version; null = the project has no such version (cannot be created). */
  jiraVersion: string | null;
  issues: IssueRef[];
}

/** Where the report came from. For an upload, `id` is the upload id and `title` the file name. */
export interface ReportPage {
  source: 'CONFLUENCE' | 'UPLOAD';
  id: string;
  title: string;
  version: number | null;
  url: string | null;
}

/**
 * Which report a follow-up request (create, export, re-analyse) is about. An upload lives in the
 * backend's memory for `veracode.upload.ttl` (2 h) and not across a restart — then 404 `upload_expired`.
 */
export type ReportSource = { pageId: string; uploadId?: undefined } | { uploadId: string; pageId?: undefined };

/** What the upload control accepts; the backend sniffs a file without one of these extensions. */
export const UPLOAD_ACCEPT = '.html,.htm,.csv,.xlsx,.xls';

export interface AnalysisCounts {
  total: number;
  exists: number;
  missing: number;
  created: number;
  versionNotInJira: number;
}

export interface Analysis {
  page: ReportPage;
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
  defaultPageId: string | null;
  projectKey: string;
  issueType: string;
  confluenceBaseUrl: string;
  jiraBaseUrl: string;
  mock: boolean;
}

const json = (body: unknown): RequestInit => ({ method: 'POST', body: JSON.stringify(body) });

export const veracodeApi = {
  settings: () => requestAt<VeracodeSettings>(BASE, '/settings'),

  /** `autoCreate` files every missing finding whose version exists in Jira, in the same request. */
  analyze: (source: ReportSource, autoCreate = false) =>
    requestAt<Analysis>(BASE, '/analysis', json({ ...source, autoCreate })),

  /**
   * Multipart, so not through `requestAt`: that sets a JSON Content-Type whenever there is a body, and
   * a multipart request must let the browser write its own boundary.
   */
  analyzeUpload: async (file: File, autoCreate = false) => {
    const form = new FormData();
    form.append('file', file);
    const response = await fetch(`${BASE}/analysis/upload?${new URLSearchParams({ autoCreate: String(autoCreate) })}`, {
      method: 'POST',
      body: form,
    });
    if (!response.ok) {
      throw await parseError(response);
    }
    return (await response.json()) as Analysis;
  },

  /**
   * Findings go by id: the server re-reads the page and re-checks Jira before creating, so a stale
   * screen or a double click comes back ALREADY_EXISTS instead of a duplicate.
   */
  createIssues: (source: ReportSource, findingIds: string[]) =>
    requestAt<{ results: CreateResult[] }>(BASE, '/jira/issues', json({ ...source, findingIds })),

  /**
   * A fresh analysis as .xlsx. Fetched rather than opened as a link so a failure shows the server's
   * message instead of a browser download of an error page.
   */
  exportXlsx: async (source: ReportSource) => {
    const query: Record<string, string> = source.uploadId
      ? { uploadId: source.uploadId }
      : { pageId: source.pageId ?? '' };
    const response = await fetch(`${BASE}/analysis/export?${new URLSearchParams(query)}`);
    if (!response.ok) {
      throw await parseError(response);
    }
    const disposition = response.headers.get('Content-Disposition') ?? '';
    const filename = /filename="?([^";]+)"?/.exec(disposition)?.[1] ?? 'veracode.xlsx';
    return { blob: await response.blob(), filename };
  },
};
