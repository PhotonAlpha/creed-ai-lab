import { request, toQuery } from './client';
import type { SplunkAuditRow, SplunkSession, SplunkSessionOptions, TotpCode, TotpInfo } from './types';

/**
 * Splunk session broker: a TOTP gates a server-side Splunk Web login, and the session cookie comes
 * back as a `document.cookie` script. Every call is audited server-side.
 */
export const splunkApi = {
  totp: () => request<TotpInfo>('/splunk/totp'),

  /** 404 `code_hidden` unless the server runs with `env-matrix.totp.expose-current-code`. */
  currentCode: () => request<TotpCode>('/splunk/totp/current'),

  /**
   * Logs into `target` (a target id; the server's default when omitted).
   * `options` overrides the target's cookie names and tunnel choice for this login only.
   * 400 unknown target / no tunnel / bad cookie name · 401 bad/replayed code · 429 locked out ·
   * 502 Splunk failed · 503 not configured. Every 400 and the 503 leave the code unused.
   */
  session: (code: string, target?: string, options: SplunkSessionOptions = {}) =>
    request<SplunkSession>('/splunk/session', { method: 'POST', body: JSON.stringify({ code, target, ...options }) }),

  audit: (limit = 50) => request<SplunkAuditRow[]>(`/splunk/audit${toQuery({ limit: String(limit) })}`),
};
