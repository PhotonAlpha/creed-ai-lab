#!/usr/bin/env node
/**
 * Regenerates the `paths:` (and `components.securitySchemes`) of every catalog/<service>/openapi.yaml
 * from the complete OpenAPI documents in catalog/<service>/paths/*.yml.
 *
 *   corepack yarn openapi:combine                    every module's openapi.yaml
 *   corepack yarn openapi:combine creed-payment      only that module (several names allowed)
 *   corepack yarn openapi:combine --check [modules]  exit 1 if any is out of date (CI)
 *
 * Each paths/*.yml is a whole document — openapi, info, paths, components — whose operations refer to
 * their own components as '#/components/...'. OpenAPI allows a $ref per path item, never one for the
 * whole `paths` object, so the entry document gets one line per path, pointing INTO the file:
 *
 *   /users/{userId}:
 *     $ref: ./paths/api-example.yml#/paths/~1users~1{userId}
 *
 * scripts/openapi-bundle.mjs (Redocly CLI) then follows those refs into catalog/<service>/dist/openapi.yaml,
 * the file the catalog reads; '#/components/...' inside a fragment resolves against that fragment, so
 * two files may both define `User`. Nothing is
 * merged here — this only writes the list, which by hand means escaping every '/' as '~1'.
 *
 * Only paths and security schemes are carried over: a fragment's info, servers, tags and top-level
 * security are its own, and the entry document's apply to the combined API.
 */
import { readdirSync, readFileSync, writeFileSync, existsSync } from 'node:fs';
import { join, relative } from 'node:path';
import YAML from 'yaml';
import { CATALOG as ROOT, cli, isMain } from './lib/modules.mjs';

/** RFC 6901: '~' → '~0', '/' → '~1'. */
const pointer = key => key.replace(/~/g, '~0').replace(/\//g, '~1');
const compareVersions = (a, b) =>
  a.localeCompare(b, undefined, { numeric: true });

/** @returns {{stale: number, failed: number}} */
export function combine({ modules, check = false }) {
  let stale = 0;
  let failed = 0;

  for (const name of modules) {
    const dir = join(ROOT, name);
    const entryFile = join(dir, 'openapi.yaml');
    const fragmentDir = join(dir, 'paths');
    if (!existsSync(entryFile) || !existsSync(fragmentDir)) continue;

    const fragments = readdirSync(fragmentDir)
      .filter(f => /\.ya?ml$/.test(f))
      .sort();
    const paths = new Map(); // path -> { ref, file }
    const schemes = new Map(); // scheme name -> { ref, file, definition }
    const versions = new Set();
    const errors = [];

    for (const file of fragments) {
      const doc = YAML.parse(readFileSync(join(fragmentDir, file), 'utf8'));
      if (!doc || typeof doc !== 'object' || !doc.openapi || !doc.paths) {
        errors.push(
          `paths/${file}: not a complete OpenAPI document (needs openapi and paths)`,
        );
        continue;
      }
      versions.add(String(doc.openapi));
      for (const key of Object.keys(doc.paths)) {
        const previous = paths.get(key);
        if (previous) {
          errors.push(
            `path ${key} is defined in both paths/${previous.file} and paths/${file}`,
          );
          continue;
        }
        paths.set(key, { ref: `./paths/${file}#/paths/${pointer(key)}`, file });
      }
      for (const [name, definition] of Object.entries(
        doc.components?.securitySchemes ?? {},
      )) {
        const previous = schemes.get(name);
        if (
          previous &&
          JSON.stringify(previous.definition) !== JSON.stringify(definition)
        ) {
          errors.push(
            `security scheme ${name} differs between paths/${previous.file} and paths/${file}`,
          );
          continue;
        }
        if (!previous) {
          schemes.set(name, {
            ref: `./paths/${file}#/components/securitySchemes/${pointer(name)}`,
            file,
            definition,
          });
        }
      }
    }

    const label = relative(join(ROOT, '..'), entryFile);
    if (errors.length) {
      failed++;
      console.error(`✗ ${label}\n${errors.map(e => `    ${e}`).join('\n')}`);
      continue;
    }

    // Edit the entry document in place, so its comments, info and servers survive.
    const source = readFileSync(entryFile, 'utf8');
    const entry = YAML.parseDocument(source);
    const version = [...versions].sort(compareVersions).at(-1);
    if (versions.size > 1) {
      console.warn(
        `! ${label}: fragments mix OpenAPI ${[...versions].join(
          ', ',
        )}; the combined document says ${version}`,
      );
    }
    if (version) entry.set('openapi', version);
    entry.set(
      'paths',
      Object.fromEntries(
        [...paths].map(([key, { ref }]) => [key, { $ref: ref }]),
      ),
    );
    if (schemes.size) {
      entry.setIn(
        ['components', 'securitySchemes'],
        Object.fromEntries(
          [...schemes].map(([name, { ref }]) => [name, { $ref: ref }]),
        ),
      );
    } else if (entry.hasIn(['components', 'securitySchemes'])) {
      entry.deleteIn(['components', 'securitySchemes']);
      if (entry.get('components')?.items?.length === 0)
        entry.delete('components');
    }
    const output = entry.toString({ lineWidth: 0 });

    if (output === source) {
      console.log(
        `✓ ${label} — ${paths.size} paths from ${fragments.length} files, up to date`,
      );
    } else if (check) {
      stale++;
      console.error(
        `✗ ${label} is out of date — run corepack yarn openapi:combine`,
      );
    } else {
      writeFileSync(entryFile, output);
      console.log(
        `✎ ${label} — ${paths.size} paths from ${fragments.length} files`,
      );
    }
  }

  return { stale, failed };
}

if (isMain(import.meta.url)) await cli(combine);
