import type { ReviewStatus, RiskCategory, RiskLevel, TargetState } from '../../api/migrationRisk';
import { ApiError } from '../../api/client';

export const LEVEL_COLOR: Record<RiskLevel, string> = { HIGH: 'red', MEDIUM: 'orange', LOW: 'green' };

export const STATE_COLOR: Record<TargetState, string> = {
  UNCHANGED: 'success',
  MODIFIED: 'warning',
  RELOCATED: 'processing',
  MISSING: 'error',
  SPLIT: 'error',
  EXTRA: 'default',
  ABSENT: 'default',
};

export const REVIEW_COLOR: Record<ReviewStatus, string> = {
  PENDING: 'default',
  SAFE: 'success',
  ATTENTION: 'error',
  SYNCED: 'cyan',
};

/** The level each category carries (backend `RiskCategory.level`). */
export const CATEGORY_LEVEL: Record<RiskCategory, RiskLevel> = {
  TARGET_MISSING: 'HIGH',
  TARGET_SPLIT: 'HIGH',
  CONFIG_CHANGED: 'HIGH',
  DEPENDENCY_CHANGED: 'HIGH',
  CONTENT_DIFFERS: 'MEDIUM',
  RELOCATED: 'MEDIUM',
  MIGRATION_API: 'MEDIUM',
  TEMPLATE_CONVERTED: 'MEDIUM',
  NON_JAVA_FILE: 'MEDIUM',
  ONLY_IN_REPO2: 'LOW',
  ALREADY_SYNCED: 'LOW',
  CONFIG_IN_SYNC: 'LOW',
  // saved baseline runs
  BOTH_MODIFIED: 'MEDIUM',
  DELETED_UPSTREAM: 'MEDIUM',
  UPSTREAM_REFACTORING: 'MEDIUM',
  AUTO_SYNC: 'LOW',
  NEW_CODE: 'LOW',
};

/** Form field names; the backend's `fields[].field` uses the same names (plus `path`/`head` for /refs). */
export interface FormValues {
  mode: 'GIT' | 'DIRECTORY';
  release: string;
  repo1Path: string;
  repo1Head: string;
  repo2Path: string;
  repo2Head: string;
  /** Optional second migrated repository / folder. */
  repo2bPath: string;
  repo2bHead: string;
  skipTests: boolean;
}

export const FORM_FIELDS: (keyof FormValues)[] =
  ['release', 'repo1Path', 'repo1Head', 'repo2Path', 'repo2Head', 'repo2bPath', 'repo2bHead'];

/**
 * Backend `fields` onto the inputs they belong to — a wrong branch is marked on the branch input, not reported
 * as a broken repository. `rename` maps /refs' `path`/`head` onto the repo the request was for.
 */
export function fieldErrors(e: unknown, rename: Partial<Record<string, keyof FormValues>> = {}) {
  const fields = e instanceof ApiError ? e.body?.fields ?? [] : [];
  return fields
    .map((f) => ({ name: (rename[f.field] ?? f.field) as keyof FormValues, errors: [f.message] }))
    .filter((f) => FORM_FIELDS.includes(f.name));
}
