import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, relative } from 'node:path';
import { parse as parseYaml } from 'yaml';
import { formatIssues, moduleSchema, type ModuleDefinition } from './definition.js';

export interface LoadedModule {
  readonly definition: ModuleDefinition;
  /** Path relative to cwd — what shows up in logs and the admin API. */
  readonly source: string;
}

const EXTENSIONS = ['.yaml', '.yml', '.json'];

function listDefinitionFiles(dir: string): string[] {
  const out: string[] = [];
  for (const entry of readdirSync(dir, { withFileTypes: true }).sort((a, b) =>
    a.name.localeCompare(b.name),
  )) {
    const full = join(dir, entry.name);
    if (entry.isDirectory()) {
      out.push(...listDefinitionFiles(full));
    } else if (EXTENSIONS.some((ext) => entry.name.endsWith(ext))) {
      out.push(full);
    }
  }
  return out;
}

export function loadModules(dir: string): LoadedModule[] {
  let stats;
  try {
    stats = statSync(dir);
  } catch {
    throw new Error(
      `mock directory not found: ${dir}\n` +
        `Create it, or point CREED_MOCK_DIR somewhere else. See mocks/catalog.yaml for the format.`,
    );
  }
  if (!stats.isDirectory()) throw new Error(`CREED_MOCK_DIR is not a directory: ${dir}`);

  const files = listDefinitionFiles(dir);
  const modules: LoadedModule[] = [];
  const seenNames = new Map<string, string>();

  for (const file of files) {
    const source = relative(process.cwd(), file);
    let raw: unknown;
    try {
      raw = parseYaml(readFileSync(file, 'utf8'));
    } catch (cause) {
      throw new Error(`${source}: not valid YAML/JSON — ${(cause as Error).message}`);
    }
    if (raw === null || raw === undefined) continue; // empty file, ignore

    const parsed = moduleSchema.safeParse(raw);
    if (!parsed.success) {
      throw new Error(`${source}: invalid mock definition\n${formatIssues(parsed.error)}`);
    }

    const previous = seenNames.get(parsed.data.name);
    if (previous) {
      throw new Error(
        `${source}: duplicate module name "${parsed.data.name}" (already defined in ${previous})`,
      );
    }
    seenNames.set(parsed.data.name, source);
    modules.push({ definition: parsed.data, source });
  }

  return modules;
}
