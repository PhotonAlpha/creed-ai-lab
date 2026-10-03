/**
 * RFC 6238 TOTP (HMAC-SHA1, the variant every authenticator app implements), hand-written because it
 * is thirty lines and a library would be the only reason to carry one.
 *
 * A code is accepted if it matches any step in `now ± allowedDriftSteps`. With `rejectReplay` on, a
 * matched step must also be later than the last accepted one, so the same code cannot be used twice
 * inside its 90-second life. That memory is per process: two broker instances behind a load balancer
 * would each accept the same code once.
 */
import { createHmac, timingSafeEqual } from 'node:crypto';

/** RFC 4648 Base32, tolerant of lower case, spaces and '=' padding as authenticator exports vary. */
export function base32Decode(input) {
  const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';
  const clean = input.replace(/[\s=-]/g, '').toUpperCase();
  const bytes = [];
  let buffer = 0;
  let bits = 0;
  for (const c of clean) {
    const value = alphabet.indexOf(c);
    if (value < 0) throw new Error('TOTP secret is not valid Base32');
    buffer = ((buffer << 5) | value) & 0xffff;
    bits += 5;
    if (bits >= 8) {
      bytes.push((buffer >> (bits - 8)) & 0xff);
      bits -= 8;
    }
  }
  return Buffer.from(bytes);
}

export class Totp {
  /** @param now injectable clock (ms) — TOTP is nothing but a function of it, and tests pin it. */
  constructor(config, now = Date.now) {
    this.config = config;
    this.now = now;
    this.key = config.secret ? base32Decode(config.secret) : Buffer.alloc(0);
    this.lastAcceptedStep = -Infinity;
  }

  get configured() {
    return this.key.length > 0;
  }

  currentStep() {
    return Math.floor(this.now() / 1000 / this.config.periodSeconds);
  }

  /** Seconds until the current code rolls over, 1..period. */
  secondsRemaining() {
    const epochSeconds = Math.floor(this.now() / 1000);
    return this.config.periodSeconds - (epochSeconds % this.config.periodSeconds);
  }

  currentCode() {
    return this.codeAt(this.currentStep());
  }

  /** RFC 6238 §4 / RFC 4226 §5.3: HMAC over the big-endian step, dynamic truncation, mod 10^digits. */
  codeAt(step) {
    if (!this.configured) throw new Error('TOTP secret is not set');
    const counter = Buffer.alloc(8);
    counter.writeBigUInt64BE(BigInt(step));
    const hash = createHmac('sha1', this.key).update(counter).digest();
    const offset = hash[hash.length - 1] & 0x0f;
    const binary = (hash.readUInt32BE(offset) & 0x7fffffff) % 10 ** this.config.digits;
    return String(binary).padStart(this.config.digits, '0');
  }

  /**
   * Checks a submitted code. Synchronous on purpose: accepting a step and recording it as used must be
   * one step, and nothing between them may yield to the event loop — otherwise two concurrent
   * requests with the same code could both get through.
   *
   * @returns {{valid: boolean, reason: string|null, serverStep: number, matchedStep: number|null, drift: number|null}}
   *          reason is malformed / invalid_code / replayed — recorded in the audit trail verbatim
   */
  verify(submitted) {
    const code = (submitted ?? '').replace(/\s/g, '');
    const serverStep = this.currentStep();
    const result = (valid, reason, matchedStep = null) => ({
      valid, reason, serverStep, matchedStep, drift: matchedStep == null ? null : matchedStep - serverStep,
    });
    if (code.length !== this.config.digits || !/^\d+$/.test(code)) return result(false, 'malformed');

    // Every step in the window is computed and compared in constant time, matched or not, so
    // response timing does not say how close a guess was.
    const candidate = Buffer.from(code, 'ascii');
    let matched = null;
    for (let drift = -this.config.allowedDriftSteps; drift <= this.config.allowedDriftSteps; drift++) {
      const expected = Buffer.from(this.codeAt(serverStep + drift), 'ascii');
      if (timingSafeEqual(expected, candidate) && matched == null) matched = serverStep + drift;
    }
    if (matched == null) return result(false, 'invalid_code');
    if (this.config.rejectReplay && matched <= this.lastAcceptedStep) return result(false, 'replayed', matched);
    this.lastAcceptedStep = matched;
    return result(true, null, matched);
  }
}
