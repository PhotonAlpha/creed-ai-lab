import { existsSync, readFileSync } from 'node:fs';
import { join } from 'node:path';

export const DEFAULT_COUNTRY_DIR = 'default';

export interface CountryData {
  readonly data: unknown;
  /** The file actually served, so the startup log says which country won. */
  readonly source: string;
}

/**
 * Reads `<baseDir>/<country>/<file>`, falling back to `<baseDir>/default/<file>` when the country
 * has no override — a new country only needs the files that differ. Read once at startup: the
 * country is fixed per process, and `npm run dev` restarts on any .json change anyway.
 */
export function loadCountryJson(baseDir: string, country: string, file: string): CountryData {
  const candidates = [country, DEFAULT_COUNTRY_DIR]
    .filter((dir, index, all) => dir !== '' && all.indexOf(dir) === index)
    .map((dir) => join(baseDir, dir, file));

  const source = candidates.find((path) => existsSync(path));
  if (!source) {
    // Fail at boot, not with an empty 200 on the first request.
    throw new Error(`mock json "${file}" not found; tried:\n  ${candidates.join('\n  ')}`);
  }
  return { data: JSON.parse(readFileSync(source, 'utf8')), source };
}
