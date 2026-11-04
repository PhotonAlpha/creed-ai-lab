/**
 * Where the page keeps the user's Jira access token: `sessionStorage` (gone with the tab) unless the user
 * asked to remember it, then `localStorage`. Never both — saving to one clears the other. Storage can be
 * unavailable (private mode, blocked site data), so every access is guarded and the page then simply
 * holds the token in memory.
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

export const jiraTokenStore = {
  load: (): { token: string; remember: boolean } => {
    const remembered = read(() => localStorage);
    if (remembered) return { token: remembered, remember: true };
    return { token: read(() => sessionStorage) ?? '', remember: false };
  },
  save: (token: string, remember: boolean) => {
    write(() => localStorage, remember ? token : null);
    write(() => sessionStorage, remember ? null : token);
  },
  clear: () => {
    write(() => localStorage, null);
    write(() => sessionStorage, null);
  },
};
