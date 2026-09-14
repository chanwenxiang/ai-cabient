/**
 * A-P2-005 门禁：试点端点字面量不得在 views/composables 再散落。
 * 用法：node scripts/check-admin-endpoints.mjs
 */
import fs from 'node:fs';
import path from 'node:path';

const endpointsFile = path.resolve('clients/admin-vue/src/api/endpoints.ts');
const scanRoots = [
  path.resolve('clients/admin-vue/src/views'),
  path.resolve('clients/admin-vue/src/composables')
];

const text = fs.readFileSync(endpointsFile, 'utf8');
const listMatch = text.match(/ADMIN_ENDPOINT_PILOT_LITERALS\s*=\s*\[([\s\S]*?)\]\s*as\s*const/);
if (!listMatch) {
  console.error('check-admin-endpoints: ADMIN_ENDPOINT_PILOT_LITERALS not found');
  process.exit(1);
}
const literals = [...listMatch[1].matchAll(/'([^']+)'/g)].map((m) => m[1]);
if (!literals.length) {
  console.error('check-admin-endpoints: empty pilot list');
  process.exit(1);
}

function walk(dir, out = []) {
  if (!fs.existsSync(dir)) return out;
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      walk(full, out);
      continue;
    }
    if (!/\.(vue|ts|tsx|js)$/.test(entry.name)) continue;
    out.push(full);
  }
  return out;
}

const offenders = [];
for (const file of scanRoots.flatMap((r) => walk(r))) {
  const body = fs.readFileSync(file, 'utf8');
  for (const lit of literals) {
    if (!body.includes(lit)) continue;
    // 允许拼接 trend?days= 等由函数生成；字面量基路径仍禁
    const line = body.split(/\n/).findIndex((l) => l.includes(lit)) + 1;
    const rel = path.relative(process.cwd(), file).replace(/\\/g, '/');
    offenders.push(`${rel}:${line} bare ${lit} — use AdminEndpoints`);
  }
  // trend 查询串也禁裸前缀
  if (/\/api\/v2\/ops\/admin\/trend(\/|\?)/.test(body)) {
    const line = body.split(/\n/).findIndex((l) => /\/api\/v2\/ops\/admin\/trend/.test(l)) + 1;
    const rel = path.relative(process.cwd(), file).replace(/\\/g, '/');
    offenders.push(`${rel}:${line} bare trend path — use AdminEndpoints.trend*`);
  }
}

if (offenders.length) {
  console.error('A-P2-005 admin endpoints check failed:\n' + offenders.join('\n'));
  process.exit(1);
}
console.log(`check-admin-endpoints: ok (${literals.length} pilot literals)`);
