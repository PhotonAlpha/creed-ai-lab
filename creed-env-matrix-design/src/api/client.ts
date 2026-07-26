/**
 * Thin fetch wrapper.
 *
 * The backend reports failures as `{error, message, fields?}` (see `EnvMatrixExceptionHandler`), so
 * errors are unwrapped into that message rather than surfacing "500 Internal Server Error" — the
 * difference between a toast that says "endpoint MS/UAT/... already exists as #42" and one that says
 * nothing useful.
 */

export interface ApiErrorBody {
  error?: string;
  message?: string;
  fields?: { field: string; message: string }[];
}

export class ApiError extends Error {
  readonly status: number;
  readonly body: ApiErrorBody | undefined;

  constructor(status: number, message: string, body?: ApiErrorBody) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.body = body;
  }
}

const BASE = '/api/env-matrix';

async function parseError(response: Response): Promise<ApiError> {
  let body: ApiErrorBody | undefined;
  try {
    body = (await response.json()) as ApiErrorBody;
  } catch {
    // Non-JSON error page (proxy failure, HTML 502, …) — fall through to the status text.
  }
  const fieldDetail = body?.fields?.map((f) => `${f.field}: ${f.message}`).join('; ');
  const message =
    [body?.message, fieldDetail].filter(Boolean).join(' — ') ||
    `${response.status} ${response.statusText}`;
  return new ApiError(response.status, message, body);
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`${BASE}${path}`, {
    headers: init?.body ? { 'Content-Type': 'application/json' } : undefined,
    ...init,
  });

  if (!response.ok) {
    throw await parseError(response);
  }
  if (response.status === 204) {
    return undefined as T;
  }
  return (await response.json()) as T;
}

/**
 * A response that is a *validation outcome* rather than an error: batch save answers 422 with a
 * populated `issues` array, which the config page must render inline instead of as a failure toast.
 */
async function requestAllowing422<T>(path: string, init: RequestInit): Promise<T> {
  const response = await fetch(`${BASE}${path}`, {
    headers: { 'Content-Type': 'application/json' },
    ...init,
  });
  if (!response.ok && response.status !== 422) {
    throw await parseError(response);
  }
  return (await response.json()) as T;
}

/** Builds `?tier=UAT&tier=SIT&scheme=https` from a filter object, skipping empty entries. */
function toQuery(params: Record<string, string[] | string | undefined>): string {
  const search = new URLSearchParams();
  Object.entries(params).forEach(([key, value]) => {
    if (value == null) return;
    if (Array.isArray(value)) {
      value.filter((v) => v !== '').forEach((v) => search.append(key, v));
    } else if (value !== '') {
      search.append(key, value);
    }
  });
  const query = search.toString();
  return query ? `?${query}` : '';
}

export { request, requestAllowing422, toQuery };
