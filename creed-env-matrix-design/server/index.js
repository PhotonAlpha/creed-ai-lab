#!/usr/bin/env node
/**
 * Mock API for the Env Matrix Viewer.
 *
 * Serves the same contract as creed-resource-env-matrix on the same port (3001), so `npm run dev`
 * works with no database, no JDK and no Docker. `mock.json` is the single source of truth and is
 * rewritten on every mutation — that is what makes the config page's "save" observable here.
 *
 * The conflict and mocked-health rules are ports of the Java implementations
 * (`ConflictDetector`, `HealthProbeService`), including Java's exact string hash, so a given
 * host:port reports the same health state in both backends.
 *
 *   node server/index.js            # :3001
 *   PORT=4001 node server/index.js
 */
import { createServer } from 'node:http';
import { readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const DATA_FILE = join(HERE, 'mock.json');
const PORT = Number(process.env.PORT ?? 3001);
const CONFLICT_SCOPE = process.env.CONFLICT_SCOPE ?? 'TIER_ENV';

const DIMENSIONS = [
  'appSystem',
  'tier',
  'envInstance',
  'country',
  'service',
  'instance',
  'scheme',
];

// ---------------------------------------------------------------- data access

let endpoints = [];
let releases = [];
let releaseNodes = [];
let releaseLinks = [];
let nextId = 1;
let nextReleaseId = 1;
let nextNodeId = 1;
let nextLinkId = 1;
let mockSeed = 0;

const maxId = (rows) => rows.reduce((max, r) => Math.max(max, r.id), 0) + 1;

function loadData() {
  const parsed = JSON.parse(readFileSync(DATA_FILE, 'utf8'));
  endpoints = parsed.endpoints ?? [];
  // Release topology. Absent from an older mock.json just means "nothing declared yet".
  releases = parsed.releases ?? [];
  releaseNodes = parsed.releaseNodes ?? [];
  releaseLinks = parsed.releaseLinks ?? [];
  nextId = maxId(endpoints);
  nextReleaseId = maxId(releases);
  nextNodeId = maxId(releaseNodes);
  nextLinkId = maxId(releaseLinks);
  console.log(
    `[mock] loaded ${endpoints.length} endpoints, ${releases.length} releases ` +
      `(${releaseNodes.length} participants / ${releaseLinks.length} links) from ${DATA_FILE}`,
  );
}

function persist() {
  writeFileSync(
    DATA_FILE,
    `${JSON.stringify({ endpoints, releases, releaseNodes, releaseLinks }, null, 2)}\n`,
  );
}

// ------------------------------------------------------------- release topology

const RELEASE_STATUSES = ['DRAFT', 'ACTIVE', 'ARCHIVED'];
const LINK_DIRECTIONS = ['ONE_WAY', 'BIDIRECTIONAL'];

/** Mirrors ReleaseRequest's bean validation, field for field. */
function validateRelease(payload) {
  for (const field of ['name', 'tier']) {
    if (!payload[field] || String(payload[field]).trim() === '') {
      return { field, message: 'must not be blank' };
    }
  }
  if (!RELEASE_STATUSES.includes(payload.status)) {
    return { field: 'status', message: `must be one of ${RELEASE_STATUSES.join(', ')}` };
  }
  return null;
}

const nodesOf = (releaseId) =>
  releaseNodes
    .filter((n) => n.releaseId === releaseId)
    .sort(
      (a, b) =>
        a.appSystem.localeCompare(b.appSystem) ||
        a.country.localeCompare(b.country) ||
        a.envInstance.localeCompare(b.envInstance),
    );

const linksOf = (releaseId) =>
  releaseLinks.filter((l) => l.releaseId === releaseId).sort((a, b) => a.id - b.id);

function releaseDto(release) {
  return {
    ...release,
    nodeCount: nodesOf(release.id).length,
    linkCount: linksOf(release.id).length,
  };
}

const nodeDto = ({ id, appSystem, country, envInstance, label, note, layer, sortOrder }) => ({
  id,
  appSystem,
  country,
  envInstance,
  label: label ?? null,
  note: note ?? null,
  // `layer` stays null when nobody pinned it — that is the graph's "derive it from the links", and
  // a 0 here would read as "column 0". `sortOrder` always has a number; 0 is the default order.
  layer: layer ?? null,
  sortOrder: sortOrder ?? 0,
});

const linkDto = ({ id, sourceNodeId, targetNodeId, direction, note }) => ({
  id,
  sourceNodeId,
  targetNodeId,
  direction,
  note: note ?? null,
});

/** Canonical key for a link end, or null when it resolves to nothing in the payload. */
function endOf(ref, payloadIds, refs) {
  if (!ref || (ref.id == null && !ref.ref)) return null;
  if (ref.id != null) return payloadIds.has(ref.id) ? `id:${ref.id}` : null;
  return refs.has(ref.ref) ? `ref:${ref.ref}` : null;
}

const describeRef = (ref) => (ref?.id != null ? `id:${ref.id}` : `ref:${ref?.ref}`);

/** Bounds for a pinned layer and a sort order — ReleaseService.MAX_LAYER / MAX_SORT_ORDER, and V5. */
const MAX_LAYER = 99;
const MAX_SORT_ORDER = 999;

/** Port of ReleaseService.validate — same rules, same issue shape, same ordering. */
function validateTopology(releaseId, nodes, links) {
  const issues = [];
  const knownNodeIds = new Set(nodesOf(releaseId).map((n) => n.id));
  const knownLinkIds = new Set(linksOf(releaseId).map((l) => l.id));
  const identities = new Set();
  const refs = new Set();
  const payloadIds = new Set();

  nodes.forEach((row, index) => {
    if (row.id != null && !knownNodeIds.has(row.id)) {
      issues.push({ section: 'nodes', index, id: row.id, field: 'id',
        message: `participant ${row.id} does not belong to this release` });
      return;
    }
    if (row.id != null) payloadIds.add(row.id);
    for (const field of ['appSystem', 'country', 'envInstance']) {
      if (!row[field] || String(row[field]).trim() === '') {
        issues.push({ section: 'nodes', index, id: row.id ?? null, field,
          message: 'must not be blank' });
        return;
      }
    }
    if (row.layer != null && (!Number.isInteger(row.layer) || row.layer < 0 || row.layer > MAX_LAYER)) {
      issues.push({ section: 'nodes', index, id: row.id ?? null, field: 'layer',
        message: `layer must be between 0 and ${MAX_LAYER}` });
    }
    if (row.sortOrder != null
        && (!Number.isInteger(row.sortOrder)
            || row.sortOrder < -MAX_SORT_ORDER || row.sortOrder > MAX_SORT_ORDER)) {
      issues.push({ section: 'nodes', index, id: row.id ?? null, field: 'sortOrder',
        message: `sortOrder must be between -${MAX_SORT_ORDER} and ${MAX_SORT_ORDER}` });
    }
    const identity = `${row.appSystem}|${row.country}|${row.envInstance}`;
    if (identities.has(identity)) {
      issues.push({ section: 'nodes', index, id: row.id ?? null, field: 'appSystem',
        message: `duplicate participant ${identity.replaceAll('|', ' ')}` });
    }
    identities.add(identity);
    if (row.ref) {
      if (refs.has(row.ref)) {
        issues.push({ section: 'nodes', index, id: row.id ?? null, field: 'ref',
          message: `duplicate ref ${row.ref}` });
      }
      refs.add(row.ref);
    }
  });

  const pairs = new Set();
  links.forEach((row, index) => {
    if (row.id != null && !knownLinkIds.has(row.id)) {
      issues.push({ section: 'links', index, id: row.id, field: 'id',
        message: `link ${row.id} does not belong to this release` });
      return;
    }
    if (!LINK_DIRECTIONS.includes(row.direction)) {
      issues.push({ section: 'links', index, id: row.id ?? null, field: 'direction',
        message: `must be one of ${LINK_DIRECTIONS.join(', ')}` });
      return;
    }
    const source = endOf(row.source, payloadIds, refs);
    const target = endOf(row.target, payloadIds, refs);
    if (!source) {
      issues.push({ section: 'links', index, id: row.id ?? null, field: 'source',
        message: `no participant in this payload matches ${describeRef(row.source)}` });
      return;
    }
    if (!target) {
      issues.push({ section: 'links', index, id: row.id ?? null, field: 'target',
        message: `no participant in this payload matches ${describeRef(row.target)}` });
      return;
    }
    if (source === target) {
      issues.push({ section: 'links', index, id: row.id ?? null, field: 'target',
        message: 'a link cannot start and end at the same participant' });
      return;
    }
    if (pairs.has(`${source}>${target}`) || pairs.has(`${target}>${source}`)) {
      issues.push({ section: 'links', index, id: row.id ?? null, field: 'target',
        message: 'these two participants are already connected' });
      return;
    }
    pairs.add(`${source}>${target}`);
  });

  return issues;
}

// ------------------------------------------------------------------ filtering
// ------------------------------------------------------------------ filtering

function matchesFilter(endpoint, params) {
  for (const dimension of DIMENSIONS) {
    const selected = params.getAll(dimension).filter(Boolean);
    if (selected.length && !selected.includes(endpoint[dimension])) {
      return false;
    }
  }
  const keyword = params.get('keyword');
  if (keyword) {
    const needle = keyword.toLowerCase();
    const haystack = [endpoint.host, endpoint.ip, endpoint.service, endpoint.note ?? ''];
    if (!haystack.some((field) => String(field).toLowerCase().includes(needle))) {
      return false;
    }
  }
  return true;
}

/** Same ordering as the backend's DEFAULT_SORT, so both produce identical row sequences. */
const SORT_KEYS = ['appSystem', 'tier', 'envInstance', 'service', 'country', 'instance', 'scheme'];

function sorted(list) {
  return [...list].sort((a, b) => {
    for (const key of SORT_KEYS) {
      const diff = String(a[key]).localeCompare(String(b[key]));
      if (diff !== 0) return diff;
    }
    return 0;
  });
}

function filtered(params) {
  return sorted(endpoints.filter((endpoint) => matchesFilter(endpoint, params)));
}

// ----------------------------------------------------------- conflict detection

function scopeKeyOf(endpoint) {
  if (CONFLICT_SCOPE === 'GLOBAL') return '*';
  if (CONFLICT_SCOPE === 'TIER') return endpoint.tier;
  return `${endpoint.tier}/${endpoint.envInstance}`;
}

const hostPort = (e) => `${e.host}:${e.port}`;
const ipPort = (e) => `${e.ip}:${e.port}`;

function groupsFor(list, kind, keyFn) {
  const buckets = new Map();
  for (const endpoint of list) {
    const scope = scopeKeyOf(endpoint);
    if (!buckets.has(scope)) buckets.set(scope, new Map());
    const byAddress = buckets.get(scope);
    const address = keyFn(endpoint);
    if (!byAddress.has(address)) byAddress.set(address, []);
    byAddress.get(address).push(endpoint);
  }
  const groups = [];
  for (const [scope, byAddress] of buckets) {
    for (const [address, members] of byAddress) {
      if (members.length > 1) {
        groups.push({ kind, scopeKey: scope, value: address, members });
      }
    }
  }
  return groups;
}

/**
 * When a set shares both hostname and IP, both groups have identical membership. Keep the
 * host:port one — it names the thing an operator will actually change.
 */
function dedupe(groups) {
  const slotByMembership = new Map();
  const result = [];
  for (const group of groups) {
    const membership = group.members
      .map((m) => m.id)
      .sort((a, b) => a - b)
      .join(',');
    const slot = slotByMembership.get(membership);
    if (slot === undefined) {
      slotByMembership.set(membership, result.length);
      result.push(group);
    } else if (result[slot].kind === 'IP_PORT' && group.kind === 'HOST_PORT') {
      result[slot] = group;
    }
  }
  return result;
}

function detectConflicts(list) {
  const groups = dedupe([
    ...groupsFor(list, 'HOST_PORT', hostPort),
    ...groupsFor(list, 'IP_PORT', ipPort),
  ]);
  const keysById = new Map();
  for (const group of groups) {
    const label = `${group.kind === 'HOST_PORT' ? 'host:port' : 'ip:port'} ${group.value}`;
    for (const member of group.members) {
      if (!keysById.has(member.id)) keysById.set(member.id, []);
      keysById.get(member.id).push(label);
    }
  }
  return { groups, keysById };
}

// -------------------------------------------------------------- mocked health

/** Java's String.hashCode, so mock states match the Spring backend exactly. */
function javaStringHash(value) {
  let hash = 0;
  for (let i = 0; i < value.length; i += 1) {
    hash = (Math.imul(31, hash) + value.charCodeAt(i)) | 0;
  }
  return hash;
}

/** Port of `Math.floorMod(Long.hashCode(seed * 31L + hostPort.hashCode()), 100)`. */
function mockBucket(seed, address) {
  const value = BigInt.asUintN(64, BigInt(seed) * 31n + BigInt(javaStringHash(address)));
  const folded = BigInt.asIntN(32, (value & 0xffffffffn) ^ (value >> 32n));
  return Number(((folded % 100n) + 100n) % 100n);
}

function healthOf(endpoint) {
  const bucket = mockBucket(mockSeed, hostPort(endpoint));
  if (bucket < 80) return 'UP';
  return bucket < 92 ? 'DEGRADED' : 'DOWN';
}

// ------------------------------------------------------------------- mapping

function toDto(endpoint, conflicts) {
  const conflictKeys = conflicts.keysById.get(endpoint.id) ?? [];
  return {
    ...endpoint,
    note: endpoint.note ?? null,
    url: `${endpoint.scheme}://${endpoint.host}:${endpoint.port}`,
    conflict: conflictKeys.length > 0,
    conflictKeys,
    health: healthOf(endpoint),
    createdAt: endpoint.createdAt ?? new Date(0).toISOString(),
    updatedAt: endpoint.updatedAt ?? new Date(0).toISOString(),
    version: endpoint.version ?? 0,
  };
}

function toGroupDtos(conflicts) {
  return conflicts.groups.map((group) => ({
    kind: group.kind,
    scopeKey: group.scopeKey,
    value: group.value,
    endpoints: group.members.map((member) => toDto(member, conflicts)),
  }));
}

const dimensionTuple = (e) => DIMENSIONS.map((d) => e[d]).join('/');

// -------------------------------------------------------------------- routing

const json = (res, status, body) => {
  res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8' });
  res.end(JSON.stringify(body));
};

const fail = (res, status, error, message) =>
  json(res, status, { error, message, time: new Date().toISOString() });

/** Mirrors the backend's @NotBlank/@Pattern/@Min-@Max constraints. */
function validate(payload) {
  for (const field of [...DIMENSIONS, 'host', 'ip']) {
    if (!payload[field] || String(payload[field]).trim() === '') {
      return { field, message: 'must not be blank' };
    }
  }
  if (!['http', 'https'].includes(payload.scheme)) {
    return { field: 'scheme', message: "scheme must be 'http' or 'https'" };
  }
  const port = Number(payload.port);
  if (!Number.isInteger(port) || port < 1 || port > 65535) {
    return { field: 'port', message: 'port must be between 1 and 65535' };
  }
  return null;
}

/** True when the submitted row would actually change any stored field. */
function differs(stored, payload) {
  const note = payload.note?.trim() ? payload.note.trim() : null;
  return (
    [...DIMENSIONS, 'host', 'ip'].some(
      (field) =>
        stored[field] !==
        (field === 'scheme'
          ? String(payload[field]).trim().toLowerCase()
          : String(payload[field]).trim()),
    ) ||
    stored.port !== Number(payload.port) ||
    (stored.note ?? null) !== note
  );
}

function applyPayload(target, payload) {
  for (const field of [...DIMENSIONS, 'host', 'ip']) {
    target[field] = String(payload[field]).trim();
  }
  target.scheme = target.scheme.toLowerCase();
  target.port = Number(payload.port);
  target.note = payload.note?.trim() ? payload.note.trim() : null;
  target.updatedAt = new Date().toISOString();
  return target;
}

async function readBody(req) {
  const chunks = [];
  for await (const chunk of req) chunks.push(chunk);
  return chunks.length ? JSON.parse(Buffer.concat(chunks).toString('utf8')) : {};
}

const server = createServer(async (req, res) => {
  const url = new URL(req.url, `http://${req.headers.host}`);
  const params = url.searchParams;
  const path = url.pathname.replace(/^\/api\/env-matrix/, '');

  // The Vite proxy makes this same-origin, but a direct browser call still works.
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'GET,POST,PUT,DELETE,OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type');
  if (req.method === 'OPTIONS') {
    res.writeHead(204);
    res.end();
    return;
  }

  try {
    if (req.method === 'GET' && path === '/ping') {
      return json(res, 200, {
        service: 'creed-resource-env-matrix (mock)',
        status: 'UP',
        healthProbeMode: 'mock',
        time: new Date().toISOString(),
      });
    }

    if (req.method === 'GET' && path === '/dimensions') {
      const result = {};
      for (const dimension of DIMENSIONS) {
        result[dimension] = [...new Set(endpoints.map((e) => e[dimension]))].sort();
      }
      return json(res, 200, result);
    }

    if (req.method === 'GET' && path === '/endpoints') {
      const rows = filtered(params);
      const conflicts = detectConflicts(rows);
      return json(res, 200, rows.map((row) => toDto(row, conflicts)));
    }

    if (req.method === 'GET' && path === '/matrix') {
      const rows = filtered(params);
      const conflicts = detectConflicts(rows);
      const byCell = new Map();
      for (const row of rows) {
        const key = `${row.service}|${row.country}`;
        if (!byCell.has(key)) byCell.set(key, []);
        byCell.get(key).push(toDto(row, conflicts));
      }
      const cells = [...byCell.values()].map((cellEndpoints) => {
        const conflictCount = cellEndpoints.filter((e) => e.conflict).length;
        return {
          service: cellEndpoints[0].service,
          country: cellEndpoints[0].country,
          endpoints: cellEndpoints,
          conflict: conflictCount > 0,
          conflictCount,
        };
      });
      return json(res, 200, {
        services: [...new Set(rows.map((r) => r.service))].sort(),
        countries: [...new Set(rows.map((r) => r.country))].sort(),
        cells,
        conflicts: toGroupDtos(conflicts),
        total: rows.length,
        scope: CONFLICT_SCOPE,
      });
    }

    if (req.method === 'GET' && path === '/conflicts') {
      return json(res, 200, toGroupDtos(detectConflicts(filtered(params))));
    }

    if (req.method === 'GET' && path === '/health') {
      const rows = filtered(params);
      const summary = {};
      const states = {};
      for (const row of rows) {
        const state = healthOf(row);
        summary[state] = (summary[state] ?? 0) + 1;
        states[row.id] = state;
      }
      return json(res, 200, {
        mode: 'mock',
        mocked: true,
        seed: mockSeed,
        total: rows.length,
        summary,
        states,
        checkedAt: new Date().toISOString(),
      });
    }

    if (req.method === 'POST' && path === '/health/recheck') {
      mockSeed += 1;
      return json(res, 200, {
        mode: 'mock',
        mocked: true,
        seed: mockSeed,
        checkedAt: new Date().toISOString(),
      });
    }

    // ---- release topology ----
    if (req.method === 'GET' && path === '/releases') {
      const tier = params.get('tier');
      const status = params.get('status');
      return json(
        res,
        200,
        releases
          .filter((r) => (!tier || r.tier === tier) && (!status || r.status === status))
          .sort((a, b) => a.tier.localeCompare(b.tier) || a.name.localeCompare(b.name))
          .map(releaseDto),
      );
    }

    if (req.method === 'POST' && path === '/releases') {
      const payload = await readBody(req);
      const invalid = validateRelease(payload);
      if (invalid) {
        return json(res, 400, {
          error: 'validation_failed',
          message: invalid.message,
          fields: [invalid],
          time: new Date().toISOString(),
        });
      }
      const clash = releases.find((r) => r.name === payload.name);
      if (clash) {
        return fail(res, 409, 'duplicate_release',
          `release '${payload.name}' already exists (id ${clash.id})`);
      }
      const now = new Date().toISOString();
      const created = {
        id: nextReleaseId++,
        name: payload.name,
        tier: payload.tier,
        status: payload.status,
        note: payload.note?.trim() ? payload.note : null,
        createdAt: now,
        updatedAt: now,
        version: 0,
      };
      releases.push(created);
      persist();
      return json(res, 201, releaseDto(created));
    }

    const releaseTopologyPath = path.match(/^\/releases\/(\d+)\/topology$/);
    if (releaseTopologyPath) {
      const id = Number(releaseTopologyPath[1]);
      const release = releases.find((r) => r.id === id);
      if (!release) return fail(res, 404, 'not_found', `no release with id ${id}`);

      if (req.method === 'GET') {
        return json(res, 200, {
          release: releaseDto(release),
          nodes: nodesOf(id).map(nodeDto),
          links: linksOf(id).map(linkDto),
        });
      }

      if (req.method === 'PUT') {
        const { nodes = [], links = [] } = await readBody(req);
        const issues = validateTopology(id, nodes, links);
        if (issues.length) {
          return json(res, 422, {
            success: false,
            nodesInserted: 0, nodesUpdated: 0, nodesDeleted: 0,
            linksInserted: 0, linksUpdated: 0, linksDeleted: 0,
            issues,
          });
        }

        const now = new Date().toISOString();
        let nodesInserted = 0;
        let nodesUpdated = 0;
        const keptNodeIds = [];
        // Payload-local ref -> the id the row actually got, so a link created in the same request
        // can point at a participant that had no id when the request was built.
        const refToId = new Map();

        for (const row of nodes) {
          let entity;
          if (row.id == null) {
            entity = {
              id: nextNodeId++,
              releaseId: id,
              appSystem: row.appSystem,
              country: row.country,
              envInstance: row.envInstance,
              label: row.label?.trim() ? row.label : null,
              note: row.note?.trim() ? row.note : null,
              layer: row.layer ?? null,
              sortOrder: row.sortOrder ?? 0,
              createdAt: now,
              updatedAt: now,
              version: 0,
            };
            releaseNodes.push(entity);
            nodesInserted += 1;
          } else {
            entity = releaseNodes.find((n) => n.id === row.id);
            const next = {
              appSystem: row.appSystem,
              country: row.country,
              envInstance: row.envInstance,
              label: row.label?.trim() ? row.label : null,
              note: row.note?.trim() ? row.note : null,
              layer: row.layer ?? null,
              sortOrder: row.sortOrder ?? 0,
            };
            if (Object.entries(next).some(([k, v]) => (entity[k] ?? null) !== v)) {
              Object.assign(entity, next, { updatedAt: now, version: (entity.version ?? 0) + 1 });
              nodesUpdated += 1;
            }
          }
          keptNodeIds.push(entity.id);
          if (row.ref) refToId.set(row.ref, entity.id);
        }

        const resolve = (ref) => (ref.id != null ? ref.id : refToId.get(ref.ref));
        let linksInserted = 0;
        let linksUpdated = 0;
        const keptLinkIds = [];

        for (const row of links) {
          const sourceNodeId = resolve(row.source);
          const targetNodeId = resolve(row.target);
          if (row.id == null) {
            releaseLinks.push({
              id: nextLinkId++,
              releaseId: id,
              sourceNodeId,
              targetNodeId,
              direction: row.direction,
              note: row.note?.trim() ? row.note : null,
              createdAt: now,
              updatedAt: now,
              version: 0,
            });
            keptLinkIds.push(nextLinkId - 1);
            linksInserted += 1;
          } else {
            const entity = releaseLinks.find((l) => l.id === row.id);
            const next = {
              sourceNodeId,
              targetNodeId,
              direction: row.direction,
              note: row.note?.trim() ? row.note : null,
            };
            if (Object.entries(next).some(([k, v]) => (entity[k] ?? null) !== v)) {
              Object.assign(entity, next, { updatedAt: now, version: (entity.version ?? 0) + 1 });
              linksUpdated += 1;
            }
            keptLinkIds.push(row.id);
          }
        }

        // Links first, so nothing is ever left pointing at a deleted participant.
        const linksBefore = releaseLinks.length;
        releaseLinks = releaseLinks.filter((l) => l.releaseId !== id || keptLinkIds.includes(l.id));
        const nodesBefore = releaseNodes.length;
        releaseNodes = releaseNodes.filter((n) => n.releaseId !== id || keptNodeIds.includes(n.id));
        persist();

        return json(res, 200, {
          success: true,
          nodesInserted,
          nodesUpdated,
          nodesDeleted: nodesBefore - releaseNodes.length,
          linksInserted,
          linksUpdated,
          linksDeleted: linksBefore - releaseLinks.length,
          issues: [],
        });
      }
    }

    const releaseById = path.match(/^\/releases\/(\d+)$/);
    if (releaseById) {
      const id = Number(releaseById[1]);
      const existing = releases.find((r) => r.id === id);
      if (!existing) return fail(res, 404, 'not_found', `no release with id ${id}`);

      if (req.method === 'GET') {
        return json(res, 200, releaseDto(existing));
      }
      if (req.method === 'PUT') {
        const payload = await readBody(req);
        const invalid = validateRelease(payload);
        if (invalid) {
          return json(res, 400, {
            error: 'validation_failed',
            message: invalid.message,
            fields: [invalid],
            time: new Date().toISOString(),
          });
        }
        const clash = releases.find((r) => r.id !== id && r.name === payload.name);
        if (clash) {
          return fail(res, 409, 'duplicate_release',
            `release '${payload.name}' already exists (id ${clash.id})`);
        }
        Object.assign(existing, {
          name: payload.name,
          tier: payload.tier,
          status: payload.status,
          note: payload.note?.trim() ? payload.note : null,
          updatedAt: new Date().toISOString(),
          version: (existing.version ?? 0) + 1,
        });
        persist();
        return json(res, 200, releaseDto(existing));
      }
      if (req.method === 'DELETE') {
        // Explicit, same as the service: the children go with the release.
        releaseLinks = releaseLinks.filter((l) => l.releaseId !== id);
        releaseNodes = releaseNodes.filter((n) => n.releaseId !== id);
        releases = releases.filter((r) => r.id !== id);
        persist();
        res.writeHead(204);
        return res.end();
      }
    }

    // ---- single-row CRUD ----
    // ---- single-row CRUD ----
    const byId = path.match(/^\/endpoints\/(\d+)$/);
    if (byId) {
      const id = Number(byId[1]);
      const existing = endpoints.find((e) => e.id === id);
      if (!existing) return fail(res, 404, 'not_found', `no endpoint with id ${id}`);

      if (req.method === 'GET') {
        return json(res, 200, toDto(existing, detectConflicts(endpoints)));
      }
      if (req.method === 'PUT') {
        const payload = await readBody(req);
        const invalid = validate(payload);
        if (invalid) {
          return json(res, 400, {
            error: 'validation_failed',
            message: 'request payload is invalid',
            fields: [invalid],
            time: new Date().toISOString(),
          });
        }
        const clash = endpoints.find(
          (e) => e.id !== id && dimensionTuple(e) === dimensionTuple(payload),
        );
        if (clash) {
          return fail(
            res,
            409,
            'duplicate_endpoint',
            `endpoint ${dimensionTuple(payload)} already exists as #${clash.id}`,
          );
        }
        applyPayload(existing, payload);
        existing.version = (existing.version ?? 0) + 1;
        persist();
        return json(res, 200, toDto(existing, detectConflicts(endpoints)));
      }
      if (req.method === 'DELETE') {
        endpoints = endpoints.filter((e) => e.id !== id);
        persist();
        res.writeHead(204);
        return res.end();
      }
    }

    if (req.method === 'POST' && path === '/endpoints') {
      const payload = await readBody(req);
      const invalid = validate(payload);
      if (invalid) {
        return json(res, 400, {
          error: 'validation_failed',
          message: 'request payload is invalid',
          fields: [invalid],
          time: new Date().toISOString(),
        });
      }
      const clash = endpoints.find((e) => dimensionTuple(e) === dimensionTuple(payload));
      if (clash) {
        return fail(
          res,
          409,
          'duplicate_endpoint',
          `endpoint ${dimensionTuple(payload)} already exists as #${clash.id}`,
        );
      }
      const created = applyPayload({ id: nextId++, version: 0 }, payload);
      created.createdAt = created.updatedAt;
      endpoints.push(created);
      persist();
      return json(res, 201, toDto(created, detectConflicts(endpoints)));
    }

    // ---- batch save (the config page's "save to database") ----
    if (req.method === 'PUT' && path === '/endpoints') {
      const { endpoints: rows = [], deleteMissing = false } = await readBody(req);

      const issues = [];
      const seen = new Map();
      rows.forEach((row, index) => {
        const invalid = validate(row);
        if (invalid) {
          issues.push({ index, id: row.id ?? null, field: invalid.field, message: invalid.message });
          return;
        }
        const tuple = dimensionTuple(row);
        if (seen.has(tuple)) {
          issues.push({
            index,
            id: row.id ?? null,
            field: 'dimensions',
            message: `duplicates row ${seen.get(tuple) + 1} (${tuple})`,
          });
          return;
        }
        seen.set(tuple, index);
        const stored = endpoints.find((e) => dimensionTuple(e) === tuple && e.id !== row.id);
        if (stored) {
          issues.push({
            index,
            id: row.id ?? null,
            field: 'dimensions',
            message: `already used by endpoint #${stored.id} (${tuple})`,
          });
        }
      });

      if (issues.length) {
        // 422 with issues, and nothing written — same as the Spring backend.
        return json(res, 422, {
          success: false,
          inserted: 0,
          updated: 0,
          deleted: 0,
          issues,
          conflicts: [],
        });
      }

      let inserted = 0;
      let updated = 0;
      const keptIds = [];
      for (const row of rows) {
        if (row.id == null) {
          const created = applyPayload({ id: nextId++, version: 0 }, row);
          created.createdAt = created.updatedAt;
          endpoints.push(created);
          keptIds.push(created.id);
          inserted += 1;
        } else {
          const existing = endpoints.find((e) => e.id === row.id);
          if (!existing) return fail(res, 404, 'not_found', `no endpoint with id ${row.id}`);
          // The config page submits the whole table, so most rows are unchanged. Only touch and
          // count the ones that actually differ — same rule as the Spring backend.
          if (differs(existing, row)) {
            applyPayload(existing, row);
            existing.version = (existing.version ?? 0) + 1;
            updated += 1;
          }
          keptIds.push(existing.id);
        }
      }

      let deleted = 0;
      if (deleteMissing) {
        const before = endpoints.length;
        endpoints = endpoints.filter((e) => keptIds.includes(e.id));
        deleted = before - endpoints.length;
      }
      persist();

      console.log(`[mock] batch save inserted=${inserted} updated=${updated} deleted=${deleted}`);
      return json(res, 200, {
        success: true,
        inserted,
        updated,
        deleted,
        issues: [],
        conflicts: toGroupDtos(detectConflicts(sorted(endpoints))),
      });
    }

    return fail(res, 404, 'not_found', `no route for ${req.method} ${url.pathname}`);
  } catch (e) {
    console.error('[mock] request failed', e);
    return fail(res, 500, 'internal_error', e.message);
  }
});

loadData();
server.listen(PORT, () => {
  console.log(`[mock] Env Matrix mock API listening on http://localhost:${PORT}/api/env-matrix`);
  console.log(`[mock] conflict scope = ${CONFLICT_SCOPE}, health = mock`);
});
