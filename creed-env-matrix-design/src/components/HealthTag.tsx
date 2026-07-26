import { Badge, Tag } from 'antd';
import { useI18n } from '../locales';
import type { HealthState } from '../api/types';

/** antd `Badge` status / `Tag` colour per health state. */
const BADGE_STATUS: Record<HealthState, 'success' | 'warning' | 'error' | 'default'> = {
  UP: 'success',
  DEGRADED: 'warning',
  DOWN: 'error',
  UNKNOWN: 'default',
};

const TAG_COLOR: Record<HealthState, string> = {
  UP: 'success',
  DEGRADED: 'warning',
  DOWN: 'error',
  UNKNOWN: 'default',
};

/** Compact dot — used inside matrix cells where space is tight. */
export function HealthDot({ health }: { health: HealthState }) {
  const { t } = useI18n();
  return <Badge status={BADGE_STATUS[health]} title={t(`health.${health}`)} />;
}

/** Labelled tag — used in the config table where there is room for words. */
export function HealthTag({ health }: { health: HealthState }) {
  const { t } = useI18n();
  return (
    <Tag color={TAG_COLOR[health]} bordered={false}>
      {t(`health.${health}`)}
    </Tag>
  );
}
