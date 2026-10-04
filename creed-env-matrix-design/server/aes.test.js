/**
 * The mock's AES cipher. The vector is the one AesCryptoServiceTest pins (produced by openssl and
 * node independently), so the mock and the Java backend cannot drift apart.
 */
import { describe, test } from 'node:test';
import assert from 'node:assert/strict';
import { AesError, aesDecrypt, aesEncrypt } from './aes.js';

const SECRET = 'creed-secret';
const IV = '0123456789abcdef';
const RANDOM = 'r4nd0m';
const PLAIN = 'db-p@ss 密码';
const VECTOR = 'EFZDc/Ok6Ib89heJb1OkHw==';

describe('aes', () => {
  test('matches the vector the Java service pins', () => {
    assert.equal(aesEncrypt(SECRET, IV, RANDOM, PLAIN), VECTOR);
    assert.equal(aesDecrypt(SECRET, IV, RANDOM, VECTOR), PLAIN);
  });

  test('an empty or missing randomkey derives the key from the Secret Key alone', () => {
    assert.equal(aesEncrypt(SECRET, IV, '', PLAIN), '16yJ7k5vZlC4Dylj/nGKeA==');
    assert.equal(aesEncrypt(SECRET, IV, null, PLAIN), '16yJ7k5vZlC4Dylj/nGKeA==');
  });

  test('wrong keys are a 422 decrypt_failed', () => {
    for (const [s, iv, r] of [[SECRET, IV, 'other'], ['x', IV, RANDOM], [SECRET, 'fedcba9876543210', RANDOM]]) {
      assert.throws(() => aesDecrypt(s, iv, r, VECTOR), (e) => e instanceof AesError && e.status === 422 && e.error === 'decrypt_failed');
    }
  });

  test('the IV is counted in UTF-8 bytes', () => {
    assert.throws(() => aesEncrypt(SECRET, 'short', RANDOM, PLAIN), { status: 400, field: 'iv', message: /got 5/ });
    assert.throws(() => aesEncrypt(SECRET, '密码密码密码', RANDOM, PLAIN), { field: 'iv', message: /got 18/ });
    assert.throws(() => aesEncrypt('', IV, RANDOM, PLAIN), { field: 'secretKey' });
  });

  test('malformed ciphertext', () => {
    assert.throws(() => aesDecrypt(SECRET, IV, RANDOM, 'not base64 !!'), { status: 422, message: /Base64/ });
    assert.throws(() => aesDecrypt(SECRET, IV, RANDOM, 'AAAA'), { status: 422, message: /multiple of 16/ });
  });

  test('round trip of an empty and a long multi-byte value', () => {
    for (const plain of ['', '值'.repeat(4000)]) {
      assert.equal(aesDecrypt(SECRET, IV, RANDOM, aesEncrypt(SECRET, IV, RANDOM, plain)), plain);
    }
  });
});
