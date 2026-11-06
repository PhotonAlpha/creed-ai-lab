import type { JiraAuth, JiraDeployment } from '../../api/veracode';

/**
 * Where the page keeps the user's Jira sign-in (kind, token, Cloud e-mail, chosen issue type): `sessionStorage` (gone with the tab)
 * unless the user asked to remember it, then `localStorage`. Never both — saving to one clears the other. Storage
 * can be unavailable (private mode, blocked site data), so every access is guarded and the page then simply
 * holds it in memory.
 */
const KEY = 'veracode.jiraToken';

const read = (storage: () => Storage) => {
  try {
    return storage().getItem(KEY);
  } catch {
    return null;
  }
};

const write = (storage: () => Storage, value: string | null) => {
  try {
    if (value) storage().setItem(KEY, value);
    else storage().removeItem(KEY);
  } catch {
    // storage unavailable: the token lives in React state only
  }
};

export const emptyAuth = (deployment: JiraDeployment): JiraAuth => ({ deployment, token: '', email: '', issueType: '' });

/** JSON since Cloud support; a bare string is a token saved before it, which could only be Data Center's. */
const parse = (stored: string | null): JiraAuth | null => {
  if (!stored) return null;
  try {
    const value = JSON.parse(stored) as Partial<JiraAuth>;
    if (value && typeof value.token === 'string') {
      return {
        deployment: value.deployment === 'cloud' ? 'cloud' : 'dataCenter',
        token: value.token,
        email: value.email ?? '',
        issueType: value.issueType ?? '',
      };
    }
  } catch {
    // not JSON: the old format
  }
  return { deployment: 'dataCenter', token: stored, email: '', issueType: '' };
};

export const jiraTokenStore = {
  /** `null` when nothing is saved — the page then preselects the deployment the backend suggests. */
  load: (): { auth: JiraAuth | null; remember: boolean } => {
    const remembered = parse(read(() => localStorage));
    if (remembered) return { auth: remembered, remember: true };
    return { auth: parse(read(() => sessionStorage)), remember: false };
  },
  save: (auth: JiraAuth, remember: boolean) => {
    const value = JSON.stringify(auth);
    write(() => localStorage, remember ? value : null);
    write(() => sessionStorage, remember ? null : value);
  },
  clear: () => {
    write(() => localStorage, null);
    write(() => sessionStorage, null);
  },
};
