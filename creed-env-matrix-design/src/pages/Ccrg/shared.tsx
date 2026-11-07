import { useCallback, useEffect, useRef, useState } from 'react';
import type { ReactNode } from 'react';
import { Alert, Spin } from 'antd';
import { openProgress } from '../../api/ccrg';
import type { SelectorVo } from '../../api/ccrg';
import { useI18n } from '../../locales';

/**
 * The CCRG pages — the old Vue `ccrg-tool-ui` migrated onto this shell. They talk to
 * `ccrg-tool-starter` through `api/ccrg.ts`; nothing here touches env-matrix data.
 */

/** Where the "Comparison Tool Guideline" banner points. Internal wiki, so it is configuration, not code. */
export const GUIDELINE_URL: string | undefined = import.meta.env.VITE_CCRG_GUIDELINE_URL || undefined;

/**
 * Confluence base a report's `_links.tinyui` is relative to. The old page had a mangled literal here
 * (two hosts pasted into one URL), so every "view" opened a dead link.
 */
export const CONFLUENCE_BASE: string | undefined = import.meta.env.VITE_CCRG_CONFLUENCE_BASE || undefined;

export function GuidelineBanner() {
  const { t } = useI18n();
  if (!GUIDELINE_URL) return null;
  return (
    <Alert
      type="success"
      showIcon
      message={
        <a href={GUIDELINE_URL} target="_blank" rel="noreferrer">
          {t('ccrg.guideline')}
        </a>
      }
    />
  );
}

export const toOptions = (selector: SelectorVo[] | undefined) =>
  (selector ?? [])
    .filter((s) => s.name)
    .map((s) => ({ label: s.name!, value: s.name! }));

/** localStorage that never throws: private windows and blocked storage just lose the saved form. */
export const storage = {
  load<T>(key: string): T | undefined {
    try {
      const raw = localStorage.getItem(key);
      return raw ? (JSON.parse(raw) as T) : undefined;
    } catch {
      return undefined;
    }
  },
  save(key: string, value: unknown): boolean {
    try {
      localStorage.setItem(key, JSON.stringify(value));
      return true;
    } catch {
      return false;
    }
  },
};

/** Same random id shape the backend's SSE pool keys on. */
export const newConnectionId = () =>
  typeof crypto.randomUUID === 'function'
    ? crypto.randomUUID()
    : `${Date.now().toString(16)}-${Math.random().toString(16).slice(2)}`;

/**
 * Full-screen "working" overlay for report runs, which take minutes: the elapsed seconds tick locally
 * and, with `sse`, the backend's progress messages (`/sse/{connectionId}`) replace the text.
 * Returns the overlay element and `run`, which owns the EventSource's whole lifetime.
 */
export function useProgressOverlay(): [ReactNode, <T>(task: (connectionId?: string) => Promise<T>, sse?: boolean) => Promise<T>] {
  const { t } = useI18n();
  const [active, setActive] = useState(false);
  const [text, setText] = useState('');
  const [seconds, setSeconds] = useState(0);
  const sourceRef = useRef<EventSource | null>(null);

  useEffect(() => () => sourceRef.current?.close(), []);

  useEffect(() => {
    if (!active) return undefined;
    const timer = setInterval(() => setSeconds((s) => s + 1), 1000);
    return () => clearInterval(timer);
  }, [active]);

  const run = useCallback(async <T,>(task: (connectionId?: string) => Promise<T>, sse = false): Promise<T> => {
    setText('');
    setSeconds(0);
    setActive(true);
    let connectionId: string | undefined;
    if (sse) {
      connectionId = newConnectionId();
      sourceRef.current = openProgress(connectionId, setText);
    }
    try {
      return await task(connectionId);
    } finally {
      sourceRef.current?.close();
      sourceRef.current = null;
      setActive(false);
    }
  }, []);

  const overlay = (
    <Spin fullscreen spinning={active} tip={`${text || t('ccrg.working')} · ${t('ccrg.elapsed', { seconds })}`} />
  );
  return [overlay, run];
}

/** Error text for a failed call: `ApiError` already carries the backend's `msg`. */
export const errorText = (e: unknown) => (e instanceof Error ? e.message : String(e));
