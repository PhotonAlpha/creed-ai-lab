#!/usr/bin/env node
/**
 * combine + bundle, for all modules or the named ones — the one command to run after editing a
 * module's paths/*.yml, and (with --check) what CI runs.
 *
 *   corepack yarn openapi:build                         every module
 *   corepack yarn openapi:build creed-payment creed-order
 *   corepack yarn openapi:check [modules]               = openapi:build --check
 */
import { bundle } from './openapi-bundle.mjs';
import { combine } from './openapi-combine.mjs';
import { cli } from './lib/modules.mjs';

await cli(({ modules, check }) => {
  const combined = combine({ modules, check });
  // Writing a bundle from an entry document that just failed to combine would only spread the error;
  // a check, though, reports both halves so one CI run lists everything that is stale.
  if (combined.failed && !check) return combined;
  const bundled = bundle({ modules, check });
  return { stale: combined.stale + bundled.stale, failed: combined.failed + bundled.failed };
});
