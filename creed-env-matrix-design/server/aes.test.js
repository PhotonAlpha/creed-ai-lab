/**
 * The mock's AES cipher. The vectors are the ones AesCryptoServiceTest pins (produced by openssl and
 * node independently), so the mock and the Java backend cannot drift apart.
 */
import { describe, test } from 'node:test';
import assert from 'node:assert/strict';
import { AesError, aesDecrypt, aesDeriveKey, aesEncrypt, aesSecretKey, keyCache } from './aes.js';

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
