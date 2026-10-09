/**
 * The mock's AES cipher. The vectors are the ones AesCryptoServiceTest pins (produced by openssl and
 * node independently), so the mock and the Java backend cannot drift apart.
 */
import { describe, test } from 'node:test';
import assert from 'node:assert/strict';
import { AesError, aesDecrypt, aesDeriveKey, aesEncrypt, aesSecretKey, createAesRoutes, keyCache } from './aes.js';

const RANDOM = 'r4nd0m';
const IV = '0123456789abcdef';
/** Multi-byte on purpose: the salt is used as UTF-8 bytes. */
const SALT = 's4lt 盐';
const PLAIN = 'db-p@ss 密码';
const SECRET = 'r4nd0mms1.cn.uat110.1.1.11';
const VECTOR = 'H24JNkxfMPSBHI3vtO41cQ==';
const KEY_HEX = '5ca1498eb5694b6b59b2eae797c6a820a4366446891d1962a5941178238ff339';

describe('aes', () => {
  test('the Secret Key is randomkey + host + ip, no separator', () => {
    assert.equal(aesSecretKey(RANDOM, 'ms1.cn.uat1', '10.1.1.11'), SECRET);
    assert.equal(aesSecretKey(null, 'ms1.cn.uat1', '10.1.1.11'), 'ms1.cn.uat110.1.1.11');
  });

  test('key = PBKDF2-HMAC-SHA256(Secret Key, UTF-8 salt, 65536, 32 bytes) — openssl kdf output', () => {
    assert.equal(aesDeriveKey(SECRET, SALT).toString('hex'), KEY_HEX);
  });

  test('matches the vector the Java service pins', () => {
    assert.equal(aesEncrypt(SECRET, SALT, IV, PLAIN), VECTOR);
    assert.equal(aesDecrypt(SECRET, SALT, IV, VECTOR), PLAIN);
  });

  test('each server gets its own ciphertext; another salt another one', () => {
    assert.equal(aesEncrypt(aesSecretKey(RANDOM, 'ms2.cn.uat1', '10.1.1.12'), SALT, IV, PLAIN), '+xwHQYYKsIdcswqiV0wq2Q==');
    assert.equal(aesEncrypt(aesSecretKey('', 'ms1.cn.uat1', '10.1.1.11'), SALT, IV, PLAIN), '3kIVwWqttsGhLWV8i/F1pA==');
    assert.equal(aesEncrypt(SECRET, 'other', IV, PLAIN), 'MKxaHOqwjN1t2Bs4KzmxpQ==');
  });

  test("another server's Secret Key, another IV or another salt is a 422 decrypt_failed", () => {
    for (const [secret, salt, iv] of [
      [aesSecretKey(RANDOM, 'ms2.cn.uat1', '10.1.1.12'), SALT, IV],
      [SECRET, SALT, 'fedcba9876543210'],
      [SECRET, 'other', IV],
    ]) {
      assert.throws(() => aesDecrypt(secret, salt, iv, VECTOR), (e) => e instanceof AesError && e.status === 422 && e.error === 'decrypt_failed');
    }
  });

  test('the salt is required; the IV is checked first', () => {
    for (const salt of [undefined, null, '']) {
      assert.throws(() => aesEncrypt(SECRET, salt, IV, PLAIN), { status: 400, field: 'salt' });
      assert.throws(() => aesDecrypt(SECRET, salt, IV, VECTOR), { status: 400, field: 'salt' });
    }
    assert.throws(() => aesEncrypt(SECRET, '', 'short', PLAIN), { field: 'iv' });
  });

  test('the IV is counted in UTF-8 bytes', () => {
    assert.throws(() => aesEncrypt(SECRET, SALT, 'short', PLAIN), { status: 400, field: 'iv', message: /got 5/ });
    assert.throws(() => aesEncrypt(SECRET, SALT, '密码密码密码', PLAIN), { field: 'iv', message: /got 18/ });
  });

  test('malformed ciphertext', () => {
    assert.throws(() => aesDecrypt(SECRET, SALT, IV, 'not base64 !!'), { status: 422, message: /Base64/ });
    assert.throws(() => aesDecrypt(SECRET, SALT, IV, 'AAAA'), { status: 422, message: /multiple of 16/ });
  });

  test('a key cache derives once per (Secret Key, salt)', () => {
    const keys = keyCache();
    assert.equal(keys(SECRET, SALT), keys(SECRET, SALT));
    assert.notDeepEqual(keys(SECRET, SALT), keys(SECRET, 'other'));
    assert.equal(aesEncrypt(SECRET, SALT, IV, PLAIN, keys), VECTOR);
  });

  test('round trip of an empty and a long multi-byte value', () => {
    for (const plain of ['', '值'.repeat(4000)]) {
      assert.equal(aesDecrypt(SECRET, SALT, IV, aesEncrypt(SECRET, SALT, IV, plain)), plain);
    }
  });
});

/**
 * The result list's read routes, on the same fixture as AesControllerTest#seedForPaging: the
 * datasource password on both MS servers, mq.password on ms1, a token on ccs1 — which has endpoints
 * in UAT1 and SIT1. Java and the mock must answer alike.
 */
describe('aes record list routes', () => {
  const ep = (appSystem, host, ip, envInstance) => ({ appSystem, host, ip, envInstance, instance: 'Green' });
  const rec = (id, appSystem, host, ip, propertyKey, updatedAt) => ({
    id, appSystem, host, ip, propertyKey, encryptedValue: 'x', randomKey: 'r', iv: null, salt: null, note: null,
    createdAt: updatedAt, updatedAt, version: 0,
  });
  const endpoints = [ep('MS', 'ms1.cn.uat1', '10.1.1.11', 'UAT1'), ep('MS', 'ms2.cn.uat1', '10.1.1.12', 'UAT1'),
    ep('CCS', 'ccs1.cn.sit1', '10.2.1.11', 'UAT1'), ep('CCS', 'ccs1.cn.sit1', '10.2.1.11', 'SIT1')];
  const records = [rec(1, 'MS', 'ms1.cn.uat1', '10.1.1.11', 'spring.datasource.password', '2026-10-01T00:00:00Z'),
    rec(2, 'MS', 'ms2.cn.uat1', '10.1.1.12', 'spring.datasource.password', '2026-10-02T00:00:00Z'),
    rec(3, 'MS', 'ms1.cn.uat1', '10.1.1.11', 'mq.password', '2026-10-03T00:00:00Z'),
    rec(4, 'CCS', 'ccs1.cn.sit1', '10.2.1.11', 'token', '2026-10-04T00:00:00Z')];
  const handle = createAesRoutes({ endpoints: () => endpoints, records: () => records, nextId: () => 99, persist() {} }, async () => ({}));
  const get = async (path, query = '') => {
    const out = {};
    const res = { writeHead: (status) => { out.status = status; }, end: (body) => { out.body = body && JSON.parse(body); } };
    await handle({ method: 'GET' }, res, path, new URLSearchParams(query));
    return out;
  };

  test('page: identity order, 1-based, total; env via endpoints; host / key filters; sort', async () => {
    let r = await get('/aes/records/page', 'size=3');
    assert.equal(r.body.total, 4);
    assert.deepEqual(r.body.items.map((i) => i.propertyKey), ['token', 'mq.password', 'spring.datasource.password']);
    assert.equal(r.body.items[0].secretKey, 'rccs1.cn.sit110.2.1.11');
    r = await get('/aes/records/page', 'size=3&page=2');
    assert.deepEqual(r.body.items.map((i) => i.host), ['ms2.cn.uat1']);
    assert.equal((await get('/aes/records/page', 'envInstance=SIT1')).body.total, 1);
    assert.equal((await get('/aes/records/page', 'envInstance=UAT1')).body.total, 4);
    assert.equal((await get('/aes/records/page', 'host=ms1.cn.uat1&host=ccs1.cn.sit1')).body.total, 3);
    assert.equal((await get('/aes/records/page', 'appSystem=MS&propertyKey=spring.datasource.password')).body.total, 2);
    r = await get('/aes/records/page', 'sort=host&order=desc');
    assert.deepEqual(r.body.items.map((i) => i.host), ['ms2.cn.uat1', 'ms1.cn.uat1', 'ms1.cn.uat1', 'ccs1.cn.sit1']);
    r = await get('/aes/records/page', 'sort=updatedAt&order=desc&size=1');
    assert.equal(r.body.items[0].id, 4);
  });

  test('page: out-of-range parameters are 400s naming them', async () => {
    for (const [query, field] of [['size=101', 'size'], ['page=0', 'page'], ['sort=encryptedValue', 'sort'], ['order=sideways', 'order'], ['size=abc', 'size']]) {
      const r = await get('/aes/records/page', query);
      assert.equal(r.status, 400, query);
      assert.equal(r.body.fields[0].field, field, query);
    }
  });

  test('filters: narrowed left to right, values records carry', async () => {
    assert.deepEqual((await get('/aes/records/filters')).body, {
      envInstances: ['SIT1', 'UAT1'], hosts: ['ccs1.cn.sit1', 'ms1.cn.uat1', 'ms2.cn.uat1'],
      propertyKeys: ['mq.password', 'spring.datasource.password', 'token'],
    });
    let r = await get('/aes/records/filters', 'appSystem=MS');
    assert.deepEqual(r.body.envInstances, ['UAT1']);
    assert.deepEqual(r.body.hosts, ['ms1.cn.uat1', 'ms2.cn.uat1']);
    r = await get('/aes/records/filters', 'envInstance=SIT1');
    assert.deepEqual([r.body.hosts, r.body.propertyKeys], [['ccs1.cn.sit1'], ['token']]);
    r = await get('/aes/records/filters', 'host=ms2.cn.uat1');
    assert.equal(r.body.hosts.length, 3);
    assert.deepEqual(r.body.propertyKeys, ['spring.datasource.password']);
  });

  test('saved-servers: servers holding any listed key; none listed is empty', async () => {
    const r = await get('/aes/records/saved-servers', 'propertyKey=mq.password&propertyKey=token');
    assert.deepEqual(r.body.map((s) => s.host).sort(), ['ccs1.cn.sit1', 'ms1.cn.uat1']);
    assert.deepEqual((await get('/aes/records/saved-servers')).body, []);
  });
});
