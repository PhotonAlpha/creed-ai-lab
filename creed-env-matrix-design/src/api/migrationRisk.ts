import { requestAt, toQuery } from './client';

/**
 * creed-resource-migration-risk (`com.creed.resource.migrationrisk.api.dto` + `domain`). A separate service,
 * reached through its own Vite proxy entry (`VITE_MIGRATION_RISK_TARGET`). Keep these types in step with the
 * Java records; there is no node mock — the Java module ships its own demo repositories.
 */

const BASE = '/api/migration-risk';

export type RiskLevel = 'HIGH' | 'MEDIUM' | 'LOW';

/** Declared in the backend's order; the level is fixed per category except NON_JAVA_FILE. */
export const RISK_CATEGORIES = [
  'TARGET_MISSING',
  'TARGET_SPLIT',
  'MIGRATION_API',
  'CONFIG_CHANGED',
  'DEPENDENCY_CHANGED',
  'RELOCATED',
  'BOTH_MODIFIED',
  'DELETED_UPSTREAM',
  'UPSTREAM_REFACTORING',
  'NON_JAVA_FILE',
  'AUTO_SYNC',
  'NEW_CODE',
] as const;
export type RiskCategory = (typeof RISK_CATEGORIES)[number];

export type ElementKind = 'FILE' | 'CLASS' | 'METHOD' | 'FIELDS' | 'CONFIG' | 'DEPENDENCY';
export type UpstreamChange = 'ADDED' | 'MODIFIED' | 'DELETED' | 'REFACTORED';
export type TargetState = 'UNCHANGED' | 'MODIFIED' | 'RELOCATED' | 'MISSING' | 'SPLIT' | 'ABSENT';

export type ReviewStatus = 'PENDING' | 'SAFE' | 'ATTENTION';

/** A saved item's review. `carriedOverFrom`: copied from the release's previous run (item unchanged since). */
export interface Review {
  itemId: number;
  status: ReviewStatus;
  note: string | null;
  reviewer: string | null;
  reviewedAt: string | null;
  carriedOverFrom: number | null;
}

export interface Location {
  path: string;
  line: number | null;
  element: string | null;
}

export interface RiskItem {
  id: string;
  level: RiskLevel;
  category: RiskCategory;
  kind: ElementKind;
  /** `com.acme.OrderService#cancel(String)`, a class name, a file path ... */
  element: string;
  change: UpstreamChange;
  /** In repo1 (HEAD; the baseline for a deletion). */
  upstream: Location | null;
  /** In repo2, when found. */
  target: Location | null;
  /** Null where the question does not apply (config, API usage). */
  targetState: TargetState | null;
  /** RefactoringMiner refactorings / rule hints that decided the level, verbatim. */
  evidence: string[];
  /** Data lines: changed config keys, dependency versions, matched API lines ... */
  details: string[];
  /** Null unless the analysis was saved under a release. */
  review: Review | null;
}

export interface CodeLocation {
  path: string;
  startLine: number;
  endLine: number;
  kind: string;
  element: string;
}

export interface DetectedRefactoring {
  type: string;
  description: string;
  /** Moves / renames / extractions of classes and methods; the rest is migration detail. */
  structural: boolean;
  before: CodeLocation[];
  after: CodeLocation[];
}

export interface CommitView {
  revision: string;
  id: string;
  shortId: string;
  message: string;
  author: string;
  time: string;
}

export interface ChangedFileView {
  change: 'ADDED' | 'MODIFIED' | 'DELETED' | 'RENAMED';
  oldPath: string | null;
  newPath: string | null;
  kind: 'JAVA' | 'CONFIG' | 'POM' | 'OTHER';
  addedLines: number;
}

export type AnalysisMode = 'GIT' | 'DIRECTORY';

export interface AnalysisSource {
  mode: AnalysisMode;
  /** DIRECTORY mode only. */
  baselinePath: string | null;
  repo1Path: string;
  repo2Path: string;
  /** GIT mode only (null for folders). */
  baseline: CommitView | null;
  repo1Head: CommitView | null;
  repo2Head: CommitView | null;
}

export interface MigrationRiskAnalysis {
  /** Saved run id; null = not saved (no release given). */
  analysisId: number | null;
  release: string | null;
  source: AnalysisSource;
  counts: { total: number; high: number; medium: number; low: number };
  /** Riskiest first. */
  items: RiskItem[];
  upstreamRefactorings: DetectedRefactoring[];
  migrationRefactorings: DetectedRefactoring[];
  changedFiles: ChangedFileView[];
  warnings: string[];
  durationMillis: number;
  analyzedAt: string;
}

export interface AnalysisRequest {
  repo1Path: string;
  baseline: string;
  repo1Head?: string;
  repo2Path: string;
  repo2Head?: string;
  /** Saves the result under this release (created on first use); blank = not saved. */
  release?: string;
}

/** Folder mode: three plain directories, no git involved. */
export interface DirectoryAnalysisRequest {
  baselinePath: string;
  repo1Path: string;
  repo2Path: string;
  release?: string;
}

export interface MigrationRiskSettings {
  /** Null when the backend's demo repositories are disabled. */
  demo: {
    repo1Path: string;
    baseline: string;
    repo1Head: string;
    repo2Path: string;
    repo2Head: string;
    baselineFolder: string;
    repo1Folder: string;
    repo2Folder: string;
  } | null;
  allowedRoots: string[];
  apiRules: { id: string; pattern: string; hint: string | null }[];
  maxChangedFiles: number;
}

/** One repository's refs. `currentBranch` is null on a detached HEAD. */
export interface RefsView {
  currentBranch: string | null;
  head: CommitView;
  branches: string[];
  remoteBranches: string[];
  tags: string[];
  /** Counted back from the requested head. */
  recentCommits: CommitView[];
}

export interface BaselineSuggestion {
  /** Null when `reason` is NO_SHARED_HISTORY. */
  baseline: CommitView | null;
  /** MERGE_BASE: newest commit both heads descend from — the commit the migration branched from. */
  reason: 'MERGE_BASE' | 'NO_SHARED_HISTORY';
}

export interface ReviewProgress {
  pending: number;
  safe: number;
  attention: number;
}

export interface AnalysisSummary {
  id: number;
  release: string;
  mode: AnalysisMode;
  repo1Path: string;
  repo2Path: string;
  baselineRef: string;
  counts: MigrationRiskAnalysis['counts'];
  review: ReviewProgress;
  durationMillis: number;
  analyzedAt: string;
}

export interface ReleaseView {
  id: number;
  name: string;
  description: string | null;
  createdAt: string;
  analysisCount: number;
  latest: AnalysisSummary | null;
}

export interface ReviewRequest {
  status: ReviewStatus;
  note?: string;
  reviewer?: string;
}

const json = (body: unknown): RequestInit => ({ method: 'POST', body: JSON.stringify(body) });
const putJson = (body: unknown): RequestInit => ({ method: 'PUT', body: JSON.stringify(body) });

export const migrationRiskApi = {
  settings: () => requestAt<MigrationRiskSettings>(BASE, '/settings'),
  refs: (path: string, head?: string) =>
    requestAt<RefsView>(BASE, `/refs${toQuery({ path, head: head || undefined })}`),
  baselineSuggestion: (r: { repo1Path: string; repo1Head?: string; repo2Path: string; repo2Head?: string }) =>
    requestAt<BaselineSuggestion>(BASE, `/baseline-suggestion${toQuery({
      repo1Path: r.repo1Path,
      repo1Head: r.repo1Head || undefined,
      repo2Path: r.repo2Path,
      repo2Head: r.repo2Head || undefined,
    })}`),
  analyze: (request: AnalysisRequest) => requestAt<MigrationRiskAnalysis>(BASE, '/analyses', json(request)),
  analyzeDirectories: (request: DirectoryAnalysisRequest) =>
    requestAt<MigrationRiskAnalysis>(BASE, '/analyses/directories', json(request)),
  savedAnalysis: (id: number) => requestAt<MigrationRiskAnalysis>(BASE, `/analyses/${id}`),
  releases: () => requestAt<ReleaseView[]>(BASE, '/releases'),
  releaseAnalyses: (releaseId: number) => requestAt<AnalysisSummary[]>(BASE, `/releases/${releaseId}/analyses`),
  review: (itemId: number, review: ReviewRequest) =>
    requestAt<RiskItem>(BASE, `/items/${itemId}/review`, putJson(review)),
  reviewAll: (itemIds: number[], review: ReviewRequest) =>
    requestAt<RiskItem[]>(BASE, '/items/review', putJson({ itemIds, ...review })),
};

/**
 * The backend's Thymeleaf diff page (RefactoringMiner's side-by-side viewer) for one side of an analysis:
 * A = repo1 baseline → repo1 head, B = repo1 baseline → repo2. Commit ids, not branch names, so a saved
 * analysis keeps showing the code it was computed on. Under /api/migration-risk, so the same proxy serves it.
 */
export function diffUrl(source: AnalysisSource, side: 'A' | 'B', file?: string | null): string {
  const params = source.mode === 'DIRECTORY'
    ? { mode: 'DIRECTORY', baselinePath: source.baselinePath ?? undefined, repo1Path: source.repo1Path, repo2Path: source.repo2Path }
    : {
      mode: 'GIT',
      repo1Path: source.repo1Path,
      baseline: source.baseline?.id,
      repo1Head: source.repo1Head?.id,
      repo2Path: source.repo2Path,
      repo2Head: source.repo2Head?.id,
    };
  return `${BASE}/view/diff${toQuery({ ...params, side, file: file ?? undefined })}`;
}
