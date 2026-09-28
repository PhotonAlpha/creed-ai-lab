import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import { loadCountryJson } from '../src/mock/country-data.js';

const BASE = fileURLToPath(new URL('./fixtures/country', import.meta.url));

describe('loadCountryJson', () => {
  it('serves the country file when it exists', () => {
    expect(loadCountryJson(BASE, 'ms', 'product-details.json').data).toEqual({ country: 'ms' });
  });

  it('falls back to default for an unknown country', () => {
    const result = loadCountryJson(BASE, 'sg', 'product-details.json');
    expect(result.data).toEqual({ country: 'default' });
    expect(result.source).toContain('default');
  });

  it('falls back per file when the country dir lacks it', () => {
    expect(loadCountryJson(BASE, 'ms', 'default-only.json').data).toEqual({ only: 'default' });
  });

  it('uses default when no country is set', () => {
    expect(loadCountryJson(BASE, '', 'product-details.json').data).toEqual({ country: 'default' });
  });

  it('fails loudly when neither location has the file', () => {
    expect(() => loadCountryJson(BASE, 'ms', 'missing.json')).toThrow(/not found/);
  });
});
