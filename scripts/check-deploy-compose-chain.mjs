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
 * list, one -f per file). The script is read from the WORKING TREE (so pre-push
 * negative verification can inject a bogus -f and see the gate fail), while existence
 * of each target is checked on disk AND via `git ls-files` (index): a local untracked
 * file must not satisfy the chain - CI clones are clean, where worktree == HEAD, so
 * the two readings are equivalent in CI.
 *
 * Negative verification (iron rule 4): stage `-f infra/does-not-exist.yml` in
 * deploy-prod.sh => gate must FAIL; `git checkout HEAD --` => green.
 *
 *   node scripts/check-deploy-compose-chain.mjs
 */
import { existsSync, readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { gitAsync } from './lib/async-spawn.mjs';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const TAG = '[check-deploy-compose-chain]';
const DEPLOY_SH = 'scripts/deploy-prod.sh';

function fail(msg) {
  console.error(`${TAG} FAIL: ${msg}`);
  process.exit(1);
}

let script;
try {
  script = readFileSync(resolve(ROOT, DEPLOY_SH), 'utf8');
} catch (e) {
  fail(`cannot read ${DEPLOY_SH}: ${e.message}`);
}

const refs = [...new Set([...script.matchAll(/-f\s+(infra\/[\w.-]+\.yml)/g)].map((m) => m[1]))];
if (refs.length === 0) {
  fail(`${DEPLOY_SH} references no "-f infra/*.yml" compose chain - extraction rule may be stale`);
}

// 异步 git（lessons #292）：同步子进程在 WorkBuddy 下 EBUSY，结果会被误读成「未跟踪」。
const listed = await gitAsync(['ls-files', '--', 'infra/'], { cwd: ROOT });
if (!listed.ok) fail(`git ls-files failed: ${listed.out}`);
const tracked = new Set(
  listed.out
    .split(/\r?\n/)
    .map((s) => s.trim())
    .filter(Boolean)
);

const problems = [];
for (const ref of refs) {
  if (!existsSync(resolve(ROOT, ref))) problems.push(`${ref}: missing on disk`);
  else if (!tracked.has(ref))
    problems.push(`${ref}: exists locally but NOT tracked in git (CI clone would break)`);
}

if (problems.length > 0) {
  fail(
    `deploy-prod.sh compose chain broken (AU-185):\n  ${problems.join('\n  ')}\n` +
      'Commit the missing file or fix the -f reference in scripts/deploy-prod.sh.'
  );
}

console.log(
  `${TAG} OK: ${refs.length} compose chain files present and tracked: ${refs.join(', ')}`
);
