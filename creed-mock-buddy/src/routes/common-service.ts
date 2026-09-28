import type { FastifyInstance, RouteOptions } from 'fastify';
import { config } from '../config.js';
import { loadCountryJson } from '../mock/country-data.js';

const TAGS = ['common-service'];

/**
 * Canned JSON responses picked per country: `CREED_MOCK_COUNTRY` selects
 * `<CREED_MOCK_JSON_DIR>/<country>/`, falling back to `default/` file by file.
 */
export function commonRoutes(app: FastifyInstance): RouteOptions[] {
  const productDetails = loadCountryJson(config.jsonMockDir, config.country, 'product-details.json');
  app.log.info({ country: config.country || '(none)', source: productDetails.source }, 'product details mock');

  return [
    {
      method: 'POST',
      url: '/product/details',
      schema: { tags: TAGS, summary: 'Product details for the startup country (CREED_MOCK_COUNTRY)' },
      handler: async () => productDetails.data,
    },
  ];
}
