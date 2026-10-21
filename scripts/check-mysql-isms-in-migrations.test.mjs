/**
 * 防绕过自测：`check-mysql-isms-in-migrations` 必须**会红**（MySQL 写法）且**不假红**（PG 合法写法）。
 * Run: node scripts/check-mysql-isms-in-migrations.test.mjs
 *
 * 为什么必须有这个文件：门禁只扫 SQL 文本，判据全靠正则。正则写错一个转义、
 * 或 `g` 标志漏掉（`matchAll` 会直接抛 TypeError），门禁都能退化成「恒绿 / 恒抛」——
 * 而**它自己**看不出。这里用真 Flyway 目录跑一遍红/绿两个方向，把判据钉住。
 *
 * ⚠️ 与 `check-flyway-seed-separation.test.mjs` 同源风险：本测试要把临时 .sql **写进真实的
 * Flyway 目录**，清理逻辑本身就是安全边界：
 *   · 开跑前先清扫（上次崩溃的残留会污染断言，也会让「应通过」的用例假红）；
 *   · 删除带重试；
 *   · 收尾复查，**留一个都算失败** —— 残留物是一个假 Flyway 脚本，
 *     一旦被 `git add -A` 带上，下次开机就真去执行它。
 */
import { existsSync, readdirSync, unlinkSync, writeFileSync } from 'node:fs';
import { execFile } from 'node:child_process';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const MIGRATION_DIR = join(root, 'services/trade-service/src/main/resources/db/migration');
const TAG = '[check-mysql-isms-in-migrations.test]';
const TMP_RE = /^V9999__tmp_mysqlism_\d+_\d+\.sql$/;

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
  if (!existsSync(MIGRATION_DIR)) return 0;
  let n = 0;
  for (const f of readdirSync(MIGRATION_DIR).filter((f) => TMP_RE.test(f))) {
    unlinkWithRetry(join(MIGRATION_DIR, f));
    n++;
  }
  return n;
}

function runCheck() {
  // 🔴 必须异步 execFile：WorkBuddy 环境下本机 Node spawnSync 一律 EBUSY/status=null
  //（lessons #292）——那样每个用例都会变成「红但输出为空」的假结果。
  // 且连续多次 node-on-node 仍会**间歇性** EBUSY：spawn 级失败（双通道全空）带退避重试，
  // 进程以数字退出码结束则不重试。
  const attempt = () =>
    new Promise((done) => {
      execFile(
        process.execPath,
        [join(root, 'scripts', 'check-mysql-isms-in-migrations.mjs')],
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
      if (last.status === 0 || last.stdout || last.stderr) return last;
      sleepSync(150 * (i + 1));
    }
    return last;
  })();
}

let seq = 0;
async function inMigration(body) {
  const abs = join(MIGRATION_DIR, `V9999__tmp_mysqlism_${Date.now()}_${seq++}.sql`);
  writeFileSync(abs, body, 'utf8');
  try {
    return await runCheck();
  } finally {
    unlinkWithRetry(abs);
  }
}

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

// ── 0) 基线：目录当前必须干净（否则后面每个「应绿」用例都在污染下测） ──────────
const swept = sweepTemps();
if (swept) {
  console.warn(`${TAG} 清扫了 ${swept} 个上次遗留的临时文件（说明之前的清理失败过，请查句柄）`);
}
const baseline = await runCheck();
if (baseline.status !== 0) {
  console.error(`${TAG} 基线就不干净，后面的方向断言没有意义。门禁输出：\n${baseline.stderr}`);
  process.exit(1);
}
console.log(`${TAG} 基线绿 ✓ 现存 SQL 无 MySQL 专有语法`);

console.log(`${TAG} 必须变红的用例（MySQL 专有写法）：`);
// R1：V395 事故原文（行内 COMMENT）——本门禁存在的理由，必须优先钉住
expect(
  "V395 形态：ADD COLUMN … COMMENT '…'",
  true,
  await inMigration(
    `ALTER TABLE sku_catalog ADD COLUMN pack_qty INT NOT NULL DEFAULT 1 COMMENT '件装数';\n`
  ),
  /inline COMMENT/
);
expect(
  'AUTO_INCREMENT + ENGINE + CHARSET',
  true,
  await inMigration(
    `CREATE TABLE t2 (id BIGINT NOT NULL AUTO_INCREMENT, PRIMARY KEY (id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;\n`
  ),
  /AUTO_INCREMENT/
);
expect(
  '反引号标识符',
  true,
  await inMigration(
    `ALTER TABLE ` + '`' + `device_info` + '`' + ` ADD COLUMN ` + '`' + `x` + '`' + ` int;\n`
  ),
  /backtick/
);
expect(
  'ON DUPLICATE KEY UPDATE',
  true,
  await inMigration(
    `INSERT INTO system_config (k, v) VALUES ('a','b') ON DUPLICATE KEY UPDATE v = VALUES(v);\n`
  ),
  /ON DUPLICATE KEY/
);
expect(
  'IFNULL + GROUP_CONCAT … SEPARATOR',
  true,
  await inMigration(`SELECT GROUP_CONCAT(IFNULL(nickname, '') SEPARATOR ',') FROM user_info;\n`),
  /GROUP_CONCAT/
);
expect(
  'MySQL 类型族（TINYINT(1)/INT(11) UNSIGNED/DATETIME/MEDIUMTEXT）',
  true,
  await inMigration(
    `CREATE TABLE t3 (a TINYINT(1), b INT(11) UNSIGNED, c DATETIME, d MEDIUMTEXT);\n`
  ),
  /MySQL 类型/
);
expect(
  '裸 DOUBLE（PG 必须 DOUBLE PRECISION）',
  true,
  await inMigration(`ALTER TABLE t4 ADD COLUMN ratio DOUBLE NULL;\n`),
  /MySQL 类型/
);
expect(
  'LIMIT offset,count',
  true,
  await inMigration(`SELECT * FROM user_info ORDER BY user_id LIMIT 10, 20;\n`),
  /LIMIT offset,count/
);
expect(
  'DELIMITER 客户端指令',
  true,
  await inMigration(`DELIMITER //\nSELECT 1;\n//\n`),
  /DELIMITER/
);
expect(
  'ADD COLUMN … AFTER（跨行语句也要抓到）',
  true,
  await inMigration(
    `ALTER TABLE sku_catalog\n  ADD COLUMN pack_qty INT NOT NULL DEFAULT 1\n  AFTER shelf_life_days;\n`
  ),
  /列位置 AFTER/
);
expect(
  'MySQL IF() 函数',
  true,
  await inMigration(`SELECT IF(a > 0, 'y', 'n') FROM t5;\n`),
  /MySQL 的 IF\(\) 函数/
);
expect(
  'MODIFY COLUMN',
  true,
  await inMigration(`ALTER TABLE t6 MODIFY COLUMN name VARCHAR(64) NOT NULL;\n`),
  /MODIFY\/CHANGE COLUMN/
);
expect(
  'ON UPDATE CURRENT_TIMESTAMP',
  true,
  await inMigration(
    `CREATE TABLE t7 (updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP);\n`
  ),
  /ON UPDATE CURRENT_TIMESTAMP/
);
expect(
  'REPLACE INTO',
  true,
  await inMigration(`REPLACE INTO system_config (k, v) VALUES ('a','b');\n`),
  /INSERT IGNORE|REPLACE INTO/
);
expect(
  'ADD CONSTRAINT IF NOT EXISTS',
  true,
  await inMigration(
    `ALTER TABLE t8 ADD CONSTRAINT IF NOT EXISTS fk_x FOREIGN KEY (a) REFERENCES t9 (id);\n`
  ),
  /ADD CONSTRAINT IF NOT EXISTS/
);
expect(
  'COLLATE utf8mb4_general_ci',
  true,
  await inMigration(`ALTER TABLE t10 MODIFY COLUMN code VARCHAR(8) COLLATE utf8mb4_general_ci;\n`),
  /MySQL 排序规则名/
);
expect(
  'FORCE INDEX / ALGORITHM=INPLACE',
  true,
  await inMigration(
    `SELECT * FROM t11 FORCE INDEX (idx_a);\nALTER TABLE t11 ADD COLUMN z int, ALGORITHM=INPLACE;\n`
  ),
  /索引 hint/
);

console.log(`${TAG} 必须保持绿的用例（PG 合法写法 / 注释里的 MySQL 片段）：`);
// G1：V395 修复后的实际形态 ⇒ 绿
expect(
  'PG：ADD COLUMN IF NOT EXISTS + COMMENT ON COLUMN',
  false,
  await inMigration(
    `ALTER TABLE sku_catalog ADD COLUMN IF NOT EXISTS pack_qty INT NOT NULL DEFAULT 1;\n` +
      `COMMENT ON COLUMN sku_catalog.pack_qty IS '件装数（每件的支数）；默认 1=无箱规';\n`
  )
);
// G2：数字带精度在 PG 是合法的（判据只打 MySQL 的显示宽度，不许顺手把 numeric 也打死）
expect(
  'PG：numeric(10,2) / double precision / timestamp',
  false,
  await inMigration(
    `CREATE TABLE g2 (amount numeric(10,2) NOT NULL, ratio double precision, ts timestamp with time zone);\n`
  )
);
// G3：PG 的 enum 类型定义必须放行（`AS ENUM (` 与 MySQL 的列类型 `ENUM(` 要区分开）
expect(
  'PG：CREATE TYPE … AS ENUM (…)',
  false,
  await inMigration(
    `CREATE TYPE g3_kind AS ENUM ('A', 'B');\n` + `ALTER TABLE g3 ADD COLUMN k g3_kind;\n`
  )
);
// G4：MySQL ENUM 列类型仍然要红（同一判据的两个方向）
expect(
  'MySQL：列类型 ENUM(…)',
  true,
  await inMigration(`CREATE TABLE g4 (k ENUM('A','B') NOT NULL);\n`),
  /MySQL 类型/
);
// G5：COALESCE / string_agg / ON CONFLICT / CASE WHEN 是 PG 写法 ⇒ 绿
expect(
  'PG：COALESCE + string_agg + ON CONFLICT + CASE WHEN',
  false,
  await inMigration(
    `INSERT INTO system_config (k, v) VALUES ('a','b') ON CONFLICT (k) DO UPDATE SET v = EXCLUDED.v;\n` +
      `SELECT string_agg(CASE WHEN v IS NULL THEN '-' ELSE COALESCE(v,'?') END, ',') FROM system_config;\n`
  )
);
// G6：LIMIT n OFFSET m ⇒ 绿
expect(
  'PG：LIMIT … OFFSET …',
  false,
  await inMigration(`SELECT * FROM user_info LIMIT 20 OFFSET 10;\n`)
);
// G7：注释里的 MySQL 写法不算数（否则本仓成千上万条反引号注释会把它逼成恒红）
expect(
  '仅注释含 MySQL 写法（含反引号）',
  false,
  await inMigration(
    `-- 历史事故：MySQL 里写 ADD COLUMN \`pack_qty\` INT COMMENT '件装数'，PG 会 syntax error\n` +
      `/* CREATE TABLE x (id BIGINT AUTO_INCREMENT) ENGINE=InnoDB; */\n` +
      `ALTER TABLE sku_catalog ADD COLUMN IF NOT EXISTS tmp_g int;\n`
  )
);
// G8：PL/pgSQL 的 IF … THEN（不带括号）不算 MySQL 的 IF() ⇒ 绿
expect(
  'PG：DO 块里的 PL/pgSQL IF … THEN',
  false,
  await inMigration(
    `DO $$ BEGIN\n  IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'g8') THEN\n    RAISE NOTICE 'x';\n  END IF;\nEND $$;\n`
  )
);
// G9：regexp_replace / ~* 是 PG 的正则写法 ⇒ 绿（判据 \bREGEXP\b 不许误伤）
expect(
  'PG：regexp_replace / ~*',
  false,
  await inMigration(
    `SELECT regexp_replace(nickname, '[0-9]+', '#', 'g') FROM user_info WHERE nickname ~* '^a';\n`
  )
);
// G10：列名/字面量里出现 after / change 不算 AFTER 位置子句
expect(
  'PG：after_change_count 之类标识符',
  false,
  await inMigration(
    `ALTER TABLE g10 ADD COLUMN after_change_count INT NOT NULL DEFAULT 0, ADD COLUMN changed_at timestamp;\n`
  )
);

// ── 收尾：一个临时文件都不许留 ──────────────────────────────────────────────
const leftovers = existsSync(MIGRATION_DIR)
  ? readdirSync(MIGRATION_DIR)
      .filter((f) => TMP_RE.test(f))
      .map((f) => join(MIGRATION_DIR, f))
  : [];
if (leftovers.length) {
  failures.push(
    `临时文件未清理干净（会在源码树漏下**假 Flyway 脚本**，下次开机被真执行）：\n  - ${leftovers.join('\n  - ')}`
  );
}

if (failures.length) {
  console.error(`\n${TAG} FAIL (${failures.length})`);
  for (const f of failures) console.error(`- ${f}`);
  process.exit(1);
}
console.log(`\n${TAG} OK（28 个用例方向全对，且不残留临时文件）`);
