import { defineConfig } from 'vitest/config';

export default defineConfig({
  test: {
    include: ['tests/**/*.test.ts'],
    environment: 'node',
    // Each file builds its own Fastify instance over a fixture directory; running them in one
    // process keeps that cheap and avoids fighting over the shared registry singleton.
    pool: 'threads',
    restoreMocks: true,
  },
});
