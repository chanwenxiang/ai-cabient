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

/**
 * 纯判据（供 sibling 夹具喂源码，S8③）：
 *  A. compose 链：每个 `-f infra/x.yml` 必须既在盘上又被 git 跟踪（AU-185 原判据）；
 *  B. Flyway 漂移对账必须是构建**之前**的一步（2026-11-13 补）：部署链此前没有 repair 环节，
 *     已应用迁移被带理由豁免改写后，目标库 checksum 落后 ⇒ 容器起来即 Validate failed。
 *     只要求"调用过"是不够的——放在 `compose build` 之后等于白跑十几分钟才发现，
 *     所以这里比对两个索引位置。
 */
export function evaluate(script, trackedSet, existsFn) {
  const problems = [];
  // 🔴 比对位置前必须剥掉整行注释：deploy-prod.sh 的文件头流程说明里就写着
  // `compose build trade-service`，不剥的话 indexOf 先命中注释 ⇒ 顺序判据恒假（本仓
  // check-docker-tests-guarded 头注警告过同一形态：整份文件搜关键词是恒真的）。
  const code = script
    .split(/\r?\n/)
    .filter((l) => !/^\s*#/.test(l))
    .join('\n');
  const refs = [...new Set([...code.matchAll(/-f\s+(infra\/[\w.-]+\.yml)/g)].map((m) => m[1]))];
  if (refs.length === 0) {
    problems.push('references no "-f infra/*.yml" compose chain - extraction rule may be stale');
  }
  for (const ref of refs) {
    if (!existsFn(ref)) problems.push(`${ref}: missing on disk`);
    else if (!trackedSet.has(ref))
      problems.push(`${ref}: exists locally but NOT tracked in git (CI clone would break)`);
  }
  const driftAt = code.indexOf('flyway-drift-check.mjs');
  const buildAt = code.indexOf('build trade-service');
  if (driftAt < 0) {
    problems.push(
      'deploy-prod.sh 不再调用 scripts/flyway-drift-check.mjs —— 目标库 checksum 漂移会一路跑到容器起不来'
    );
  } else if (buildAt >= 0 && driftAt > buildAt) {
    problems.push(
      'flyway-drift-check 必须在 `compose build trade-service` 之前（红=中止部署，别让构建白跑）'
    );
  }
  return problems;
}

async function main() {
  let script;
  try {
    script = readFileSync(resolve(ROOT, DEPLOY_SH), 'utf8');
  } catch (e) {
    fail(`cannot read ${DEPLOY_SH}: ${e.message}`);
  }
  const listed = await gitAsync(['ls-files', '--', 'infra/'], { cwd: ROOT });
  if (!listed.ok) fail(`git ls-files failed: ${listed.out}`);
  const tracked = new Set(
    listed.out
      .split(/\r?\n/)
      .map((s) => s.trim())
      .filter(Boolean)
  );
  const problems = evaluate(script, tracked, (ref) => existsSync(resolve(ROOT, ref)));
  if (problems.length > 0) {
    fail(
      `deploy-prod.sh 部署链不完整 (AU-185 / 漂移对账):\n  ${problems.join('\n  ')}\n` +
        '补齐文件、修 -f 引用，或把 flyway-drift-check 挪到 build 之前。'
    );
  }
  console.log(`${TAG} OK：compose 链完整且 Flyway 漂移对账在 build 之前`);
}

if (process.argv[1] && resolve(process.argv[1]) === resolve(fileURLToPath(import.meta.url))) {
  await main();
}
