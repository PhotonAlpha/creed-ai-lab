import { buildApp } from './app.js';
import { config } from './config.js';
import { pkg } from './version.js';

const SHUTDOWN_GRACE_MS = 10_000;

async function main(): Promise<void> {
  const app = await buildApp();

  let shuttingDown = false;
  const shutdown = async (signal: string): Promise<void> => {
    if (shuttingDown) return;
    shuttingDown = true;
    app.log.info({ signal }, 'shutting down');

    // If a handler is wedged (an injected 30s delay, say) we still have to exit, or an
    // orchestrator escalates to SIGKILL and in-flight requests are cut mid-write anyway.
    const forceExit = setTimeout(() => {
      app.log.error('graceful shutdown timed out, exiting');
      process.exit(1);
    }, SHUTDOWN_GRACE_MS);
    forceExit.unref();

    try {
      await app.close();
      app.log.info('shutdown complete');
      process.exit(0);
    } catch (cause) {
      app.log.error({ err: cause }, 'shutdown failed');
      process.exit(1);
    }
  };

  for (const signal of ['SIGINT', 'SIGTERM'] as const) {
    process.on(signal, () => void shutdown(signal));
  }
  process.on('unhandledRejection', (reason) => {
    app.log.error({ err: reason }, 'unhandled rejection');
    void shutdown('unhandledRejection');
  });

  await app.listen({ host: config.host, port: config.port });

  const base = `http://localhost:${config.port}`;
  app.log.info(
    {
      modules: app.registry.loadedModules().length,
      routes: app.registry.routeKeys().length,
      collections: app.registry.collectionBindings().length,
      scenario: app.registry.scenario,
      mocksDir: config.mocksDir,
    },
    `${pkg.name} ${pkg.version} ready`,
  );
  if (config.docsEnabled) app.log.info(`docs      ${base}${config.docsPath}`);
  app.log.info(`admin     ${base}${config.adminPrefix}/routes`);
}

main().catch((cause) => {
  // No logger yet if buildApp() threw — a bad mocks/ file lands here.
  console.error(`\n${pkg.name} failed to start:\n${(cause as Error).message}\n`);
  process.exit(1);
});
