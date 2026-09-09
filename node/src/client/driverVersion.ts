/**
 * Reads an installed package's version for the NDJSON `metadata` block.
 *
 * `require('<pkg>/package.json')` is NOT usable here: `@valkey/valkey-glide`
 * declares an `exports` map with no `./package.json` entry, so the subpath import
 * fails with ERR_PACKAGE_PATH_NOT_EXPORTED. Resolving the module's entry point
 * and walking up to the nearest package.json with `fs` sidesteps `exports`
 * entirely (it only governs specifier resolution, not file reads) and works for
 * every driver.
 */

import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { dirname, join } from 'node:path';

const require = createRequire(import.meta.url);

/** Depth to walk up from the resolved entry point looking for package.json. */
const MAX_WALK_UP = 6;

export function packageVersion(name: string): string {
  let dir: string;
  try {
    dir = dirname(require.resolve(name));
  } catch {
    return 'unknown';
  }

  for (let i = 0; i < MAX_WALK_UP; i++) {
    try {
      const manifest = JSON.parse(readFileSync(join(dir, 'package.json'), 'utf8')) as {
        name?: string;
        version?: string;
      };
      // Guard against picking up a nested manifest of a different package.
      if (manifest.name === name && typeof manifest.version === 'string') return manifest.version;
    } catch {
      // Not here (or unreadable) -- keep walking up.
    }
    const parent = dirname(dir);
    if (parent === dir) break;
    dir = parent;
  }
  return 'unknown';
}
