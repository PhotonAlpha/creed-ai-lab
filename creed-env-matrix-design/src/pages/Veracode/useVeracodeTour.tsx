import { useCallback, useEffect, useRef, useState } from 'react';
import { Button, Tour, Tooltip } from 'antd';
import type { TourProps } from 'antd';
import { CompassOutlined } from '@ant-design/icons';
import { useI18n } from '../../locales';
import type { MessageKey } from '../../locales';

/**
 * The page's guided tour (asked for, 2026-11-16: on first use). Two parts, because most of the page appears only with
 * an analysis: `intro` on the first visit (where reports come from, the Jira token, the lookup conditions, the create
 * fields), `results` the first time an analysis is on screen (the summary, the template export, create / label, the
 * filters, SAST). Each plays once per browser - marked when the tour closes, so a reload in the middle shows it again -
 * and the header button replays both. Steps point at `data-tour` attributes, and one whose element is not on the page
 * (no Jira fields yet, a hidden button) is left out rather than shown pointing at nothing.
 */
type Part = 'intro' | 'results';

const STORAGE_KEY = 'veracode.tour';

const STEPS: Record<Part, string[]> = {
  intro: ['source', 'jiraToken', 'query', 'jiraFields', 'tokenFile', 'help'],
  results: ['reports', 'templateExport', 'create', 'filters', 'sastTab'],
};

/** A per-browser convenience: storage refused (private window, blocked site data) = the tour shows again. */
const readSeen = (): Partial<Record<Part, boolean>> => {
  try {
    return JSON.parse(localStorage.getItem(STORAGE_KEY) ?? '{}') as Partial<Record<Part, boolean>>;
  } catch {
    return {};
  }
};

const markSeen = (parts: Part[]) => {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify({ ...readSeen(), ...Object.fromEntries(parts.map((p) => [p, true])) }));
  } catch {
    // shown again next time - harmless
  }
};

const target = (id: string) => document.querySelector<HTMLElement>(`[data-tour="${id}"]`);

/**
 * @param ready       the page has drawn its sections (settings read): the intro waits for it
 * @param hasAnalysis an analysis is on screen: the results part waits for it
 * @param busy        something else holds the screen (analysing, a dialog): no tour starts meanwhile
 */
export function useVeracodeTour({ ready, hasAnalysis, busy }: { ready: boolean; hasAnalysis: boolean; busy: boolean }) {
  const { t } = useI18n();
  const [open, setOpen] = useState(false);
  const [current, setCurrent] = useState(0);
  const [steps, setSteps] = useState<NonNullable<TourProps['steps']>>([]);
  const playing = useRef<Part[]>([]);

  const start = useCallback((parts: Part[]) => {
    const next = parts.flatMap((part) => STEPS[part]).filter((id) => target(id)).map((id) => ({
      title: t(`veracode.tour.${id}.title` as MessageKey),
      description: t(`veracode.tour.${id}.description` as MessageKey),
      target: () => target(id) as HTMLElement,
    }));
    playing.current = parts;
    if (next.length === 0) {
      markSeen(parts);
      return;
    }
    setSteps(next);
    setCurrent(0);
    setOpen(true);
  }, [t]);

  const close = useCallback(() => {
    setOpen(false);
    markSeen(playing.current);
  }, []);

  // first visit, then the first analysis; a short delay lets the sections (and the analysis tables) lay out first
  useEffect(() => {
    if (open || busy || !ready) return undefined;
    const seen = readSeen();
    const part: Part | null = !seen.intro ? 'intro' : hasAnalysis && !seen.results ? 'results' : null;
    if (!part) return undefined;
    const timer = window.setTimeout(() => start([part]), 600);
    return () => window.clearTimeout(timer);
  }, [open, busy, ready, hasAnalysis, start]);

  const element = (
    <Tour
      open={open}
      current={current}
      onChange={setCurrent}
      onClose={close}
      onFinish={close}
      steps={steps}
      indicatorsRender={(index, total) => <span>{index + 1} / {total}</span>}
    />
  );

  const button = (
    <Tooltip title={t('veracode.tour.buttonHint')}>
      <Button icon={<CompassOutlined />} data-tour="help" onClick={() => start(hasAnalysis ? ['intro', 'results'] : ['intro'])}>
        {t('veracode.tour.button')}
      </Button>
    </Tooltip>
  );

  return { element, button };
}
