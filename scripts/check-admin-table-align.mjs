/**
 * A07′：admin 表格列对齐门禁。
 * - 硬错误：同一 <el-table-column> 上同时出现 align="center" 与 class-name="col-text"
 *   （文本列应由 col-text 左齐，不应再强制居中）
 * - 统计：其余 align="center" 债务（状态/操作等应挂 col-status / col-action；全量清零后 STRICT 可开）
 *
 * 用法：node scripts/check-admin-table-align.mjs
 * 默认 STRICT 开启（裸 align=center 债务已清零）；STRICT=0 可临时放宽
 * STRICT_MONEY=1 时，金额语义未配对 col-money 也失败
 */
import fs from 'node:fs';
import path from 'node:path';

const root = path.resolve('clients/admin-vue/src');
const STRICT = process.env.STRICT !== '0';
const STRICT_MONEY = process.env.STRICT_MONEY === '1';
const MONEY_LABEL =
  /label\s*=\s*["'][^"']*(?:元|金额|余额|毛利|营收|成本|单价|小计|抽成|手续费)[^"']*["']/;
const conflicts = [];
const bareCenter = [];
const moneyUnpaired = [];

function walk(dir) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      walk(full);
      continue;
    }
    if (!entry.name.endsWith('.vue')) continue;
    const text = fs.readFileSync(full, 'utf8');
    const rel = path.relative(process.cwd(), full).replace(/\\/g, '/');
    const re = /<el-table-column\b[\s\S]*?>/g;
    let m;
    while ((m = re.exec(text))) {
      const tag = m[0];
      const line = text.slice(0, m.index).split(/\n/).length;
      const className = /\bclass-name\s*=\s*["']([^"']+)["']/.exec(tag)?.[1] || '';
      if (MONEY_LABEL.test(tag) && !className.includes('col-money')) {
        moneyUnpaired.push(`${rel}:${line}`);
      }
      const hasAlignCenter = /(^|\s)align\s*=\s*["']center["']/.test(tag);
      if (!hasAlignCenter) continue;
      if (className.includes('col-text')) {
        conflicts.push(`${rel}:${line}`);
        continue;
      }
      if (
        className.includes('col-action') ||
        className.includes('col-money') ||
        className.includes('col-status')
      ) {
        continue;
      }
      bareCenter.push(`${rel}:${line}`);
    }
  }
}

walk(root);

console.log(`[admin-table-align] col-text+align=center conflicts: ${conflicts.length}`);
for (const c of conflicts.slice(0, 40)) console.log(`  ERROR ${c}`);
if (conflicts.length > 40) console.log(`  … +${conflicts.length - 40} more`);

console.log(`[admin-table-align] bare align=center (debt): ${bareCenter.length}`);
if (STRICT) {
  for (const c of bareCenter.slice(0, 40)) console.log(`  STRICT ${c}`);
  if (bareCenter.length > 40) console.log(`  … +${bareCenter.length - 40} more`);
}

console.log(`[admin-table-align] money-label without col-money: ${moneyUnpaired.length}`);
if (moneyUnpaired.length > 0) {
  for (const c of moneyUnpaired.slice(0, 40)) console.log(`  WARN ${c}`);
  if (moneyUnpaired.length > 40) console.log(`  … +${moneyUnpaired.length - 40} more`);
}

if (
  conflicts.length > 0 ||
  (STRICT && bareCenter.length > 0) ||
  (STRICT_MONEY && moneyUnpaired.length > 0)
) {
  process.exit(1);
}
console.log('[admin-table-align] ok');
