/**
 * `/api/env-matrix/aes/*` for the mock — the same contract as creed-resource-env-matrix's
 * AesController, and the same cipher as its AesCryptoService:
 *
 *   key        = SHA-256( UTF-8(secretKey + randomKey) )   -> AES-256
 *   iv         = UTF-8(iv), exactly 16 bytes
 *   ciphertext = Base64( AES-256-CBC with PKCS#7 = Java's PKCS5Padding )
 *
 * A value encrypted by either backend decrypts in the other; server/aes.test.js and
 * AesCryptoServiceTest pin the same vector. Records live in mock.json (`aesRecords`): the ciphertext
 * and its randomkey (shown in the result list) — never the Secret Key or the IV.
 */
import { createCipheriv, createDecipheriv, createHash } from 'node:crypto';

export const ALGORITHM = 'AES-256/CBC/PKCS5Padding, key = SHA-256(secretKey + randomKey), Base64';
const WRONG_KEYS = 'could not decrypt — the Secret Key, IV or randomkey differ from the ones it was encrypted with';

export class AesError extends Error {
  /** @param field set for a 400 that names one request field */
  constructor(status, error, message, field = null) {
    super(message);
    this.status = status;
    this.error = error;
    this.field = field;
  }
}

function material(secretKey, iv, randomKey) {
  if (!secretKey) throw new AesError(400, 'validation_failed', 'must not be empty', 'secretKey');
  const ivBytes = Buffer.from(iv ?? '', 'utf8');
  if (ivBytes.length !== 16) {
    throw new AesError(400, 'validation_failed', `must be exactly 16 bytes in UTF-8, got ${ivBytes.length}`, 'iv');
  }
  const key = createHash('sha256').update(`${secretKey}${randomKey ?? ''}`, 'utf8').digest();
  return { key, iv: ivBytes };
}

export function aesEncrypt(secretKey, iv, randomKey, plainValue) {
  const m = material(secretKey, iv, randomKey);
  const cipher = createCipheriv('aes-256-cbc', m.key, m.iv);
  return Buffer.concat([cipher.update(plainValue, 'utf8'), cipher.final()]).toString('base64');
}

export function aesDecrypt(secretKey, iv, randomKey, encryptedValue) {
  const text = String(encryptedValue).trim();
  // Buffer.from(…, 'base64') never throws — it skips what it cannot read — so check the alphabet first.
  if (!/^[A-Za-z0-9+/]*={0,2}$/.test(text) || text.length % 4 !== 0) {
    throw new AesError(422, 'decrypt_failed', 'the encrypted value is not valid Base64');
  }
  const bytes = Buffer.from(text, 'base64');
  if (bytes.length === 0 || bytes.length % 16 !== 0) {
    throw new AesError(422, 'decrypt_failed', `the encrypted value is ${bytes.length} bytes; AES/CBC output is a non-empty multiple of 16`);
  }
  const m = material(secretKey, iv, randomKey);
  let plain;
  try {
    const decipher = createDecipheriv('aes-256-cbc', m.key, m.iv);
    plain = Buffer.concat([decipher.update(bytes), decipher.final()]);
  } catch {
    throw new AesError(422, 'decrypt_failed', WRONG_KEYS);
  }
  try {
    // fatal: a lenient decode would report a wrong key's output as U+FFFD soup instead of failing.
    return new TextDecoder('utf-8', { fatal: true }).decode(plain);
  } catch {
    throw new AesError(422, 'decrypt_failed', WRONG_KEYS);
  }
}

// ------------------------------------------------------------------- routes

const send = (res, status, body, headers = {}) => {
  res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', ...headers });
  res.end(body === undefined ? undefined : JSON.stringify(body));
};
const NO_STORE = { 'Cache-Control': 'no-store' };

const invalid = (field, message) => new AesError(400, 'validation_failed', message, field);

/** Mirrors the request records' bean validation. */
function check(body, rules) {
  for (const [field, { required, max, list }] of Object.entries(rules)) {
    const value = body[field];
    if (list) {
      if (!Array.isArray(value) || value.length === 0) throw invalid(field, 'must not be empty');
      if (value.length > list) throw invalid(field, `size must be between 1 and ${list}`);
      continue;
    }
    if (value == null || (required === 'blank' && String(value).trim() === '') || (required === 'empty' && value === '')) {
      if (required) throw invalid(field, required === 'blank' ? 'must not be blank' : 'must not be empty');
      continue;
    }
    if (typeof value !== 'string') throw invalid(field, 'must be a string');
    if (max && value.length > max) throw invalid(field, `size must be between 0 and ${max}`);
  }
}

const KEY_RULES = { secretKey: { required: 'empty', max: 256 }, iv: { required: 'null', max: 64 }, randomKey: { max: 256 } };

const trimOrNull = (s) => (s == null || String(s).trim() === '' ? null : String(s).trim());
const byIdentity = (a, b) =>
  a.appSystem.localeCompare(b.appSystem) || a.propertyKey.localeCompare(b.propertyKey)
  || a.host.localeCompare(b.host) || a.ip.localeCompare(b.ip);

/**
 * @param data      { endpoints(): rows, records(): rows, nextId(): number, persist(): void }
 * @param readBody  the mock's JSON body reader
 */
export function createAesRoutes(data, readBody) {
  const findIdentity = (appSystem, host, ip, propertyKey) => data.records()
    .find((r) => r.appSystem === appSystem && r.host === host && r.ip === ip && r.propertyKey === propertyKey);

  /** Every item to every server: insert, replace, or leave an identical row alone. */
  function saveItems(items, servers) {
    servers.forEach((srv, i) => {
      for (const [f, max] of [['appSystem', 64], ['host', 255], ['ip', 45]]) {
        if (!srv?.[f] || String(srv[f]).trim() === '') throw invalid(`servers[${i}].${f}`, 'must not be blank');
        if (String(srv[f]).length > max) throw invalid(`servers[${i}].${f}`, `size must be between 0 and ${max}`);
      }
    });
    const unique = new Map(servers.map((srv) => {
      const server = { appSystem: srv.appSystem.trim(), host: srv.host.trim(), ip: srv.ip.trim() };
      return [`${server.appSystem}\u0000${server.host}\u0000${server.ip}`, server];
    }));
    let inserted = 0;
    let updated = 0;
    const saved = [];
    const now = new Date().toISOString();
    for (const item of items) {
      const propertyKey = item.propertyKey.trim();
      const value = item.encryptedValue.trim();
      const note = trimOrNull(item.note);
      // Not trimmed: a randomkey's spaces are part of the hashed material. Empty means none.
      const randomKey = item.randomKey ? item.randomKey : null;
      for (const server of unique.values()) {
        let row = findIdentity(server.appSystem, server.host, server.ip, propertyKey);
        if (!row) {
          row = { id: data.nextId(), ...server, propertyKey, encryptedValue: value, randomKey, note, createdAt: now, updatedAt: now, version: 0 };
          data.records().push(row);
          inserted++;
        } else if (row.encryptedValue !== value || row.note !== note || (row.randomKey ?? null) !== randomKey) {
          Object.assign(row, { encryptedValue: value, randomKey, note, updatedAt: now, version: row.version + 1 });
          updated++;
        }
        saved.push(row);
      }
    }
    if (inserted || updated) data.persist();
    return { inserted, updated, records: saved };
  }

  /** `check` for one array element, with the element's path prefixed onto any field it names. */
  function checkItem(item, prefix, rules) {
    if (item == null || typeof item !== 'object') throw invalid(prefix.slice(0, -1), 'must be an object');
    try {
      check(item, rules);
    } catch (e) {
      if (e instanceof AesError && e.field) e.field = prefix + e.field;
      throw e;
    }
  }

  return async function handleAes(req, res, path, params) {
    if (!path.startsWith('/aes/')) return false;
    try {
      if (req.method === 'GET' && path === '/aes/servers') {
        const app = params.get('appSystem');
        const seen = new Map();
        for (const e of data.endpoints()) {
          if (app && e.appSystem !== app) continue;
          seen.set(`${e.appSystem}\u0000${e.host}\u0000${e.ip}`, { appSystem: e.appSystem, host: e.host, ip: e.ip });
        }
        send(res, 200, [...seen.values()].sort((a, b) =>
          a.appSystem.localeCompare(b.appSystem) || a.host.localeCompare(b.host) || a.ip.localeCompare(b.ip)));
      } else if (req.method === 'POST' && path === '/aes/encrypt') {
        const body = await readBody(req);
        check(body, { ...KEY_RULES, plainValue: { required: 'null', max: 4000 } });
        send(res, 200, { encryptedValue: aesEncrypt(body.secretKey, body.iv, body.randomKey, body.plainValue), plainValue: null, algorithm: ALGORITHM });
      } else if (req.method === 'POST' && path === '/aes/decrypt') {
        const body = await readBody(req);
        check(body, { ...KEY_RULES, encryptedValue: { required: 'blank', max: 16384 } });
        send(res, 200, { encryptedValue: null, plainValue: aesDecrypt(body.secretKey, body.iv, body.randomKey, body.encryptedValue), algorithm: ALGORITHM }, NO_STORE);
      } else if (req.method === 'GET' && path === '/aes/records') {
        const app = params.get('appSystem');
        const key = params.get('propertyKey')?.trim();
        send(res, 200, data.records()
          .filter((r) => (!app || r.appSystem === app) && (!key || r.propertyKey === key))
          .sort(byIdentity));
      } else if (req.method === 'POST' && path === '/aes/records') {
        const body = await readBody(req);
        check(body, { propertyKey: { required: 'blank', max: 255 }, encryptedValue: { required: 'blank', max: 16384 }, randomKey: { max: 256 }, note: { max: 512 }, servers: { list: 500 } });
        send(res, 200, saveItems([{ propertyKey: body.propertyKey, encryptedValue: body.encryptedValue, randomKey: body.randomKey, note: body.note }], body.servers));
      } else if (req.method === 'POST' && path === '/aes/records/batch') {
        const body = await readBody(req);
        check(body, { items: { list: 200 }, servers: { list: 500 } });
        body.items.forEach((item, i) => checkItem(item, `items[${i}].`, {
          propertyKey: { required: 'blank', max: 255 }, encryptedValue: { required: 'blank', max: 16384 }, randomKey: { max: 256 }, note: { max: 512 },
        }));
        const seen = new Map();
        body.items.forEach((item, i) => {
          const key = item.propertyKey.trim();
          if (seen.has(key)) throw invalid(`items[${i}].propertyKey`, `the same property key is already item ${seen.get(key)} of this save`);
          seen.set(key, i);
        });
        send(res, 200, saveItems(body.items, body.servers));
      } else if (req.method === 'POST' && (path === '/aes/encrypt/batch' || path === '/aes/decrypt/batch')) {
        const body = await readBody(req);
        check(body, { items: { list: 200 } });
        body.items.forEach((item, i) => checkItem(item, `items[${i}].`, {
          secretKey: { max: 256 }, iv: { max: 64 }, randomKey: { max: 256 }, value: { required: 'null', max: 16384 },
        }));
        const encrypting = path === '/aes/encrypt/batch';
        const results = body.items.map((item, index) => {
          try {
            const value = encrypting
              ? aesEncrypt(item.secretKey, item.iv, item.randomKey, item.value)
              : aesDecrypt(item.secretKey, item.iv, item.randomKey, item.value);
            return { index, value, error: null, field: null, message: null };
          } catch (e) {
            if (!(e instanceof AesError)) throw e;
            return e.field
              ? { index, value: null, error: 'invalid_key_material', field: e.field, message: e.message }
              : { index, value: null, error: e.error, field: null, message: e.message };
          }
        });
        send(res, 200, results, encrypting ? {} : NO_STORE);
      } else if (req.method === 'PUT' && /^\/aes\/records\/\d+$/.test(path)) {
        const id = Number(path.split('/').pop());
        const row = data.records().find((r) => r.id === id);
        if (!row) throw new AesError(404, 'not_found', `no AES record with id ${id}`);
        const body = await readBody(req);
        check(body, {
          appSystem: { required: 'blank', max: 64 }, host: { required: 'blank', max: 255 }, ip: { required: 'blank', max: 45 },
          propertyKey: { required: 'blank', max: 255 }, encryptedValue: { required: 'blank', max: 16384 }, randomKey: { max: 256 }, note: { max: 512 },
        });
        const next = { appSystem: body.appSystem.trim(), host: body.host.trim(), ip: body.ip.trim(), propertyKey: body.propertyKey.trim() };
        const other = findIdentity(next.appSystem, next.host, next.ip, next.propertyKey);
        if (other && other.id !== id) {
          throw new AesError(409, 'duplicate_aes_record', `that server already has a value for this property key, as record #${other.id}`);
        }
        Object.assign(row, next, {
          encryptedValue: body.encryptedValue.trim(), randomKey: body.randomKey ? body.randomKey : null, note: trimOrNull(body.note),
          updatedAt: new Date().toISOString(), version: row.version + 1,
        });
        data.persist();
        send(res, 200, row);
      } else if (req.method === 'DELETE' && path === '/aes/records') {
        const ids = new Set(params.getAll('ids').map(Number));
        const kept = data.records().filter((r) => !ids.has(r.id));
        if (kept.length !== data.records().length) {
          data.records().splice(0, data.records().length, ...kept);
          data.persist();
        }
        send(res, 204);
      } else if (req.method === 'POST' && path === '/aes/records/decrypt') {
        const body = await readBody(req);
        check(body, { items: { list: 500 } });
        body.items.forEach((item, i) => {
          if (item?.id == null) throw invalid(`items[${i}].id`, 'must not be null');
          checkItem(item, `items[${i}].`, { secretKey: { max: 256 }, iv: { max: 64 }, randomKey: { max: 256 } });
        });
        const answered = new Set();
        const results = [];
        for (const item of body.items) {
          const id = Number(item.id);
          if (answered.has(id)) continue;
          answered.add(id);
          const row = data.records().find((r) => r.id === id);
          if (!row) {
            results.push({ id, plainValue: null, error: 'not_found', message: `no AES record with id ${id}` });
            continue;
          }
          try {
            results.push({ id, plainValue: aesDecrypt(item.secretKey, item.iv, item.randomKey, row.encryptedValue), error: null, message: null });
          } catch (e) {
            if (!(e instanceof AesError)) throw e;
            results.push(e.field
              ? { id, plainValue: null, error: 'invalid_key_material', message: `${e.field}: ${e.message}` }
              : { id, plainValue: null, error: e.error, message: e.message });
          }
        }
        send(res, 200, results, NO_STORE);
      } else {
        send(res, 404, { error: 'not_found', message: `no route for ${req.method} /api/env-matrix${path}`, time: new Date().toISOString() });
      }
    } catch (e) {
      if (!(e instanceof AesError)) throw e;
      send(res, e.status, {
        error: e.error,
        message: e.field ? 'request payload is invalid' : e.message,
        ...(e.field ? { fields: [{ field: e.field, message: e.message }] } : {}),
        time: new Date().toISOString(),
      });
    }
    return true;
  };
}
