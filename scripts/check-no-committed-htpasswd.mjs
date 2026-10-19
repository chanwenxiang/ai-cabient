#!/usr/bin/env node
/**
 * Gate (AU-146): no `*.htpasswd` may be tracked in git.
 *
 * Background (audit AU-146, 2026-10-18): `infra/gateway/grafana.htpasswd` was committed
 * with a REAL admin bcrypt hash at cost=05 (offline brute-force ~32x faster than cost 10),
 * and the old hash stays in git history forever even after cost bump. Decision (user
 * approved 2026-10-19): rotate the credential and generate the file at deploy time from
 * `GRAFANA_BASIC_AUTH_PASSWORD` (infra/.env) via `scripts/devops/gen-grafana-htpasswd.ps1`.
 *
 * This gate is reject-style (fail-closed): ANY tracked file matching `*.htpasswd` fails,
 * regardless of directory. Cost/algorithm cannot be checked here by design - the correct
 * state is "file does not exist in the index".
 *
 * Negative verification (iron rule 4): `git add` any `x.htpasswd` => gate must FAIL;
 * remove it again => gate green.
 *
 *   node scripts/check-no-committed-htpasswd.mjs
 */
import { execFileSync } from 'node:child_process';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const TAG = '[check-no-committed-htpasswd]';

function fail(msg) {
  console.error(`${TAG} FAIL: ${msg}`);
  process.exit(1);
}

let tracked = [];
try {
  tracked = execFileSync('git', ['ls-files', '--', '*.htpasswd'], {
    cwd: ROOT,
    encoding: 'utf8'
  })
    .split(/\r?\n/)
    .map((s) => s.trim())
    .filter(Boolean);
} catch (e) {
  fail(`git ls-files failed: ${e.message}`);
}

if (tracked.length > 0) {
  fail(
    `htpasswd file(s) tracked in git (AU-146): ${tracked.join(', ')}. ` +
      'Remove from index (`git rm --cached <file>`), add to .gitignore, ' +
      'and generate locally via scripts/devops/gen-grafana-htpasswd.ps1 instead.'
  );
}

console.log(`${TAG} OK: no tracked *.htpasswd`);
