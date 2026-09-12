/**
 * A07′：admin 表格列语义 class 门禁。
 * - 硬错误：金额语义 label 未挂 col-money（STRICT_MONEY=1）
 * - 统计：裸 align="center" 且未挂 col-status/col-action/col-money（建议补语义 class）
 * - 产品对齐：表头+单元格一律居中（main.css）；col-text + align=center 不再视为冲突
 *
 * 用法：node scripts/check-admin-table-align.mjs
 * 默认 STRICT 开启；STRICT=0 可临时放宽裸 align=center
 * STRICT_MONEY=1 时，金额语义未配对 col-money 也失败
 */
import fs from 'node:fs';
import path from 'node:path';

const root = path.resolve('clients/admin-vue/src');
const STRICT = process.env.STRICT !== '0';
const STRICT_MONEY = process.env.STRICT_MONEY === '1';
const MONEY_LABEL =
  /label\s*=\s*["'][^"']*(?:元|金额|余额|毛利|营收|成本|单价|小计|抽成|手续费)[^"']*["']/;
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
      // 全表居中后，col-text/money/status/action 均允许 align=center
      if (
        className.includes('col-text') ||
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

console.log(`[admin-table-align] col-text+align=center conflicts: 0 (relaxed: all-center)`);
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

if ((STRICT && bareCenter.length > 0) || (STRICT_MONEY && moneyUnpaired.length > 0)) {
  process.exit(1);
}
console.log('[admin-table-align] ok');
