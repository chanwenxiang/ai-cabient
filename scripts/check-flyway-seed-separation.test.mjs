/**
 * 本地快速自测：`check-flyway-seed-separation` 必须**会红**且**不假红**。
 * Run: node scripts/check-flyway-seed-separation.test.mjs
 *
 * ⚠️ 与 `check-migration-reviewed-gate.test.mjs` 同源风险：本测试要把临时 .sql
 * **写进真实的 Flyway 目录**（门禁是按目录扫描的），所以清理逻辑本身就是安全边界：
 *   · 开跑前先清扫（上次崩溃的残留会污染断言，会让「应通过」的用例假红）；
 *   · 删除带重试；
 *   · 收尾复查，**留一个都算失败** —— 残留物是一个假 Flyway 脚本，
 *     一旦被 `git add -A` 带上就是一次静默数据变更。
 */
import { existsSync, readdirSync, unlinkSync, writeFileSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const MIGRATION_DIR = join(root, 'services/trade-service/src/main/resources/db/migration');
const SEED_DIR = join(root, 'services/trade-service/src/main/resources/db/seed');
const TAG = '[check-flyway-seed-separation.test]';

// 只匹配本测试产生的文件名，绝不误伤真实迁移
// 注意：实际文件名形如 `V9999__tmp_seedsep_<ts>_<seq>.sql`，正则必须带上 `_<seq>`
// 后缀 —— 否则收尾清扫匹配不到，会把**假 Flyway 脚本**留在源码树里（已踩过一次）。
const TMP_RE = /^(V9999__tmp_seedsep_\d+_\d+\.sql|R__seed_tmp_seedsep_\d+_\d+\.sql)$/;

function sleepSync(ms) {
  Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, ms);
}

function unlinkWithRetry(abs) {
  let lastErr;
  for (let i = 0; i < 20; i++) {
    try {
      unlinkSync(abs);
      return;
    } catch (e) {
      if (e.code === 'ENOENT') return;
      lastErr = e;
      sleepSync(100);
    }
  }
  throw new Error(`${abs}: 无法删除（${lastErr && lastErr.message}）`);
}

function sweepTemps() {
  let n = 0;
  for (const dir of [MIGRATION_DIR, SEED_DIR]) {
    if (!existsSync(dir)) continue;
    for (const f of readdirSync(dir).filter((f) => TMP_RE.test(f))) {
      unlinkWithRetry(join(dir, f));
      n++;
    }
  }
  return n;
}

function runCheck() {
  return spawnSync('node', [join(root, 'scripts', 'check-flyway-seed-separation.mjs')], {
    cwd: root,
    encoding: 'utf8'
  });
}

let seq = 0;
function withTemp(dir, prefix, body, run) {
  const abs = join(dir, `${prefix}${Date.now()}_${seq++}.sql`);
  writeFileSync(abs, body, 'utf8');
  try {
    return run();
  } finally {
    unlinkWithRetry(abs);
  }
}
const inMigration = (body, run) => withTemp(MIGRATION_DIR, `V9999__tmp_seedsep_`, body, run);
const inSeed = (body, run) => withTemp(SEED_DIR, `R__seed_tmp_seedsep_`, body, run);

const failures = [];
function expect(label, wantRed, res, matchRe) {
  const red = res.status !== 0;
  const out = `${res.stdout || ''}${res.stderr || ''}`;
  if (wantRed !== red) {
    failures.push(`${label}: 期望 ${wantRed ? '红' : '绿'}，实际 ${red ? '红' : '绿'}\n${out}`);
    return;
  }
  if (wantRed && matchRe && !matchRe.test(out)) {
    failures.push(`${label}: 变红了但不是预期的原因\n${out}`);
  }
  console.log(`  ${wantRed ? '红' : '绿'} ✓ ${label}`);
}

// ── 0) 开跑前清扫 ────────────────────────────────────────────────────────────
const swept = sweepTemps();
if (swept) {
  console.warn(`${TAG} 清扫了 ${swept} 个上次遗留的临时文件（说明之前的清理失败过，请查句柄）`);
}

console.log(`${TAG} V 迁移用例：`);
// 1) 含数据写但未声明种类 ⇒ 红
expect(
  'V 含 INSERT 且无 MIGRATION_KIND',
  true,
  inMigration(
    `-- tmp\nINSERT INTO system_config (k, v) VALUES ('a', 'b') ON CONFLICT DO NOTHING;\n`,
    runCheck
  ),
  /未声明种类/
);
// 2) 自己声明成 seed ⇒ 红
expect(
  'V 声明 MIGRATION_KIND: seed',
  true,
  inMigration(
    `-- MIGRATION_KIND: seed\nINSERT INTO system_config (k, v) VALUES ('a','b') ON CONFLICT DO NOTHING;\n`,
    runCheck
  ),
  /种子不许放在 V 迁移里/
);
// 3) 声明 schema 却写数据 ⇒ 红
expect(
  'V 声明 schema 却含 INSERT',
  true,
  inMigration(
    `-- MIGRATION_KIND: schema\nINSERT INTO system_config (k, v) VALUES ('a','b');\n`,
    runCheck
  ),
  /二者矛盾/
);
// 4) 声明 backfill + 写数据 ⇒ 绿（V 的正当用途）
expect(
  'V 声明 backfill + INSERT',
  false,
  inMigration(
    `-- MIGRATION_KIND: backfill\n-- 一次性回填，无 ON CONFLICT 也合法\nUPDATE user_info SET nickname = 'x' WHERE nickname IS NULL;\n`,
    runCheck
  )
);
// 5) 纯 DDL，无需声明 ⇒ 绿
expect(
  'V 纯 DDL 无声明',
  false,
  inMigration(`ALTER TABLE user_info ADD COLUMN tmp_col varchar(8);\n`, runCheck)
);
// 6) 注释里的 INSERT 不算数 ⇒ 绿（否则注释样例会造成假红）
expect(
  'V 仅注释含 INSERT',
  false,
  inMigration(
    `-- 曾经这样写过：INSERT INTO system_config (k, v) VALUES ('a','b');\nALTER TABLE user_info ADD COLUMN tmp_col2 varchar(8);\n`,
    runCheck
  )
);

console.log(`${TAG} 种子用例：`);
// 7) 合规种子 ⇒ 绿
expect(
  'R__seed 幂等',
  false,
  inSeed(
    `-- tmp\nINSERT INTO system_config (k, v) VALUES ('a','b') ON CONFLICT (k) DO UPDATE SET v = EXCLUDED.v;\n`,
    runCheck
  )
);
// 8) 不幂等 ⇒ 红
expect(
  'R__seed 不幂等',
  true,
  inSeed(`-- tmp\nINSERT INTO system_config (k, v) VALUES ('a','b'), ('c','d');\n`, runCheck),
  /必须幂等/
);
// 9) TRUNCATE ⇒ 红
expect(
  'R__seed 含 TRUNCATE',
  true,
  inSeed(
    `-- tmp\nTRUNCATE TABLE system_config;\nINSERT INTO system_config (k,v) VALUES ('a','b') ON CONFLICT DO NOTHING;\n`,
    runCheck
  ),
  /不得 TRUNCATE/
);
// 10) 无条件 DELETE ⇒ 红
expect(
  'R__seed 无条件 DELETE',
  true,
  inSeed(
    `-- tmp\nDELETE FROM system_config;\nINSERT INTO system_config (k,v) VALUES ('a','b') ON CONFLICT DO NOTHING;\n`,
    runCheck
  ),
  /无条件 DELETE/
);

// ── 收尾：一个临时文件都不许留 ──────────────────────────────────────────────
const leftovers = [MIGRATION_DIR, SEED_DIR]
  .filter((d) => existsSync(d))
  .flatMap((d) =>
    readdirSync(d)
      .filter((f) => TMP_RE.test(f))
      .map((f) => join(d, f))
  );
if (leftovers.length) {
  failures.push(
    `临时文件未清理干净（会在源码树漏下**假 Flyway 脚本**，可能被 git add -A 误提交）：\n  - ${leftovers.join('\n  - ')}`
  );
}

if (failures.length) {
  console.error(`\n${TAG} FAIL (${failures.length})`);
  for (const f of failures) console.error(`- ${f}`);
  process.exit(1);
}
console.log(`\n${TAG} OK（10 个用例方向全对，且不残留临时文件）`);
