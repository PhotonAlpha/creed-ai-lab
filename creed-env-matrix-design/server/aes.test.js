/**
 * The mock's AES cipher. The vectors are the ones AesCryptoServiceTest pins (produced by openssl and
 * node independently), so the mock and the Java backend cannot drift apart.
 */
import { describe, test } from 'node:test';
import assert from 'node:assert/strict';
import { AesError, aesDecrypt, aesEncrypt, aesSecretKey } from './aes.js';

const RANDOM = 'r4nd0m';
const IV = '0123456789abcdef';
const PLAIN = 'db-p@ss 密码';
const SECRET = 'r4nd0mms1.cn.uat110.1.1.11';
const VECTOR = 'jD7BgEr6wW5uZCYJi4j5Rw==';

describe('aes', () => {
  test('the Secret Key is randomkey + host + ip, no separator', () => {
    assert.equal(aesSecretKey(RANDOM, 'ms1.cn.uat1', '10.1.1.11'), SECRET);
    assert.equal(aesSecretKey(null, 'ms1.cn.uat1', '10.1.1.11'), 'ms1.cn.uat110.1.1.11');
  });

  test('matches the vector the Java service pins: key = SHA-256(Secret Key)', () => {
    assert.equal(aesEncrypt(SECRET, IV, PLAIN), VECTOR);
    assert.equal(aesDecrypt(SECRET, IV, VECTOR), PLAIN);
  });

  test('each server gets its own ciphertext', () => {
    assert.equal(aesEncrypt(aesSecretKey(RANDOM, 'ms2.cn.uat1', '10.1.1.12'), IV, PLAIN), 'zvgQBvL3DkQo62CFSqDT4w==');
    assert.equal(aesEncrypt(aesSecretKey('', 'ms1.cn.uat1', '10.1.1.11'), IV, PLAIN), '77E6/C/RavNY+erjoa3itA==');
  });

  test("another server's Secret Key or another IV is a 422 decrypt_failed", () => {
    for (const [secret, iv] of [[aesSecretKey(RANDOM, 'ms2.cn.uat1', '10.1.1.12'), IV], [SECRET, 'fedcba9876543210']]) {
      assert.throws(() => aesDecrypt(secret, iv, VECTOR), (e) => e instanceof AesError && e.status === 422 && e.error === 'decrypt_failed');
    }
  });

  test('the IV is counted in UTF-8 bytes', () => {
    assert.throws(() => aesEncrypt(SECRET, 'short', PLAIN), { status: 400, field: 'iv', message: /got 5/ });
    assert.throws(() => aesEncrypt(SECRET, '密码密码密码', PLAIN), { field: 'iv', message: /got 18/ });
  });

  test('malformed ciphertext', () => {
    assert.throws(() => aesDecrypt(SECRET, IV, 'not base64 !!'), { status: 422, message: /Base64/ });
    assert.throws(() => aesDecrypt(SECRET, IV, 'AAAA'), { status: 422, message: /multiple of 16/ });
  });

  test('round trip of an empty and a long multi-byte value', () => {
    for (const plain of ['', '值'.repeat(4000)]) {
      assert.equal(aesDecrypt(SECRET, IV, aesEncrypt(SECRET, IV, plain)), plain);
    }
  });
});
