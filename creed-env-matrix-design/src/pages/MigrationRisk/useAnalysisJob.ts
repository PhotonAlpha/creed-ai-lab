import { useCallback, useEffect, useRef, useState } from 'react';
import { jobEventsUrl, migrationRiskApi } from '../../api/migrationRisk';
import type { JobProgress, MigrationRiskAnalysis } from '../../api/migrationRisk';
import { ApiError } from '../../api/client';
import type { ApiErrorBody } from '../../api/client';

/**
 * Runs an analysis as a backend job and follows it over Server-Sent Events.
 *
 * `run` resolves with the result once the `done` event arrives (the result itself is fetched with one GET — it
 * can be megabytes, too big for an event frame), and rejects with an ApiError carrying the usual
 * `{error, message, fields?}` body on `failed`, so field errors land on their inputs exactly as for a
 * synchronous call. EventSource reconnects by itself; the backend replays the job's stages on every connect.
 */
export function useAnalysisJob() {
  const [progress, setProgress] = useState<JobProgress | null>(null);
  const [running, setRunning] = useState(false);
  const [startedAt, setStartedAt] = useState<number | null>(null);
  const current = useRef<{ jobId: string; source: EventSource } | null>(null);

  const close = () => {
    current.current?.source.close();
    current.current = null;
  };

  useEffect(() => close, []);

  const run = useCallback(async (start: () => Promise<{ jobId: string }>): Promise<MigrationRiskAnalysis> => {
    close();
    setProgress(null);
    setRunning(true);
    setStartedAt(Date.now());
    try {
      const { jobId } = await start();
      return await new Promise<MigrationRiskAnalysis>((resolve, reject) => {
        const source = new EventSource(jobEventsUrl(jobId));
        current.current = { jobId, source };
        const isCurrent = () => current.current?.jobId === jobId;
        const onProgress = (e: MessageEvent) => {
          if (isCurrent()) setProgress(JSON.parse(e.data) as JobProgress);
        };
        /** The terminal state comes from the job itself, so a missed `done`/`failed` frame cannot strand the page. */
        const settle = async () => {
          source.close();
          try {
            const job = await migrationRiskApi.job(jobId);
            if (job.status === 'DONE' && job.result) {
              resolve(job.result);
            } else if (job.status === 'FAILED' || job.status === 'CANCELLED') {
              const body = (job.error ?? undefined) as ApiErrorBody | undefined;
              reject(new ApiError(job.status === 'CANCELLED' ? 409 : 500, body?.message ?? job.status, body));
            } else {
              reject(new Error(`job ${jobId} is still ${job.status}`));
            }
          } catch (e) {
            reject(e);
          }
        };
        source.addEventListener('stage', onProgress);
        source.addEventListener('progress', onProgress);
        source.addEventListener('done', () => void settle());
        source.addEventListener('failed', () => void settle());
        source.onerror = () => {
          // CONNECTING = the browser retries on its own; CLOSED = it gave up (e.g. the job expired): ask once.
          if (source.readyState === EventSource.CLOSED && isCurrent()) void settle();
        };
      });
    } finally {
      setRunning(false);
    }
  }, []);

  const cancel = useCallback(async () => {
    const job = current.current;
    if (job) await migrationRiskApi.cancelJob(job.jobId);
  }, []);

  return { run, cancel, progress, running, startedAt };
}
