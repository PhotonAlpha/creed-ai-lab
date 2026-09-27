import { request, toQuery } from './client';
import type { SplunkAuditRow, SplunkSession, TotpCode, TotpInfo } from './types';

/**
 * Splunk session broker: a TOTP gates a server-side Splunk Web login, and the session cookie comes
 * back as a `document.cookie` script. Every call is audited server-side.
 */
export const splunkApi = {
  totp: () => request<TotpInfo>('/splunk/totp'),

  /** 404 `code_hidden` unless the server runs with `env-matrix.totp.expose-current-code`. */
  currentCode: () => request<TotpCode>('/splunk/totp/current'),

  /** 401 bad/replayed code · 429 locked out · 502 Splunk failed · 503 not configured. */
  session: (code: string) =>
    request<SplunkSession>('/splunk/session', { method: 'POST', body: JSON.stringify({ code }) }),

  audit: (limit = 50) => request<SplunkAuditRow[]>(`/splunk/audit${toQuery({ limit: String(limit) })}`),
};
