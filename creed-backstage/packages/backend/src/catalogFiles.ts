import {
  coreServices,
  createBackendPlugin,
} from '@backstage/backend-plugin-api';
import express from 'express';
import { resolve } from 'node:path';

/**
 * Serves this checkout's `catalog/` directory read-only at `/api/catalog-files/*`, so local
 * development can register the catalog as a **URL** location.
 *
 * Why not a `type: file` location: placeholders (`$text` included) resolve their relative paths with
 * `new URL(path, location.target)` and read through the URL readers. A file location's target is a
 * bare path, so the resolve throws "Placeholder $text could not form a URL", and there is no URL
 * reader for local files anyway. Over HTTP, `./dist/openapi.yaml` resolves as a URL like any other.
 *
 * Only active when `creed.catalogFiles.directory` is configured — app-config.yaml (development,
 * `../../catalog`) and app-config.production.yaml (the image's `./catalog`) both set it. The route
 * is unauthenticated because the catalog's URL reader fetches it without a token; it serves nothing
 * but that one directory (express.static refuses paths that climb out of it).
 */
export default createBackendPlugin({
  pluginId: 'catalog-files',
  register(env) {
    env.registerInit({
      deps: {
        config: coreServices.rootConfig,
        http: coreServices.httpRouter,
        logger: coreServices.logger,
      },
      async init({ config, http, logger }) {
        const directory = config.getOptionalString('creed.catalogFiles.directory');
        if (!directory) {
          logger.info('creed.catalogFiles.directory not set; not serving catalog files');
          return;
        }
        const root = resolve(directory);
        const router = express.Router();
        router.use(
          express.static(root, {
            dotfiles: 'deny',
            index: false,
            setHeaders: res => res.setHeader('Cache-Control', 'no-store'),
          }),
        );
        http.use(router);
        http.addAuthPolicy({ path: '/', allow: 'unauthenticated' });
        logger.info(`Serving catalog files from ${root}`);
      },
    });
  },
});
