/**
 * 本地快速校验：空头 MIGRATION_REVIEWED 应失败。
 * Run: node scripts/check-migration-reviewed-gate.test.mjs
 *
 * ⚠️ 本测试会把临时 .sql **写进真实的 Flyway migration 目录**（因为 check-migration-safety
 * 是按目录扫描的），所以清理逻辑本身就是安全边界，不能靠「沉默的 catch」兜底：
 *
 * 2026-09-16 实测（Windows + OneDrive）：原实现里 `rmSync` 在 `catch {}` 内静默失败 ——
 * 临时文件一个都删不掉。后果有两个，都是真事故级：
 *   ① 第一个（bare 头）文件残留 → 第二次 `withTempSql(ok)` 扫到它 → 断言「完整头应通过」
 *      **在干净工作区上首跑即红**（本地假红，CI/Linux 看不到）；
 *   ② 残留物是 migration 目录里一个**假的 Flyway 脚本**（`ALTER TABLE shopping_session
 *      ALTER COLUMN state TYPE varchar(64)`），一旦被 `git add -A` 带上就是一次静默 schema 变更。
 *
 * 因此这里：开跑前 + 跑完后都清扫；删除带重试；删不掉**显式失败**（不再静默）。
 * 兜底还有 .gitignore 里的 `V9999__tmp_migration_reviewed_gate_*.sql`。
 */
import { readdirSync, unlinkSync, writeFileSync, existsSync } from 'node:fs';
import { join } from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const migrationDir = join(
  root,
  'services',
  'trade-service',
  'src',
  'main',
  'resources',
  'db',
  'migration'
);
const TAG = '[check-migration-reviewed-gate]';

// 严格匹配本测试自己产生的文件名（不误伤任何真实迁移）
const TMP_RE = /^V9999__tmp_migration_reviewed_gate_\d+\.sql$/;

const bare = `-- MIGRATION_REVIEWED: yes
-- empty bypass attempt
ALTER TABLE shopping_session ALTER COLUMN state TYPE varchar(64);
`;

const ok = `-- MIGRATION_REVIEWED: yes
-- TABLES: shopping_session (~1k)
-- LOCK_RISK: medium
-- NOTES: staging lock wait < 1s
ALTER TABLE shopping_session ALTER COLUMN state TYPE varchar(64);
`;

function sleepSync(ms) {
  Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, ms);
}

/** 删除单个文件，带重试；删不掉就抛（绝不静默）。 */
function unlinkWithRetry(abs) {
  let lastErr;
  for (let i = 0; i < 20; i++) {
    try {
      unlinkSync(abs);
      return;
    } catch (e) {
      if (e.code === 'ENOENT') return; // 已经不在了 = 目标达成
      lastErr = e;
      sleepSync(100);
    }
  }
  throw new Error(`${abs}: 无法删除（${lastErr && lastErr.message}）`);
}

/** 清扫目录里所有本测试的临时迁移（含**上一次崩溃/静默失败**遗留的）。返回清扫数量。 */
function sweepTemps() {
  const leftovers = readdirSync(migrationDir).filter((f) => TMP_RE.test(f));
  for (const f of leftovers) unlinkWithRetry(join(migrationDir, f));
  return leftovers.length;
}

function withTempSql(body, run) {
  const name = `V9999__tmp_migration_reviewed_gate_${Date.now()}.sql`;
  const abs = join(migrationDir, name);
  writeFileSync(abs, body, 'utf8');
  try {
    return run();
  } finally {
    unlinkWithRetry(abs); // 失败会抛，不会把假迁移留在源码树里
  }
}

function runCheck() {
  return spawnSync('node', [join(root, 'scripts', 'check-migration-safety.mjs')], {
    cwd: root,
    encoding: 'utf8'
  });
}

// 0) 开跑前先清扫：否则上一次的残留会直接污染断言（这就是首跑即红的真因）
const sweptAtStart = sweepTemps();
if (sweptAtStart) {
  console.warn(
    `${TAG} 清扫了 ${sweptAtStart} 个上次遗留的临时迁移（说明之前的清理失败过，请检查磁盘/同步软件句柄）`
  );
}

// 1) 空头 MIGRATION_REVIEWED: yes 必须失败
const bareResult = withTempSql(bare, runCheck);
if (bareResult.status === 0) {
  console.error('FAIL: bare MIGRATION_REVIEWED should have failed');
  process.exit(1);
}
if (!/requires LOCK_RISK|requires non-empty NOTES|requires TABLES/i.test(bareResult.stderr || '')) {
  console.error('FAIL: expected metadata errors, got:\n', bareResult.stderr);
  process.exit(1);
}

// 2) 元数据齐全的 reviewed 头必须通过
const okResult = withTempSql(ok, runCheck);
if (okResult.status !== 0) {
  console.error('FAIL: complete reviewed header should pass\n', okResult.stderr);
  process.exit(1);
}

// 3) 收尾自查：migration 目录里不得留任何临时迁移
const remaining = readdirSync(migrationDir).filter((f) => TMP_RE.test(f));
if (remaining.length) {
  console.error(
    `${TAG} FAIL: 临时迁移未清理干净（会在源码树里漏下**假 Flyway 脚本**，可能被 git add -A 误提交）：\n  - ${remaining.join('\n  - ')}`
  );
  process.exit(1);
}
if (!existsSync(migrationDir)) {
  console.error(`${TAG} FAIL: migration 目录不存在，扫描锚点已失效`);
  process.exit(1);
}

console.log(`${TAG} OK（本测试不残留任何临时迁移）`);
