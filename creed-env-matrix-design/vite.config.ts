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
 */
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), 'VITE_');

  return {
    plugins: [react()],
    server: {
      port: 5173,
      proxy: {
        '/api': {
          target: env.VITE_API_TARGET ?? 'http://localhost:3001',
          changeOrigin: true,
          secure: false,
        },
      },
    },
    build: {
      outDir: 'dist',
      sourcemap: true,
    },
  };
});
