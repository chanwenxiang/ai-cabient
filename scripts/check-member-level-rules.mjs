#!/usr/bin/env node
/**
 * 会员等级规则守卫 —— 守住「功能必需的配置表不会被清空后没人补回」。
 *
 * 背景（2026-11-20 真机会员中心）
 * ----------------------------------
 * 用户真机截图：会员中心等级显示英文 `NORMAL`、进度条写「已达最高等级」、
 * 「等级说明」标题下一行内容都没有。生产实查 `member_level_rule` **0 行**：
 * V79/V99 种过 4 档等级规则，V283 还按「等级规则是功能必需配置而非 demo 数据」
 * 的口径幂等补回过，但 V344（上线前清演示数据）把整表 `DELETE FROM member_level_rule`，
 * 且它排在这两个迁移之后 ⇒ 生产落到空表。空表的后果不止文案：
 *   · `ConsumerMemberFacade.profile` 的 `current == null` ⇒ 等级名回退成编码（英文）
 *   · `next == null` ⇒ 假装「已达最高等级」
 *   · `levels` 空数组 ⇒ 等级说明整段空白
 *   · `calculateMemberLevel` 恒落 NORMAL ⇒ 会员价折扣与升级体系整体失效
 *
 * 判据（三条，都贴真实责任边界）
 * ------------------------------
 *   R1 清空必补回：任何 `DELETE FROM member_level_rule`（整表删）之后，必须存在一条
 *      version 更大的 `INSERT INTO member_level_rule`，否则红 —— 空表就是坏档位体系。
 *   R2 档位齐全：最后生效的那条 INSERT 必须覆盖 4 个默认档位
 *      （NORMAL/SILVER/GOLD/PLATINUM），少一个就等于把某档会员打成上一档。
 *   R3 名字中文：那条 INSERT 必须带中文等级名 —— 「名字 == 编码」正是本次英文 NORMAL 的形态。
 *
 * 只扫迁移脚本，不连库：能红的前提是「有人又加了整表删却没补回」，与运行时数据无关。
 *
 *   node scripts/check-member-level-rules.mjs
 */
import { readdirSync, readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const TAG = '[check-member-level-rules]';
export const MIGRATION_DIR = join(
  ROOT,
  'services',
  'trade-service',
  'src',
  'main',
  'resources',
  'db',
  'migration'
);

/** 默认档位：少任何一个都会被「取下一档」逻辑静默吃掉。 */
export const DEFAULT_CODES = ['NORMAL', 'SILVER', 'GOLD', 'PLATINUM'];

/**
 * SQL 注释脱敏：等长空白替换（保留行号与偏移），避免「注释里提了一句」把判据骗绿。
 * 与 check-ops-alert-channels 的 maskComments 同口径。
 */
export function maskSqlComments(sql) {
  let out = sql.replace(/\/\*[\s\S]*?\*\//g, (m) => m.replace(/[^\n]/g, ' '));
  out = out.replace(/--[^\n]*/g, (m) => ' '.repeat(m.length));
  return out;
}

/** 读迁移目录 → [{ file, version, body }]，body 已脱敏注释。 */
export function loadMigrations(dir = MIGRATION_DIR) {
  return readdirSync(dir)
    .filter((f) => /^V\d+__.+\.sql$/.test(f))
    .map((file) => ({
      file,
      version: Number(file.replace(/^V(\d+).*$/, '$1')),
      body: maskSqlComments(readFileSync(join(dir, file), 'utf8'))
    }));
}

export function levelRuleProblems(migrations) {
  const problems = [];
  const sorted = [...migrations].sort((a, b) => a.version - b.version);
  if (!sorted.length) {
    problems.push('未扫到任何 V 迁移 ⇒ 判据恒真，锚点可能漂移');
    return problems;
  }

  const deletes = sorted.filter((m) => /DELETE\s+FROM\s+member_level_rule\b/i.test(m.body));
  const inserts = sorted.filter((m) => /INSERT\s+INTO\s+member_level_rule\b/i.test(m.body));
  if (!inserts.length) {
    problems.push('没有任何 INSERT INTO member_level_rule ⇒ 等级体系无档位规则');
    return problems;
  }

  // R1 最后一次整表删之后必须有补回
  const lastDelete = deletes.length ? deletes[deletes.length - 1] : null;
  const effective = lastDelete ? inserts.filter((m) => m.version > lastDelete.version) : inserts;
  if (lastDelete && !effective.length) {
    problems.push(
      `${lastDelete.file} 整表清空了 member_level_rule，其后没有任何 INSERT 补回 ⇒ ` +
        `生产会员中心会显示英文等级名/「已达最高等级」/等级说明空白，且升级与会员折扣整体失效。` +
        `请加一条 backfill 迁移补回默认档位（参照 V497__restore_member_level_rules.sql）`
    );
    return problems;
  }

  const target = effective[effective.length - 1];

  // R2 四档齐全
  const missing = DEFAULT_CODES.filter(
    (code) => !new RegExp(`\\(\\s*'${code}'`, 'i').test(target.body)
  );
  if (missing.length) {
    problems.push(
      `${target.file}: 最后生效的等级规则 INSERT 缺档位 ${missing.join('、')} ⇒ ` +
        `缺的那档会员会被并到上一档（升级/折扣口径错）`
    );
  }

  // R3 中文名（名字==编码正是本次英文 NORMAL 的形态）
  if (!/[\u4e00-\u9fa5]/.test(target.body)) {
    problems.push(
      `${target.file}: 等级规则 INSERT 里没有中文等级名 ⇒ 会员中心会显示 NORMAL 这类编码`
    );
  }

  return problems;
}

if (process.argv[1] && resolve(process.argv[1]) === resolve(fileURLToPath(import.meta.url))) {
  const problems = levelRuleProblems(loadMigrations());
  if (problems.length) {
    for (const p of problems) console.error(`${TAG} ${p}`);
    process.exit(1);
  }
  console.log(`${TAG} OK：等级规则档位齐全、清空后有补回（扫迁移脚本，不含运行库）`);
}
