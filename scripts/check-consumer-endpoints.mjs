/**
 * C2 门禁：试点端点字面量不得在 pages/composables 再散落。
 * 用法：node scripts/check-consumer-endpoints.mjs
 */
import fs from 'node:fs';
import path from 'node:path';

const endpointsFile = path.resolve('clients/consumer-mp/src/api/endpoints.ts');
const scanRoots = [
  path.resolve('clients/consumer-mp/src/pages'),
  path.resolve('clients/consumer-mp/src/composables'),
  // 审计 P3-2：与 merchant 侧对齐——utils 曾漏扫（merchant 侧 check 自注释承认此盲区）
  path.resolve('clients/consumer-mp/src/utils'),
  path.resolve('clients/consumer-mp/src/components')
];

const text = fs.readFileSync(endpointsFile, 'utf8');
const listMatch = text.match(/CONSUMER_ENDPOINT_PILOT_LITERALS\s*=\s*\[([\s\S]*?)\]\s*as\s*const/);
if (!listMatch) {
  console.error('check-consumer-endpoints: CONSUMER_ENDPOINT_PILOT_LITERALS not found');
  process.exit(1);
}
const literals = [...listMatch[1].matchAll(/'([^']+)'/g)].map((m) => m[1]);
if (!literals.length) {
  console.error('check-consumer-endpoints: empty pilot list');
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
    offenders.push(`${rel}:${line} bare ${lit} — use ConsumerEndpoints`);
  }
}

if (offenders.length) {
  console.error('C2 consumer endpoints check failed:\n' + offenders.join('\n'));
  process.exit(1);
}
console.log(`check-consumer-endpoints: ok (${literals.length} pilot literals)`);

// ── CB-030：消费端「购物视频」能力**有意不开放**（竞品台账 docs/COMPETITOR_BENCHMARK.md）──
// 台账结论是同行一致以隐私为由不提供、且我们本期只做运营侧复核 ⇒ 消费端这条链路已整体下线：
// 前端删 pages/video 分包与订单详情入口，后端删 GET /api/v2/orders/{orderId}/video。
// 这里把它钉成**拒绝式**门禁：这些字面量一旦重新出现，说明有人把与台账结论冲突的功能加了
// 回来 —— 必须先回读台账条目、登记新结论（追加「修正 CB-030」）再动代码，不能悄悄复活。
// 后端端点那半边由 check-openapi-types（从运行容器重生成并 git diff）钉住。
// 扫描范围只用 src/ 与 pages.json（tests/ 里说明「已删除」的注释不该被误伤）。
const REMOVED_CONSUMER_SURFACES = [
  { token: 'pages/video', why: '消费端购物视频分包（CB-030 有意不开放）' },
  { token: 'orderVideo', why: '消费端订单录像端点常量（CB-030 有意不开放）' }
];
const removedRoots = [
  path.resolve('clients/consumer-mp/src'),
  path.resolve('clients/consumer-mp/src/pages.json')
];
const resurrected = [];
for (const file of removedRoots.flatMap((r) =>
  fs.existsSync(r) && fs.statSync(r).isFile() ? [r] : walk(r)
)) {
  const body = fs.readFileSync(file, 'utf8');
  for (const { token, why } of REMOVED_CONSUMER_SURFACES) {
    if (!body.includes(token)) continue;
    const line = body.split(/\n/).findIndex((l) => l.includes(token)) + 1;
    const rel = path.relative(process.cwd(), file).replace(/\\/g, '/');
    resurrected.push(`${rel}:${line} ${token} — ${why}`);
  }
}
if (resurrected.length) {
  console.error('CB-030 consumer video surface resurrected:\n' + resurrected.join('\n'));
  process.exit(1);
}
console.log(
  `check-consumer-endpoints: ok（CB-030 已下线面 ${REMOVED_CONSUMER_SURFACES.length} 项均未复活）`
);
