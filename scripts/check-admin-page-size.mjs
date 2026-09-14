/**
 * A-P1-002：运营后台列表 page-sizes 不得超过 ADMIN_LIST_MAX_PAGE_SIZE（50）。
 * 用法：node scripts/check-admin-page-size.mjs
 */
import fs from 'node:fs';
import path from 'node:path';

const MAX = 50;
const root = path.resolve('clients/admin-vue/src/views');
const offenders = [];

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
    const re = /:page-sizes\s*=\s*"\[([^\]]+)\]"/g;
    let m;
    while ((m = re.exec(text))) {
      const nums = m[1]
        .split(',')
        .map((s) => Number(String(s).trim()))
        .filter((n) => Number.isFinite(n));
      const over = nums.filter((n) => n > MAX);
      if (over.length) {
        const line = text.slice(0, m.index).split(/\n/).length;
        offenders.push(`${rel}:${line} page-sizes includes ${over.join(',')}`);
      }
    }
    // also catch :page-sizes="ADMIN_LIST_PAGE_SIZES" as OK (no numbers > 50 in literal)
  }
}

walk(root);

if (offenders.length) {
  console.error(`[admin-page-size] FAIL: ${offenders.length} view(s) allow pageSize > ${MAX}`);
  for (const o of offenders) console.error(`  - ${o}`);
  console.error('Use ADMIN_LIST_PAGE_SIZES from @/utils/admin-list-pager (max 50).');
  process.exit(1);
}

console.log(`[admin-page-size] OK: no :page-sizes literal exceeds ${MAX}`);
