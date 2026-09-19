#!/usr/bin/env node
/**
 * O9 Flyway 治理：**种子 / 结构分离**（只向前生效，历史迁移不动）。
 *
 *   node scripts/check-flyway-seed-separation.mjs
 *   MIGRATION_BASE_REF=origin/main node scripts/check-flyway-seed-separation.mjs
 *
 * 约定见 `db/seed/README.md`：结构 = `db/migration/V*.sql`（一次性、checksum 不可变）；
 * 种子 = `db/seed/R__seed_*.sql`（可重复、**必须幂等**）。
 *
 * 🔴 判据取向（刻意**不猜语义**）：
 *    本脚本不去猜「这条 INSERT 算种子还是算 backfill」—— 那是个模糊判断，猜错就是假红，
 *    而假红会让门禁被人绕过去然后删掉。改为**强制显式声明**：
 *      · 新增 `V*.sql` 若含数据写语句，必须自己声明 `MIGRATION_KIND: schema|backfill`
 *        （声明 `seed` ⇒ 直接红，种子不许留在 V 里）；
 *      · 声明 `schema` 却写数据 ⇒ 红（要么改 backfill，要么挪去 seed）。
 *    这样「把种子塞进 V」不再是顺手能做的事，而是一次自觉且有记录的决定。
 * 🔴 历史 277 个迁移天然 grandfather：按「相对基线新增」判定，不回溯。
 *    改写已应用迁移 = checksum 漂移，需要每台环境 `flyway repair` —— 明确不做。
 */
import { spawnSync } from 'node:child_process';
import { existsSync, readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const MIGRATION_DIR = 'services/trade-service/src/main/resources/db/migration';
const SEED_DIR = 'services/trade-service/src/main/resources/db/seed';
const APP_YML = 'services/trade-service/src/main/resources/application.yml';
const PROD_YML = 'services/trade-service/src/main/resources/application-prod.yml';

const errors = [];

function err(m) {
  errors.push(m);
}
function read(rel) {
  try {
    return readFileSync(join(root, rel), 'utf8');
  } catch (e) {
    err(`读不到 ${rel}: ${e.message}`);
    return '';
  }
}
function git(args) {
  const r = spawnSync('git', args, { cwd: root, encoding: 'utf8' });
  if (r.status !== 0) return { ok: false, out: (r.stdout || '') + (r.stderr || '') };
  return { ok: true, out: (r.stdout || '').trim() };
}

/** 去 SQL 注释（`--` 行注释与块注释）。用于判「有没有真的写数据」。 */
function stripSqlComments(src) {
  return src.replace(/\/\*[\s\S]*?\*\//g, '').replace(/--[^\n]*/g, '');
}

// ── 锚点：目录与配置必须真的存在，否则本门禁会「扫不到任何东西却常绿」 ────────
for (const d of [MIGRATION_DIR, SEED_DIR]) {
  if (!existsSync(join(root, d))) {
    err(`${d} 不存在 —— 扫描锚点失效，本门禁会恒绿`);
  }
}
const appYml = read(APP_YML);
const locM = appYml.match(/locations\s*:\s*(.+)/);
if (!locM) {
  err(`${APP_YML} 里找不到 spring.flyway.locations`);
} else {
  const locs = locM[1];
  if (!locs.includes('classpath:db/migration')) {
    err(`flyway.locations 不含 classpath:db/migration（实际：${locs.trim()}）`);
  }
  if (!locs.includes('classpath:db/seed')) {
    err(
      `flyway.locations 不含 classpath:db/seed ⇒ 写进 db/seed 的种子会被 Flyway **静默忽略**` +
        `（实际：${locs.trim()}）`
    );
  }
}
// prod 必须保留种子守卫，否则种子会直接落到生产
const prodYml = read(PROD_YML);
if (prodYml && !/seed_env\s*:\s*none/.test(prodYml)) {
  err(`${PROD_YML} 的 spring.flyway.placeholders.seed_env 不再是 none —— 生产会执行种子语句`);
}

// ── 新增文件（相对基线）─────────────────────────────────────────────────────
const baseRef = process.env.MIGRATION_BASE_REF || 'origin/dev';
let diff = git([
  'diff',
  '--name-only',
  '--diff-filter=A',
  `${baseRef}...HEAD`,
  '--',
  MIGRATION_DIR,
  SEED_DIR
]);
if (!diff.ok) {
  diff = git(['diff', '--name-only', '--diff-filter=A', 'HEAD', '--', MIGRATION_DIR, SEED_DIR]);
}
// 刻意不加 --exclude-standard：未跟踪的 .sql 同样是一次真实的新迁移（与 check:migration-safety 同口径）
const untracked = git(['ls-files', '--others', '--', MIGRATION_DIR, SEED_DIR]);
if (!diff.ok && !untracked.ok) {
  err('git 不可用，无法判定新增迁移 —— 拒绝静默放行');
}

const files = [
  ...new Set(
    [
      ...(diff.ok && diff.out ? diff.out.split(/\r?\n/) : []),
      ...(untracked.ok && untracked.out ? untracked.out.split(/\r?\n/) : [])
    ]
      .map((s) => s.trim())
      .filter((s) => /\.sql$/i.test(s))
  )
];

const DATA_WRITE = /\b(INSERT\s+INTO|DELETE\s+FROM|TRUNCATE|UPDATE\s+[A-Za-z_][\w."]*\s+SET)\b/i;

let vCount = 0;
let rCount = 0;

for (const rel of files) {
  const raw = read(rel);
  if (!raw) continue;
  const name = rel.split('/').pop();

  if (rel.startsWith(SEED_DIR + '/')) {
    rCount++;
    if (!/^R__seed_[A-Za-z0-9_]+\.sql$/.test(name)) {
      err(`${rel}: 种子文件名必须形如 R__seed_<用途>.sql（可重复迁移必须用 R__ 前缀）`);
    }
    const body = stripSqlComments(raw);
    const idem = /ON\s+CONFLICT/i.test(body) || /WHERE\s+NOT\s+EXISTS/i.test(body);
    if (!idem) {
      err(
        `${rel}: 可重复种子**必须幂等**（至少要出现 ON CONFLICT 或 WHERE NOT EXISTS）——` +
          `R__ 在 checksum 变化时会重跑，不幂等会重复插入或直接报错`
      );
    }
    if (/\bTRUNCATE\b/i.test(body)) {
      err(`${rel}: 种子不得 TRUNCATE —— 重跑会反复清库`);
    }
    for (const stmt of body.split(';')) {
      if (/\bDELETE\s+FROM\b/i.test(stmt) && !/\bWHERE\b/i.test(stmt)) {
        err(`${rel}: 无条件 DELETE FROM —— 可重复迁移下会反复清表`);
        break;
      }
    }
    continue;
  }

  if (rel.startsWith(MIGRATION_DIR + '/')) {
    vCount++;
    const kindM = raw.match(/MIGRATION_KIND\s*:\s*(schema|backfill|seed)\b/i);
    const kind = kindM ? kindM[1].toLowerCase() : '';
    const writes = DATA_WRITE.test(stripSqlComments(raw));

    if (kind === 'seed') {
      err(
        `${rel}: 声明 MIGRATION_KIND: seed ⇒ 种子不许放在 V 迁移里。` +
          `请改为 db/seed/R__seed_<用途>.sql（可重复 + 幂等），见 db/seed/README.md`
      );
    } else if (!kind && writes) {
      err(
        `${rel}: 含数据写语句但未声明种类。请在文件头加一行 ` +
          `\`-- MIGRATION_KIND: backfill\`（一次性数据修复，属 V 的正当用途）或 ` +
          `\`-- MIGRATION_KIND: schema\`；若其实是种子数据，请移到 db/seed/R__seed_*.sql`
      );
    } else if (kind === 'schema' && writes) {
      err(
        `${rel}: 声明 MIGRATION_KIND: schema 却含数据写语句 —— 二者矛盾，请改 backfill 或移入 db/seed`
      );
    }
    continue;
  }

  err(`${rel}: 落在预期目录之外（既非 ${MIGRATION_DIR} 也非 ${SEED_DIR}）`);
}

// ── 汇总 ────────────────────────────────────────────────────────────────────
if (files.length === 0) {
  console.log('[check-flyway-seed-separation] 无相对基线新增的迁移；锚点与配置已校验，OK');
} else {
  console.log(
    `[check-flyway-seed-separation] 新增 V 迁移 ${vCount} 个、种子 ${rCount} 个（文件共 ${files.length}）`
  );
}
if (errors.length) {
  for (const e of errors) console.error(`[check-flyway-seed-separation] FAIL ${e}`);
  console.error(`[check-flyway-seed-separation] ${errors.length} 处不合规`);
  process.exit(1);
}
console.log('[check-flyway-seed-separation] OK：种子与结构分离策略得到遵守');
