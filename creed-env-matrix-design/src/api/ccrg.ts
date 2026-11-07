import { ApiError, toQuery } from './client';

/**
 * ccrg-tool-starter (`creed-resource/ccrg-tool-starter`, `com.creed.parent`) — the config-comparison
 * backend the old Vue `ccrg-tool-ui` talked to. It serves everything under `/api/...` on :8600, which
 * would collide with env-matrix's own `/api` proxy, so the browser calls `/api/ccrg/...` and the Vite
 * proxy (`VITE_CCRG_TARGET`) strips the `/ccrg` again.
 *
 * Every JSON answer is the `R<T>` envelope `{code, data, msg}`. Success is `code = 0`
 * (`GlobalErrorCodeConstants.SUCCESS`); the old UI only worked because it read `data.code || 200` and
 * 0 is falsy. 200 is accepted too — the old static mock files use it.
 */

const BASE = '/api/ccrg';

interface Envelope<T> {
  code?: number;
  data: T;
  msg?: string;
}

function isSuccess(code: number | undefined): boolean {
  return code == null || code === 0 || code === 200;
}

async function call<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`${BASE}${path}`, {
    headers: init?.body && !(init.body instanceof FormData) ? { 'Content-Type': 'application/json' } : undefined,
    ...init,
  });
  let body: Envelope<T> | undefined;
  try {
    body = (await response.json()) as Envelope<T>;
  } catch {
    // HTML error page from the proxy — fall through to the status line.
  }
  if (!response.ok || !body || !isSuccess(body.code)) {
    throw new ApiError(response.status, body?.msg || `${response.status} ${response.statusText}`);
  }
  return body.data;
}

const get = <T>(path: string) => call<T>(path);
const post = <T>(path: string, data?: unknown) =>
  call<T>(path, { method: 'POST', body: data === undefined ? undefined : JSON.stringify(data) });

/** A generated file: the name comes from `Content-Disposition`, set by `outputFileDownload`. */
export interface DownloadedFile {
  blob: Blob;
  filename: string;
}

/**
 * Report endpoints answer a file on success and an `R` envelope (application/json) on failure, with
 * HTTP 200 either way — so the content type, not the status, says which one arrived.
 */
async function download(path: string, init: RequestInit, fallbackName: string): Promise<DownloadedFile> {
  const response = await fetch(`${BASE}${path}`, init);
  const type = response.headers.get('content-type') ?? '';
  if (!response.ok || type.startsWith('application/json')) {
    let msg = `${response.status} ${response.statusText}`;
    try {
      msg = ((await response.json()) as Envelope<unknown>).msg || msg;
    } catch {
      // not JSON after all
    }
    throw new ApiError(response.status, msg);
  }
  const disposition = response.headers.get('content-disposition') ?? '';
  const match = /filename[^;=\n]*=((['"]).*?\2|[^;\n]*)/.exec(disposition);
  const filename = match?.[1] ? match[1].replace(/['"]/g, '') : fallbackName;
  return { blob: await response.blob(), filename };
}

export function saveFile({ blob, filename }: DownloadedFile): void {
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  a.click();
  URL.revokeObjectURL(url);
}

/** One option of a cascading dropdown (`SelectedOptionVo`). `selected` marks the backend's default pick. */
export interface SelectorVo {
  id?: number;
  name?: string;
  value?: string;
  parentId?: number;
  parentName?: string;
  selected?: boolean;
  created?: string;
  uiPath?: string;
  repositoryPath?: string;
}

/** The level `/v2/system/dict/path-walk` answers for — one step down the config folder tree. */
export type WalkPathType = 'COMMON' | 'ENVIRONMENT' | 'APP' | 'INSTANCE' | 'MODULE' | 'ZONE';

export interface WalkPathInfo {
  dictType: WalkPathType;
  projectName: string;
  releaseName?: string;
  countryCode?: string;
  app?: string;
  environment?: string;
  instance?: string;
}

// ---------------------------------------------------------------------------------------------
// Pull config (install / uninstall a release from Artifactory)

export interface InstalledInfoVo {
  parentName?: string;
  name?: string;
  releaseName?: string;
  lastUpdateTime?: string;
  children?: InstalledInfoVo[];
}

export interface WalkPathInfoVo {
  projectName?: string;
  branchName?: string;
  releaseName?: string;
  buildNoName?: string;
  countryCode?: string;
}

// ---------------------------------------------------------------------------------------------
// Comparison reports

/** One side of a Batch/Crossover combination, as the report endpoints take it. */
export interface RouteInfoVo {
  releaseName: string;
  projectName: string;
  countryCode: string;
  app: string;
  environment: string;
  instance: string;
  node: string;
  /** Comma-joined zone names. */
  zone: string;
}

export interface BatchFormVo {
  connectionId?: string;
  batch: { compareAll: boolean; baseRelease: string; routes: RouteInfoVo[] }[];
}

/** instance name -> its folders, keyed by the "value" the user picks. */
export type InstanceMatrix = Record<string, unknown>;

export interface PromptCombinationRequest {
  compareAll?: boolean;
  baseRelease?: string;
  projectName: string;
  baselineRelease: string;
  comparisonRelease: string;
  countryCode?: string;
  app: string;
  baselineInstanceMatrix?: unknown;
  comparisonInstanceMatrix?: unknown;
  baselineValue?: string;
  comparisonValue?: string;
}

export interface PromptCombinationResponse {
  baselineInstanceMatrix: Record<string, unknown>;
  comparisonInstanceMatrix: Record<string, unknown>;
}

export interface ConfluenceContent {
  id: string;
  type: string;
  createdDate?: string;
  status?: string;
  title: string;
  _links?: { webui?: string; edit?: string; tinyui?: string; self?: string };
}

export interface PageResult<T> {
  list: T[];
  total: number;
}

// ---------------------------------------------------------------------------------------------
// Dict (the config-folder tree the cascades walk)

export type DictStatus = 0 | 1;

export interface DictVO {
  id?: number;
  name: string;
  parentId: number;
  sort: number;
  nodeId?: number;
  zoneId?: number;
  status: DictStatus;
  level?: number;
}

export interface ExtraTypeVO {
  id: number;
  name: string;
  val: string;
}

export const ccrgApi = {
  // dict v2 — what the cascades use
  projects: () => get<SelectorVo[]>(`/v2/system/dict/list-select${toQuery({ parentName: 'idb-modules' })}`),
  pathWalk: (info: WalkPathInfo) => post<SelectorVo[]>('/v2/system/dict/path-walk', info),

  // pull config
  installed: () => get<InstalledInfoVo[]>('/admin/install/list-all'),
  releases: (info: WalkPathInfoVo) => post<SelectorVo[]>('/admin/artifactory/list-releases', info),
  builds: (info: WalkPathInfoVo) => post<SelectorVo[]>('/admin/artifactory/list-builds', info),
  install: (info: { projectName: string; releaseName: string; buildNo: string }) =>
    post<string>('/admin/install', info),
  uninstall: (uninstallList: WalkPathInfoVo[]) => post<WalkPathInfoVo[]>('/admin/uninstall', { uninstallList }),

  /** `/admin/artifactory/shortcut/{repo}/{branch}/{release}` — each segment optional, left to right. */
  shortcut: (...segments: (string | undefined)[]) => {
    const parts = segments
      .map((s) => s?.trim())
      .filter((s): s is string => !!s)
      .map(encodeURIComponent);
    return get<SelectorVo[]>(`/admin/artifactory/shortcut${parts.length ? `/${parts.join('/')}` : ''}`);
  },

  decrypt: (symmetricKey: string, encryptedData: string) =>
    post<string>('/secure/decrypt', { symmetricKey, encryptedData }),

  // GenReport (Confluence)
  listInstances: (req: PromptCombinationRequest) => post<PromptCombinationResponse>('/prompt/list-instances', req),
  generative: (connectionId: string, batch: PromptCombinationRequest[], type: 'xlsx' | 'html') =>
    post<unknown>(`/prompt/generative${toQuery({ type })}`, { connectionId, batch }),
  listReports: (pageNo: number, pageSize: number) =>
    get<PageResult<ConfluenceContent>>(`/prompt/list-reports${toQuery({ pageNo: String(pageNo), pageSize: String(pageSize) })}`),
  mergeReports: (mergeIds: string[]) => post<unknown>('/prompt/merge-reports', { mergeIds }),
  deleteReports: (mergeIds: string[]) => post<unknown>('/prompt/delete-reports', { mergeIds }),

  // Batch / Crossover
  batchXlsx: (form: BatchFormVo) =>
    download('/system/report/generate-report-batch', jsonPost(form), 'report.xlsx'),
  batchHtml: (form: BatchFormVo) => download('/system/report/generate-pdf-batch', jsonPost(form), 'report.zip'),

  // dict v1 — the management page
  dictList: (name?: string) => get<DictVO[]>(`/system/dict/list${toQuery({ name })}`),
  dictSimple: () => get<DictVO[]>('/system/dict/list-all-simple'),
  dictExtraTypes: (param: 'NODE' | 'ZONE') => get<ExtraTypeVO[]>(`/system/dict/list-all-types${toQuery({ param })}`),
  dictGet: (id: number) => get<DictVO>(`/system/dict/get${toQuery({ id: String(id) })}`),
  dictCreate: (dict: DictVO) => post<number>('/system/dict/create', dict),
  dictUpdate: (dict: DictVO) => call<boolean>('/system/dict/update', { method: 'PUT', body: JSON.stringify(dict) }),
  dictDelete: (id: number) => call<boolean>(`/system/dict/delete${toQuery({ id: String(id) })}`, { method: 'DELETE' }),
  dictCopy: (id: number) => post<number>(`/system/dict/copy${toQuery({ id: String(id) })}`),
  dictDownloadSql: () => download('/system/dict/download-sql', {}, 'data.sql'),
  dictImport: (file: File) => {
    const body = new FormData();
    body.append('file', file);
    return call<boolean>('/system/dict/import-json-config', { method: 'POST', body });
  },
};

function jsonPost(data: unknown): RequestInit {
  return { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(data) };
}

/** Progress feed for a long report run: the backend pushes plain-text `message` events, then `end`. */
export function openProgress(connectionId: string, onMessage: (text: string) => void): EventSource {
  const source = new EventSource(`${BASE}/sse/${encodeURIComponent(connectionId)}`);
  source.onmessage = (e) => onMessage(String(e.data));
  source.addEventListener('end', () => source.close());
  source.onerror = () => source.close();
  return source;
}
