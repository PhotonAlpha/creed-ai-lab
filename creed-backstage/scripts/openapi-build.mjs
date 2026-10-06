#!/usr/bin/env node
/**
 * combine + bundle, for all modules or the named ones — the one command to run after editing a
 * module's paths/*.yml. With --check nothing is written and lint runs too: that is `openapi:check`,
 * what CI runs. (One script rather than `build --check && lint` in package.json: yarn appends the
 * module arguments to the LAST command only, so the first half would silently check every module.)
 *
 *   corepack yarn openapi:build                         every module
 *   corepack yarn openapi:build creed-payment creed-order
 *   corepack yarn openapi:check [modules]               = openapi:build --check
 */
import { bundle } from './openapi-bundle.mjs';
import { combine } from './openapi-combine.mjs';
import { lint } from './openapi-lint.mjs';
import { cli } from './lib/modules.mjs';

await cli(({ modules, check }) => {
  const combined = combine({ modules, check });
  // Writing a bundle from an entry document that just failed to combine would only spread the error;
  // a check, though, reports both halves so one CI run lists everything that is stale.
  if (combined.failed && !check) return combined;
  const bundled = bundle({ modules, check });
  const linted = check ? lint({ modules }) : { stale: 0, failed: 0 };
  return {
    stale: combined.stale + bundled.stale,
    failed: combined.failed + bundled.failed + linted.failed,
  };
});
