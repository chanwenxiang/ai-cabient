#!/usr/bin/env node
/**
 * Gate (AU-185): every compose file referenced by `scripts/deploy-prod.sh` must exist
 * in the repo.
 *
 * Background: deploy-prod.sh:103 used to reference `infra/docker-compose.prod-server.yml`
 * which never existed in git history - it lived only on the production server. That made
 * the production compose chain un-reviewable and unreproducible (audit A3/AU-185).
 * Decision (user approved 2026-10-19): commit the overlay (it is pure topology, secrets
 * live in infra/.env.production) and guard the chain with this gate so a missing `-f`
 * target can never regress.
 *
 * Extraction: any `-f infra/<name>.yml` token in deploy-prod.sh (single-quoted word
 * list, one -f per file). Existence is checked on disk AND via `git ls-files` (a local
 * untracked file must not satisfy the chain - CI clones are clean).
 *
 * Negative verification (iron rule 4): add `-f infra/does-not-exist.yml` to
 * deploy-prod.sh => gate must FAIL; revert => green.
 *
 *   node scripts/check-deploy-compose-chain.mjs
 */
import { execFileSync } from 'node:child_process';
import { existsSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const TAG = '[check-deploy-compose-chain]';
const DEPLOY_SH = 'scripts/deploy-prod.sh';

function fail(msg) {
  console.error(`${TAG} FAIL: ${msg}`);
  process.exit(1);
}

const script = (() => {
  try {
    return execFileSync('git', ['show', `HEAD:${DEPLOY_SH}`], {
      cwd: ROOT,
      encoding: 'utf8',
      maxBuffer: 4 * 1024 * 1024,
    });
  } catch {
    // Fallback for repos without commits; still better than silently passing.
    return null;
  }
})();
if (script === null) fail(`cannot read ${DEPLOY_SH} from git HEAD`);

const refs = [...script.matchAll(/-f\s+(infra\/[\w.-]+\.yml)/g)].map((m) => m[1]);
if (refs.length === 0) {
  fail(`${DEPLOY_SH} references no "-f infra/*.yml" compose chain - extraction rule may be stale`);
}

let tracked = [];
try {
  tracked = new Set(
    execFileSync('git', ['ls-files', '--', 'infra/'], { cwd: ROOT, encoding: 'utf8' })
      .split(/\r?\n/)
      .map((s) => s.trim())
      .filter(Boolean)
  );
} catch (e) {
  fail(`git ls-files failed: ${e.message}`);
}

const problems = [];
for (const ref of refs) {
  if (!existsSync(resolve(ROOT, ref))) problems.push(`${ref}: missing on disk`);
  else if (!tracked.has(ref)) problems.push(`${ref}: exists locally but NOT tracked in git (CI clone would break)`);
}

if (problems.length > 0) {
  fail(
    `deploy-prod.sh compose chain broken (AU-185):\n  ${problems.join('\n  ')}\n` +
      'Commit the missing file or fix the -f reference in scripts/deploy-prod.sh.'
  );
}

console.log(`${TAG} OK: ${refs.length} compose chain files present and tracked: ${refs.join(', ')}`);
