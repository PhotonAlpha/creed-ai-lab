#!/usr/bin/env node
/**
 * Scaffolds a catalog module — one functional area with its own System, Component and API entity:
 *
 *   corepack yarn catalog:new creed-payment --title Payment --component creed-resource-payment \
 *     --server http://localhost:18093/api/payment
 *
 * writes
 *
 *   catalog/<module>/catalog-info.yaml     System <module>, Component <component>, API <module>-api
 *   catalog/<module>/openapi.yaml          entry document (info + servers; paths GENERATED)
 *   catalog/<module>/paths/<short>-health.yml   a starter GET /ping — replace it with the real paths
 *   catalog/<module>/dist/openapi.yaml     the bundle, built right away
 *
 * and adds the module's catalog-info.yaml to catalog/all.yaml — unless `--no-register`, for registering
 * it by hand instead (Backstage → Register Existing Component, with the URL the script prints): an
 * entity listed in all.yaml AND registered in the UI is claimed by two locations and the second fails. <short> is the module name without a
 * leading `creed-`; it is also the module's tag. Refuses to touch an existing module directory.
 *
 * Options (all optional): --title, --description, --component (default creed-resource-<short>),
 * --server (default http://localhost:8080/api/<short>), --owner (default group:default/creed-platform).
 */
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { join, relative } from 'node:path';
import YAML from 'yaml';
import { CATALOG, MODULE_NAME, REPO } from './lib/modules.mjs';
import { combine } from './openapi-combine.mjs';
import { bundle } from './openapi-bundle.mjs';

function fail(message) {
  console.error(`✗ ${message}`);
  process.exit(2);
}

const argv = process.argv.slice(2);
const options = {};
const positional = [];
for (let i = 0; i < argv.length; i++) {
  const arg = argv[i];
  if (!arg.startsWith('--')) {
    positional.push(arg);
    continue;
  }
  if (arg === '--no-register') {
    options.noRegister = true;
    continue;
  }
  const [key, inline] = arg.slice(2).split(/=(.*)/s);
  const value = inline ?? argv[++i];
  if (!['title', 'description', 'component', 'server', 'owner'].includes(key))
    fail(`unknown option --${key}`);
  if (value == null || value.startsWith('--')) fail(`--${key} needs a value`);
  options[key] = value;
}
if (positional.length !== 1)
  fail('usage: catalog:new <module> [--title …] [--component …] [--server …]');

const module = positional[0];
if (!MODULE_NAME.test(module))
  fail(
    `module name '${module}' must be lowercase words joined by '-' (e.g. creed-payment)`,
  );
const short = module.replace(/^creed-/, '');
const component = options.component ?? `creed-resource-${short}`;
if (!MODULE_NAME.test(component))
  fail(`component name '${component}' must be lowercase words joined by '-'`);
const title =
  options.title ??
  short.replace(
    /(^|-)(\w)/g,
    (_, sep, c) => (sep ? ' ' : '') + c.toUpperCase(),
  );
const description = options.description ?? `${title} — functional module`;
const server = options.server ?? `http://localhost:8080/api/${short}`;
const owner = options.owner ?? 'group:default/creed-platform';

const dir = join(CATALOG, module);
if (existsSync(dir)) fail(`${relative(REPO, dir)} already exists`);

// Values go through YAML.stringify so a title with ':' or quotes stays valid YAML. lineWidth 0: a folded
// long value would continue at column 0 of the template and break the document.
const q = value => YAML.stringify(value, { lineWidth: 0 }).trimEnd();

const catalogInfo = `# Module ${module}: one System, the Component that implements it, and the API it provides.
# Scaffolded by \`corepack yarn catalog:new\`. The API definition is the Redocly bundle in dist/ —
# edit paths/*.yml, then \`corepack yarn openapi:build ${module}\` and commit dist/ too.
apiVersion: backstage.io/v1alpha1
kind: System
metadata:
  name: ${module}
  title: ${q(title)}
  description: ${q(description)}
  tags: [${short}]
spec:
  owner: ${owner}
---
apiVersion: backstage.io/v1alpha1
kind: Component
metadata:
  name: ${component}
  title: ${component}
  description: ${q(`Service implementing ${title}`)}
  tags: [${short}]
spec:
  type: service
  lifecycle: experimental
  owner: ${owner}
  system: ${module}
  providesApis:
    - ${module}-api
---
apiVersion: backstage.io/v1alpha1
kind: API
metadata:
  name: ${module}-api
  title: ${q(`${title} API`)}
  description: ${q(`REST API of ${component}`)}
  tags: [${short}, rest]
spec:
  type: openapi
  lifecycle: experimental
  owner: ${owner}
  system: ${module}
  definition:
    # The single-file bundle of ./openapi.yaml + paths/*.yml (\`corepack yarn openapi:build ${module}\`),
    # committed. $text inserts it verbatim; ./openapi.yaml itself would reach Swagger UI with $refs.
    $text: ./dist/openapi.yaml
`;

const entry = `# The entry document of the ${title} API. Every file in paths/ is a complete OpenAPI document; this
# one combines their paths. \`paths:\` and \`components.securitySchemes\` below are GENERATED — run
#   corepack yarn openapi:build ${module}
# after adding, removing or changing a file in paths/. Edit info and servers here by hand.
openapi: 3.1.0
info:
  title: ${q(`${title} API`)}
  version: 1.0.0
  description: ${q(
    `${component} — combined from catalog/${module}/paths/*.yml`,
  )}
servers:
  - url: ${q(server)}
paths: {}
`;

const starter = `# Starter fragment from \`catalog:new\` — replace it with the module's real endpoints. Each file in
# paths/ is a complete OpenAPI document; give every operation a tag (Swagger UI filters on them).
openapi: 3.1.0
info:
  title: ${q(`${title} — health`)}
  version: 1.0.0
paths:
  /ping:
    get:
      tags: [health]
      summary: Liveness
      operationId: ping
      responses:
        '200':
          description: Service is up
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/Ping'
components:
  schemas:
    Ping:
      type: object
      properties:
        service: { type: string, example: ${component} }
        status: { type: string, example: UP }
        time: { type: string, format: date-time }
`;

mkdirSync(join(dir, 'paths'), { recursive: true });
writeFileSync(join(dir, 'catalog-info.yaml'), catalogInfo);
writeFileSync(join(dir, 'openapi.yaml'), entry);
writeFileSync(join(dir, 'paths', `${short}-health.yml`), starter);
console.log(
  `✎ ${relative(
    REPO,
    dir,
  )}/{catalog-info.yaml,openapi.yaml,paths/${short}-health.yml}`,
);

// Register it — edited as a YAML document so all.yaml's comments survive.
const allFile = join(CATALOG, 'all.yaml');
if (!options.noRegister) {
  const all = YAML.parseDocument(readFileSync(allFile, 'utf8'));
  const targets = all.getIn(['spec', 'targets']);
  const target = `./${module}/catalog-info.yaml`;
  if (!targets.items.some(t => (t.value ?? t) === target)) {
    targets.add(target);
    writeFileSync(allFile, all.toString({ lineWidth: 0 }));
    console.log(`✎ ${relative(REPO, allFile)} — added ${target}`);
  }
}

const combined = combine({ modules: [module] });
const bundled = combined.failed ? combined : bundle({ modules: [module] });
if (bundled.failed) process.exit(2);
console.log(
  `\nNext: replace paths/${short}-health.yml with the real endpoints, then`,
);
console.log(`  corepack yarn openapi:build ${module}`);
if (options.noRegister) {
  console.log(
    `and commit catalog/${module}/ (dist/ included). Not added to catalog/all.yaml — register it in`,
  );
  console.log(
    'Backstage → Create → Register Existing Component with this URL (backend running):',
  );
  console.log(
    `  http://localhost:7007/api/catalog-files/${module}/catalog-info.yaml`,
  );
} else {
  console.log(
    `and commit catalog/${module}/ (dist/ included) and catalog/all.yaml.`,
  );
}
