// Stress test for creed-simple-metrics.
//
// Load model: 100 virtual users, each running 10 rounds (per-vu-iterations).
// Each round walks the ENTIRE REST API surface, so every endpoint is exercised
// VUS * ITERATIONS times.
//
// Run:
//   k6 run stress-test.js
//   k6 run -e VUS=200 -e ITERATIONS=20 stress-test.js
//   k6 run -e BASE_URL=https://localhost:8096/camel/api stress-test.js
//
// See README.md for prerequisites (the downstream catalog/order resource servers).

import { group, sleep } from 'k6';
import { VUS, ITERATIONS, MAX_DURATION, ENDPOINTS, THRESHOLDS } from './config.js';
import { callEndpoint } from './lib/api.js';

export const options = {
  // Self-signed Creed CA cert on the HTTPS listener; clientAuth is NONE so no cert needed.
  insecureSkipTLSVerify: true,

  scenarios: {
    stress: {
      executor: 'per-vu-iterations',
      vus: VUS,
      iterations: ITERATIONS,
      maxDuration: MAX_DURATION,
    },
  },

  thresholds: THRESHOLDS,
};

// One round = hit every endpoint once, grouped so per-endpoint stats are easy to read.
export default function () {
  group('creed-simple-metrics full API sweep', () => {
    for (const ep of ENDPOINTS) {
      callEndpoint(ep);
    }
  });

  // Small think-time between rounds to keep the profile realistic rather than a tight loop.
  sleep(0.1);
}
