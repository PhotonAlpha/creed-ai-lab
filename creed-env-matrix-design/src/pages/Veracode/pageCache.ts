import type { Analysis } from '../../api/veracode';

/**
 * The page's state across a reload: the last analysis and what produced it (Jira versions, create-screen values,
 * filters, page size). `sessionStorage`, so it lives as long as the tab — a report's findings are not something
 * to leave in the browser for good, and the backend keeps the upload only `veracode.upload.ttl` (2 h) anyway.
 *
 * A big analysis can exceed the storage quota (about 5 MB): the state is then kept without it, with its upload id,
 * and the page re-analyses that upload on load instead of showing it from the cache.
 */
const KEY = 'veracode.page';

export interface CachedPage<F> {
  analysis: Analysis | null;
  /** Set when `analysis` did not fit: re-analyse this upload. */
  uploadId: string | null;
  savedAt: number;
  /** Everything else the page restores (versions, fields, filters ...), owned by the page. */
  view: F;
}

export const pageCache = {
  load<F>(): CachedPage<F> | null {
    try {
      const raw = sessionStorage.getItem(KEY);
      return raw ? (JSON.parse(raw) as CachedPage<F>) : null;
    } catch {
      return null;
    }
  },

  save<F>(analysis: Analysis | null, view: F) {
    const write = (value: CachedPage<F>) => sessionStorage.setItem(KEY, JSON.stringify(value));
    try {
      write({ analysis, uploadId: null, savedAt: Date.now(), view });
    } catch {
      // over the quota (or storage blocked): keep the small part, the upload id stands in for the analysis
      try {
        write({ analysis: null, uploadId: analysis?.report.id ?? null, savedAt: Date.now(), view });
      } catch {
        // storage unavailable: the page simply starts empty after a reload
      }
    }
  },

  clear() {
    try {
      sessionStorage.removeItem(KEY);
    } catch {
      // nothing stored
    }
  },
};
