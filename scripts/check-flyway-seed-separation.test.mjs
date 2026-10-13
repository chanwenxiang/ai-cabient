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
import { execFile } from 'node:child_process';
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
  // 🔴 必须异步 execFile：WorkBuddy 环境下本机 Node spawnSync 一律 EBUSY/status=null
  //（铁律 43）——那样每个用例都会变成「红但输出为空」的假结果。与守卫脚本内
  // gitAsync 同款取向。且连续多次 node-on-node 仍会**间歇性** EBUSY：
  // spawn 级失败（err.code 非数字退出码）带退避重试；进程以数字退出码结束则不重试。
  const attempt = () =>
    new Promise((done) => {
      execFile(
        process.execPath,
        [join(root, 'scripts', 'check-flyway-seed-separation.mjs')],
        { cwd: root, encoding: 'utf8' },
        (err, stdout, stderr) => {
          done({
            err,
            status: err ? (typeof err.code === 'number' ? err.code : 1) : 0,
            stdout: stdout || '',
            stderr: stderr || ''
          });
        }
      );
    });
  return (async () => {
    let last;
    for (let i = 0; i < 5; i++) {
      last = await attempt();
      // spawn 级失败的特征：非零退出 + 双通道全空（真实门禁失败必有 FAIL 行输出）
      if (last.status === 0 || last.stdout || last.stderr) return last;
      Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, 150 * (i + 1));
    }
    return last;
  })();
}

let seq = 0;
async function withTemp(dir, prefix, body, run) {
  const abs = join(dir, `${prefix}${Date.now()}_${seq++}.sql`);
  writeFileSync(abs, body, 'utf8');
  try {
    return await run();
  } finally {
    unlinkWithRetry(abs);
  }
}
const inMigration = (body) => withTemp(MIGRATION_DIR, `V9999__tmp_seedsep_`, body, runCheck);
const inSeed = (body) => withTemp(SEED_DIR, `R__seed_tmp_seedsep_`, body, runCheck);

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
    return;
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
  await inMigration(
    `-- tmp\nINSERT INTO system_config (k, v) VALUES ('a', 'b') ON CONFLICT DO NOTHING;\n`
  ),
  /未声明种类/
);
// 2) 自己声明成 seed ⇒ 红
expect(
  'V 声明 MIGRATION_KIND: seed',
  true,
  await inMigration(
    `-- MIGRATION_KIND: seed\nINSERT INTO system_config (k, v) VALUES ('a','b') ON CONFLICT DO NOTHING;\n`
  ),
  /种子不许放在 V 迁移里/
);
// 3) 声明 schema 却写数据 ⇒ 红
expect(
  'V 声明 schema 却含 INSERT',
  true,
  await inMigration(
    `-- MIGRATION_KIND: schema\nINSERT INTO system_config (k, v) VALUES ('a','b');\n`
  ),
  /二者矛盾/
);
// 4) 声明 backfill + 写数据 ⇒ 绿（V 的正当用途）
expect(
  'V 声明 backfill + INSERT',
  false,
  await inMigration(
    `-- MIGRATION_KIND: backfill\n-- 一次性回填，无 ON CONFLICT 也合法\nUPDATE user_info SET nickname = 'x' WHERE nickname IS NULL;\n`,
    runCheck
  )
);
// 5) 纯 DDL，无需声明 ⇒ 绿
expect(
  'V 纯 DDL 无声明',
  false,
  await inMigration(`ALTER TABLE user_info ADD COLUMN tmp_col varchar(8);\n`)
);
// 6) 注释里的 INSERT 不算数 ⇒ 绿（否则注释样例会造成假红）
expect(
  'V 仅注释含 INSERT',
  false,
  await inMigration(
    `-- 曾经这样写过：INSERT INTO system_config (k, v) VALUES ('a','b');\nALTER TABLE user_info ADD COLUMN tmp_col2 varchar(8);\n`,
    runCheck
  )
);

console.log(`${TAG} 种子用例：`);
// 7) 合规种子 ⇒ 绿
expect(
  'R__seed 幂等',
  false,
  await inSeed(
    `-- tmp\nINSERT INTO system_config (k, v) VALUES ('a','b') ON CONFLICT (k) DO UPDATE SET v = EXCLUDED.v;\n`,
    runCheck
  )
);
// 8) 不幂等 ⇒ 红
expect(
  'R__seed 不幂等',
  true,
  await inSeed(`-- tmp\nINSERT INTO system_config (k, v) VALUES ('a','b'), ('c','d');\n`),
  /必须幂等/
);
// 9) TRUNCATE ⇒ 红
expect(
  'R__seed 含 TRUNCATE',
  true,
  await inSeed(
    `-- tmp\nTRUNCATE TABLE system_config;\nINSERT INTO system_config (k,v) VALUES ('a','b') ON CONFLICT DO NOTHING;\n`
  ),
  /不得 TRUNCATE/
);
// 10) 无条件 DELETE ⇒ 红
expect(
  'R__seed 无条件 DELETE',
  true,
  await inSeed(
    `-- tmp\nDELETE FROM system_config;\nINSERT INTO system_config (k,v) VALUES ('a','b') ON CONFLICT DO NOTHING;\n`
  ),
  /无条件 DELETE/
);

console.log(`${TAG} 演示锚点守卫用例（C-2026-10-13）：`);
// 11) backfill 写语句引用演示锚点 ⇒ 红（防 V2…V302 形态复发）
expect(
  'V backfill INSERT 演示锚点',
  true,
  await inMigration(
    `-- MIGRATION_KIND: backfill\nINSERT INTO device_sku_inventory (device_id, sku_id, quantity) VALUES ('CAB-001', 'SKU-DEMO-001', 5);\n`
  ),
  /演示锚点/
);
// 12) 纯 DELETE 清理演示行（V344 形态）⇒ 绿
expect(
  'V backfill 纯 DELETE 演示锚点',
  false,
  await inMigration(
    `-- MIGRATION_KIND: backfill\nDELETE FROM sku_catalog WHERE sku_id IN ('SKU-DEMO-001', 'SKU-SODA-001');\n`,
    runCheck
  )
);
// 13) 写入 _ 前缀临时表夹具（守卫迁移内部形态）⇒ 绿
expect(
  'V backfill INSERT 临时表夹具',
  false,
  await inMigration(
    `-- MIGRATION_KIND: backfill\nCREATE TEMP TABLE _demo_user (user_id bigint PRIMARY KEY);\nINSERT INTO _demo_user VALUES (10001), (13800138000);\n`,
    runCheck
  )
);
// 14) 演示锚点只在注释里 ⇒ 绿（与用例 6 同口径）
expect(
  'V 注释含演示锚点',
  false,
  await inMigration(
    `-- MIGRATION_KIND: backfill\n-- 历史上这里曾 INSERT INTO sku_catalog VALUES ('SKU-DEMO-001', ...)\nUPDATE sku_catalog SET shelf_life_days = 180 WHERE shelf_life_days IS NULL;\n`,
    runCheck
  )
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
console.log(`\n${TAG} OK（14 个用例方向全对，且不残留临时文件）`);
