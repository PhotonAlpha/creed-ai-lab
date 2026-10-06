/**
 * Catalog modules: every directory `catalog/<module>/` that holds an `openapi.yaml`. A module is one
 * functional area — creed-env-matrix, creed-payment, … — with its own catalog-info.yaml (System,
 * Component, API), its own `paths/*.yml` and its own `dist/openapi.yaml` bundle.
 *
 * Every openapi:* script takes the same arguments:
 *
 *   <script>                        all modules
 *   <script> creed-payment          only the named module(s)
 *   <script> --check [modules…]     report instead of writing
 */
import { existsSync, readdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

export const REPO = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
export const CATALOG = join(REPO, 'catalog');

/** A Backstage entity name, and a directory name: lowercase words joined by '-'. */
export const MODULE_NAME = /^[a-z0-9]+(-[a-z0-9]+)*$/;

/** Module names, sorted. */
export function listModules() {
  return readdirSync(CATALOG, { withFileTypes: true })
    .filter((d) => d.isDirectory() && existsSync(join(CATALOG, d.name, 'openapi.yaml')))
    .map((d) => d.name)
    .sort();
}

/** `--check` plus the positional module names; an unknown name is an error, not a silent no-op. */
export function parseArgs(argv = process.argv.slice(2)) {
  const check = argv.includes('--check');
  const unknownFlag = argv.find((a) => a.startsWith('-') && a !== '--check');
  if (unknownFlag) throw new Error(`unknown option ${unknownFlag}`);
  const names = [...new Set(argv.filter((a) => !a.startsWith('-')))];
  const available = listModules();
  const missing = names.filter((n) => !available.includes(n));
  if (missing.length) {
    throw new Error(`no catalog module ${missing.map((m) => `'${m}'`).join(', ')} — available: ${available.join(', ') || '(none)'}`);
  }
  return { check, modules: names.length ? names : available };
}

/** Runs `main` as a CLI: argument errors print one line and exit 2. */
export async function cli(main) {
  let args;
  try {
    args = parseArgs();
  } catch (e) {
    console.error(`✗ ${e.message}`);
    process.exit(2);
  }
  const { failed, stale } = await main(args);
  process.exit(failed ? 2 : stale ? 1 : 0);
}

/** True when this module file is the script node was started with (vs. imported). */
export const isMain = (metaUrl) => process.argv[1] && fileURLToPath(metaUrl) === process.argv[1];
