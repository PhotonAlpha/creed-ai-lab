/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** CCRG pages: path prefix of the backend calls (default `/api/ccrg`); the dev proxy strips it. */
  readonly VITE_CCRG_API_BASE?: string;
  /** CCRG pages: the "Comparison Tool Guideline" wiki page; unset hides the banner. */
  readonly VITE_CCRG_GUIDELINE_URL?: string;
  /** CCRG GenReport: Confluence base URL a report's `_links.tinyui` is appended to. */
  readonly VITE_CCRG_CONFLUENCE_BASE?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
