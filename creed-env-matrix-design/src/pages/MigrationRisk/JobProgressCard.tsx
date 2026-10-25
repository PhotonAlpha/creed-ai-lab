import { useEffect, useState } from 'react';
import { ProCard } from '@ant-design/pro-components';
import { Button, Progress, Space, Steps, Typography } from 'antd';
import { StopOutlined } from '@ant-design/icons';
import { JOB_STAGES } from '../../api/migrationRisk';
import type { JobProgress, JobStage } from '../../api/migrationRisk';
import { useI18n } from '../../locales';
import type { MessageKey } from '../../locales';

const { Text } = Typography;

/** Shown while a job runs; DONE is not a step of its own — the card disappears with the result. */
const VISIBLE: JobStage[] = JOB_STAGES.filter((s) => s !== 'DONE');

/** The RefactoringMiner runs are one call each with no progress inside: say so, and let the clock tick. */
const OPAQUE: JobStage[] = ['UPSTREAM', 'MIGRATION'];

const seconds = (ms: number) => `${Math.floor(ms / 1000)}s`;

interface Props {
  progress: JobProgress | null;
  startedAt: number | null;
  onCancel: () => void;
}

export function JobProgressCard({ progress, startedAt, onCancel }: Props) {
  const { t } = useI18n();
  const [now, setNow] = useState(Date.now());

  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(timer);
  }, []);

  const stage = progress?.stage ?? 'RESOLVE';
  const index = Math.max(0, VISIBLE.indexOf(stage));
  const elapsed = startedAt ? now - startedAt : 0;

  return (
    <ProCard bordered title={t('migrationRisk.job.title')}
      extra={<Button size="small" icon={<StopOutlined />} onClick={onCancel}>{t('migrationRisk.job.cancel')}</Button>}>
      <Space direction="vertical" size="middle" style={{ width: '100%' }}>
        <Steps
          size="small"
          current={index}
          items={VISIBLE.map((s) => ({ title: t(`migrationRisk.job.stage.${s}` as MessageKey) }))}
        />
        <Progress
          percent={progress?.percent ?? 0}
          status="active"
          format={(p) => `${p ?? 0}%`}
        />
        <Space wrap size={4}>
          <Text strong>{t(`migrationRisk.job.stage.${stage}` as MessageKey)}</Text>
          {progress?.total != null && progress.done != null && (
            <Text>{`${Math.min(progress.done + 1, progress.total)} / ${progress.total}`}</Text>
          )}
          {progress?.detail && <Text type="secondary" code>{progress.detail}</Text>}
        </Space>
        <Text type="secondary">
          {t('migrationRisk.job.elapsed', { time: seconds(elapsed) })}
          {OPAQUE.includes(stage) && ` · ${t('migrationRisk.job.opaque')}`}
        </Text>
      </Space>
    </ProCard>
  );
}
