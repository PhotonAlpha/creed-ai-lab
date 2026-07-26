import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

/**
 * The UI always calls a same-origin `/api/...`, and this proxy decides who answers:
 *
 *   npm run mock   -> the node mock server on :3001   (server/index.js, no database needed)
 *   backend `dev`  -> creed-resource-env-matrix on :3001 (plain HTTP, real Postgres)
 *
 * Both speak the same contract on the same port, so switching between them needs no frontend change.
 * Point VITE_API_TARGET at https://localhost:18095 to hit the module's normal HTTPS profile instead;
 * `secure: false` is what lets that work with the Creed-CA self-signed certificate.
 */
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: process.env.VITE_API_TARGET ?? 'http://localhost:3001',
        changeOrigin: true,
        secure: false,
      },
    },
  },
  build: {
    outDir: 'dist',
    sourcemap: true,
  },
});
