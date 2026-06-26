// Central configuration for the creed-simple-metrics k6 stress test.
//
// Everything here is overridable from the command line via environment variables, e.g.
//   k6 run -e BASE_URL=https://localhost:8096/camel/api -e VUS=100 -e ITERATIONS=10 stress-test.js

// --- Target -----------------------------------------------------------------

// creed-simple-metrics serves its Camel REST API over HTTPS on 8096 under /camel/api/*
// (see camel-context.xml + application.yml). Inbound clientAuth is NONE, so a plain
// HTTPS client with TLS verification disabled (self-signed Creed CA) is enough.
export const BASE_URL = __ENV.BASE_URL || 'https://localhost:8096/camel/api';

// --- Load profile -----------------------------------------------------------

// 100 users, 10 rounds each: a `per-vu-iterations` scenario gives exactly
// VUS virtual users, each running ITERATIONS times = VUS * ITERATIONS total iterations.
export const VUS = Number(__ENV.VUS || 100);
export const ITERATIONS = Number(__ENV.ITERATIONS || 10);

// Cap so a slow downstream cluster can't make the run hang forever.
export const MAX_DURATION = __ENV.MAX_DURATION || '3m';

// --- REST API surface -------------------------------------------------------
//
// Every endpoint of creed-simple-metrics. Each iteration of the stress test hits
// ALL of them, so the run exercises the full REST surface.
//
//   name     - logical name (used for per-endpoint tags / metrics)
//   method   - HTTP verb
//   path     - appended to BASE_URL
//   body     - request body for write methods (optional)
//   downstream - true when the route fans out to the catalog/order resource servers
//                (GET /catalog, /order, /aggregate, /aggregate-notify). These need the
//                resource servers running; they are tagged so you can filter them out.
export const ENDPOINTS = [
  { name: 'hello', method: 'GET', path: '/hello' },
  { name: 'time', method: 'GET', path: '/time' },
  {
    name: 'echo',
    method: 'POST',
    path: '/echo',
    body: { message: 'k6 stress', ts: '__TS__' },
  },
  { name: 'catalog', method: 'GET', path: '/catalog', downstream: true },
  { name: 'order', method: 'GET', path: '/order', downstream: true },
  { name: 'aggregate', method: 'GET', path: '/aggregate', downstream: true },
  { name: 'aggregate-notify', method: 'GET', path: '/aggregate-notify', downstream: true },
  // Complex pipeline: multicast aggregate -> filter -> multicast enrich -> notify/fail.
  {
    name: 'fulfillment',
    method: 'POST',
    path: '/fulfillment',
    body: { failCatalog: false, failOrder: false },
    downstream: true,
  },
];

// --- Pass/fail thresholds ---------------------------------------------------
//
// The run is marked FAILED (non-zero exit) if any threshold is breached.
export const THRESHOLDS = {
  // Overall request error rate must stay under 1%.
  http_req_failed: ['rate<0.01'],
  // 95% of all requests under 2s, 99% under 5s.
  http_req_duration: ['p(95)<2000', 'p(99)<5000'],
  // Custom check success rate must stay above 99%.
  checks: ['rate>0.99'],
};
