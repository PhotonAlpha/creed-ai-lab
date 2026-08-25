import { readFileSync } from 'node:fs';

/**
 * Read at runtime rather than imported, so the value is identical whether we are running from
 * `src/` under tsx or from `dist/` after a tsup build — both sit one level below package.json.
 */
function read(): { name: string; version: string; description: string } {
  try {
    const raw = readFileSync(new URL('../package.json', import.meta.url), 'utf8');
    const pkg = JSON.parse(raw) as Partial<{ name: string; version: string; description: string }>;
    return {
      name: pkg.name ?? 'creed-mock-buddy',
      version: pkg.version ?? '0.0.0',
      description: pkg.description ?? '',
    };
  } catch {
    return { name: 'creed-mock-buddy', version: '0.0.0', description: '' };
  }
}

export const pkg = read();
