import type { ConfluenceAuth, JiraDeployment } from '../../api/veracode';

/**
 * The user's Confluence sign-in, kept like the Jira one (see `jiraToken`): `sessionStorage` unless the user asked
 * to remember it, then `localStorage`, never both; storage that throws leaves it in memory only.
 */
const KEY = 'veracode.confluenceToken';

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

export const emptyConfluenceAuth = (deployment: JiraDeployment): ConfluenceAuth => ({ deployment, token: '', email: '' });

const parse = (stored: string | null): ConfluenceAuth | null => {
  if (!stored) return null;
  try {
    const value = JSON.parse(stored) as Partial<ConfluenceAuth>;
    if (value && typeof value.token === 'string') {
      return { deployment: value.deployment === 'cloud' ? 'cloud' : 'dataCenter', token: value.token, email: value.email ?? '' };
    }
  } catch {
    // not ours
  }
  return null;
};

export const confluenceTokenStore = {
  load: (): { auth: ConfluenceAuth | null; remember: boolean } => {
    const remembered = parse(read(() => localStorage));
    if (remembered) return { auth: remembered, remember: true };
    return { auth: parse(read(() => sessionStorage)), remember: false };
  },
  save: (auth: ConfluenceAuth, remember: boolean) => {
    const value = JSON.stringify(auth);
    write(() => localStorage, remember ? value : null);
    write(() => sessionStorage, remember ? null : value);
  },
  clear: () => {
    write(() => localStorage, null);
    write(() => sessionStorage, null);
  },
};
