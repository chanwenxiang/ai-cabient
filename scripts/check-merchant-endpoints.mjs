/**
 * M3 门禁：试点端点字面量不得在 pages/composables 再散落。
 * 用法：node scripts/check-merchant-endpoints.mjs
 */
import fs from 'node:fs';
import path from 'node:path';

const endpointsFile = path.resolve('clients/merchant-mp/src/api/endpoints.ts');
const scanRoots = [
  path.resolve('clients/merchant-mp/src/pages'),
  path.resolve('clients/merchant-mp/src/composables'),
  // P2：utils 曾漏扫（dict-runtime 裸路径漏网即此处盲区）
  path.resolve('clients/merchant-mp/src/utils'),
  path.resolve('clients/merchant-mp/src/components')
];

const text = fs.readFileSync(endpointsFile, 'utf8');
const listMatch = text.match(/MERCHANT_ENDPOINT_PILOT_LITERALS\s*=\s*\[([\s\S]*?)\]\s*as\s*const/);
if (!listMatch) {
  console.error('check-merchant-endpoints: MERCHANT_ENDPOINT_PILOT_LITERALS not found');
  process.exit(1);
}
const literals = [...listMatch[1].matchAll(/'([^']+)'/g)].map((m) => m[1]);
if (!literals.length) {
  console.error('check-merchant-endpoints: empty pilot list');
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
    const line = body.split(/\n/).findIndex((l) => l.includes(lit)) + 1;
    const rel = path.relative(process.cwd(), file).replace(/\\/g, '/');
    offenders.push(`${rel}:${line} bare ${lit} — use MerchantEndpoints`);
  }
}

if (offenders.length) {
  console.error('M3 merchant endpoints check failed:\n' + offenders.join('\n'));
  process.exit(1);
}
console.log(`check-merchant-endpoints: ok (${literals.length} pilot literals)`);

// ── 2026-10-13：商户端「要货申请」能力**整体下线**（台账 docs/SATELLITE_WAREHOUSE.md）──
// 拍板依据：补货员异地有自己分仓，日常补货不该走「申请→运营审批」要货流；
// 替代能力 = 自主补货 POST /api/v2/merchant/replenishment/self-initiate（无审批直接生效）。
// 后端已 DROP merchant_replenishment_request(_line) 并删 6 个要货端点（V346）。
// 这里钉成**拒绝式**门禁：这些字面量一旦重新出现，说明有人把与拍板冲突的功能加了回来
// —— 必须先回读台账条目、登记新结论再动代码，不能悄悄复活。
// 后端端点那半边由 check-openapi-types（从运行容器重生成并 git diff）钉住。
const REMOVED_MERCHANT_SURFACES = [
  { token: 'pages/request', why: '商户端要货申请分包（2026-10-13 拍板下线，改自主补货）' },
  {
    token: 'replenishment/requests',
    why: '要货端点字面量（2026-10-13 拍板下线，改 self-initiate）'
  }
];
const merchantRemovedRoots = [
  path.resolve('clients/merchant-mp/src'),
  path.resolve('clients/merchant-mp/src/pages.json')
];
const merchantResurrected = [];
for (const file of merchantRemovedRoots.flatMap((r) =>
  fs.existsSync(r) && fs.statSync(r).isFile() ? [r] : walk(r)
)) {
  const body = fs.readFileSync(file, 'utf8');
  for (const { token, why } of REMOVED_MERCHANT_SURFACES) {
    if (!body.includes(token)) continue;
    const line = body.split(/\n/).findIndex((l) => l.includes(token)) + 1;
    const rel = path.relative(process.cwd(), file).replace(/\\/g, '/');
    merchantResurrected.push(`${rel}:${line} ${token} — ${why}`);
  }
}
if (merchantResurrected.length) {
  console.error(
    'merchant replenishment-request surface resurrected:\n' + merchantResurrected.join('\n')
  );
  process.exit(1);
}
console.log(
  `check-merchant-endpoints: ok（要货已下线面 ${REMOVED_MERCHANT_SURFACES.length} 项均未复活）`
);
