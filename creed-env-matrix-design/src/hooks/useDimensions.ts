import { useCallback, useEffect, useState } from 'react';
import { envMatrixApi } from '../api/envMatrix';
import type { Dimensions } from '../api/types';

const EMPTY: Dimensions = {
  appSystem: [],
  tier: [],
  envInstance: [],
  country: [],
  service: [],
  instance: [],
  scheme: [],
};

/**
 * Filter options, fetched from `/dimensions`.
 *
 * The option lists come from the data rather than a hard-coded enum, so a row inserted with a new
 * country or a new `UATn` instance shows up in the dropdowns after a `reload()` — which is why the
 * config page calls it after a successful save.
 */
export function useDimensions() {
  const [dimensions, setDimensions] = useState<Dimensions>(EMPTY);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<Error | null>(null);

  const reload = useCallback(async () => {
    setLoading(true);
    try {
      setDimensions(await envMatrixApi.dimensions());
      setError(null);
    } catch (e) {
      setError(e as Error);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void reload();
  }, [reload]);

  return { dimensions, loading, error, reload };
}
