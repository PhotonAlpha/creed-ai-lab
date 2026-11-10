import { Typography } from 'antd';
import type { CSSProperties } from 'react';
import type { ApplicationView } from '../../api/veracode';

/**
 * A Veracode application under its mapped name, linked (`veracode.applications` in the backend config:
 * `GBT-T1-Creed-CCS` → `Creed-CCS`). Without a mapping — or in an analysis cached before the field existed — the
 * `app_name` itself, as text.
 */
export function ApplicationName({ app, apps, style }: { app: string | null | undefined; apps?: ApplicationView[]; style?: CSSProperties }) {
  if (!app) return <>—</>;
  const view = apps?.find((a) => a.appName === app);
  const name = view?.name ?? app;
  // the app_name stays findable: it is what the report and the filters say
  const title = name === app ? undefined : app;
  return view?.url
    ? <a href={view.url} target="_blank" rel="noreferrer" title={title} style={style}>{name}</a>
    : <Typography.Text title={title} style={style}>{name}</Typography.Text>;
}

/** The mapped name as plain text (filter options, sort keys). */
export const applicationLabel = (app: string | null | undefined, apps?: ApplicationView[]) =>
  (app ? apps?.find((a) => a.appName === app)?.name ?? app : '');
