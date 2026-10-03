import { defineConfig, loadEnv } from 'vite';
import react from '@vitejs/plugin-react';

/**
 * The UI always calls a same-origin `/api/...`, and this proxy decides who answers:
 *
 *   npm run mock   -> the node mock server on :3001   (server/index.js, no database needed)
 *   backend `dev`  -> creed-resource-env-matrix on :3001 (plain HTTP, real Postgres)
 *
 * Both speak the same contract on the same port, so switching between them needs no frontend change.
 * The target comes from VITE_API_TARGET in `.env` (currently https://localhost:18095, the module's
 * normal HTTPS profile); `secure: false` is what lets that work with the Creed-CA self-signed
 * certificate. Config files run before Vite loads `.env`, so `process.env` is empty here and the
 * value has to be read explicitly with `loadEnv` — a shell-exported VITE_API_TARGET still wins.
 *
 * `/api/env-matrix/splunk` is split off to VITE_SPLUNK_TARGET (`.env`: the Node BFF on :3002) because
 * the Splunk broker no longer exists in the Java service. The mock serves it too, which is why
 * `npm run dev:mock` points both targets at :3001. The more specific key must come first — Vite
 * takes the first matching prefix.
 *
 * `xfwd: true` on both: http-proxy adds X-Forwarded-For/-Port/-Proto/-Host only when asked. Without
 * it the Splunk audit's `forwardedFor` was always null in dev — every request arrives from Vite's own
 * socket (127.0.0.1), so the header is the only trace of the browser's address.
 */
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), 'VITE_');

  return {
    plugins: [react()],
    server: {
      port: 5173,
      proxy: {
        '/api/env-matrix/splunk': {
          target: env.VITE_SPLUNK_TARGET ?? 'http://localhost:3002',
          changeOrigin: true,
          xfwd: true,
        },
        '/api': {
          target: env.VITE_API_TARGET ?? 'http://localhost:3001',
          changeOrigin: true,
          secure: false,
          xfwd: true,
        },
      },
    },
    build: {
      outDir: 'dist',
      sourcemap: true,
    },
  };
});
