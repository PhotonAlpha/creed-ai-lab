import type { ReviewStatus, RiskCategory, RiskLevel, TargetState } from '../../api/migrationRisk';
import { ApiError } from '../../api/client';

export const LEVEL_COLOR: Record<RiskLevel, string> = { HIGH: 'red', MEDIUM: 'orange', LOW: 'green' };

export const STATE_COLOR: Record<TargetState, string> = {
  UNCHANGED: 'success',
  MODIFIED: 'warning',
  RELOCATED: 'processing',
  MISSING: 'error',
  SPLIT: 'error',
  ABSENT: 'default',
};

export const REVIEW_COLOR: Record<ReviewStatus, string> = {
  PENDING: 'default',
  SAFE: 'success',
  ATTENTION: 'error',
  SYNCED: 'cyan',
};

/** The level each category carries (backend `RiskCategory.level`); NON_JAVA_FILE depends on repo2's copy. */
export const CATEGORY_LEVEL: Record<RiskCategory, RiskLevel | null> = {
  TARGET_MISSING: 'HIGH',
  TARGET_SPLIT: 'HIGH',
  MIGRATION_API: 'HIGH',
  CONFIG_CHANGED: 'HIGH',
  DEPENDENCY_CHANGED: 'HIGH',
  RELOCATED: 'MEDIUM',
  BOTH_MODIFIED: 'MEDIUM',
  DELETED_UPSTREAM: 'MEDIUM',
  UPSTREAM_REFACTORING: 'MEDIUM',
  TEMPLATE_CONVERTED: 'MEDIUM',
  NON_JAVA_FILE: null,
  AUTO_SYNC: 'LOW',
  ALREADY_SYNCED: 'LOW',
  CONFIG_IN_SYNC: 'LOW',
  NEW_CODE: 'LOW',
};

/** Form field names; the backend's `fields[].field` uses the same names (plus `path`/`head` for /refs). */
export interface FormValues {
  mode: 'GIT' | 'DIRECTORY';
  release: string;
  repo1Path: string;
  baseline: string;
  repo1Head: string;
  repo2Path: string;
  repo2Head: string;
  /** Optional second migrated repository / folder. */
  repo2bPath: string;
  repo2bHead: string;
  baselinePath: string;
  skipTests: boolean;
}

export const FORM_FIELDS: (keyof FormValues)[] =
  ['release', 'repo1Path', 'baseline', 'repo1Head', 'repo2Path', 'repo2Head', 'repo2bPath', 'repo2bHead', 'baselinePath'];

/**
 * Backend `fields` onto the inputs they belong to — a wrong baseline is marked on the baseline input, not
 * reported as a broken repository. `rename` maps /refs' `path`/`head` onto the repo the request was for.
 */
export function fieldErrors(e: unknown, rename: Partial<Record<string, keyof FormValues>> = {}) {
  const fields = e instanceof ApiError ? e.body?.fields ?? [] : [];
  return fields
    .map((f) => ({ name: (rename[f.field] ?? f.field) as keyof FormValues, errors: [f.message] }))
    .filter((f) => FORM_FIELDS.includes(f.name));
}
