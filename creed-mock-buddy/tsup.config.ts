import { defineConfig } from 'tsup';

export default defineConfig({
  entry: ['src/main.ts'],
  outDir: 'dist',
  format: ['esm'],
  target: 'node22',
  platform: 'node',
  clean: true,
  sourcemap: true,
  splitting: false,
  // Dependencies stay external and are installed in the runtime image. Bundling them would
  // break @fastify/swagger-ui, which resolves its static assets from its own package directory.
  skipNodeModulesBundle: true,
  dts: false,
});
