// Thin request layer: turns an ENDPOINTS entry into a tagged k6 HTTP call and validates it.
import http from 'k6/http';
import { check } from 'k6';
import { Trend, Counter } from 'k6/metrics';
import { BASE_URL } from '../config.js';

// Per-endpoint latency + error counters, so the summary breaks results down by API
// (k6's built-in metrics are aggregated across all requests).
const latency = new Trend('endpoint_duration', true);
const errors = new Counter('endpoint_errors');

// Call a single endpoint and run standard checks against it.
// Returns the k6 Response so callers can inspect it further if needed.
export function callEndpoint(ep) {
  const url = `${BASE_URL}${ep.path}`;
  const tags = { endpoint: ep.name, method: ep.method };

  const params = {
    tags,
    headers: { Accept: 'application/json' },
  };

  let res;
  if (ep.method === 'GET') {
    res = http.get(url, params);
  } else if (ep.method === 'POST') {
    params.headers['Content-Type'] = 'application/json';
    const body = JSON.stringify({ ...ep.body, ts: new Date().toISOString() });
    res = http.post(url, body, params);
  } else {
    throw new Error(`Unsupported method ${ep.method} for ${ep.name}`);
  }

  latency.add(res.timings.duration, tags);

  const ok = check(
    res,
    {
      'status is 200': (r) => r.status === 200,
      'has body': (r) => r.body && r.body.length > 0,
    },
    tags
  );

  if (!ok) {
    errors.add(1, tags);
  }

  return res;
}
