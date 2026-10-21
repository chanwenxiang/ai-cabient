#!/usr/bin/env node
/**
 * Admin 生产包体积门禁（对已提交的 static/admin 产物）。
 * 防 ui-vendor / 入口 / 单页懒加载 chunk / 总 JS 静默膨胀。
 *
 *   node scripts/check-admin-bundle-budget.mjs
 *   pnpm check:admin-bundle
 *
 * 预算可用环境变量覆盖（单位 KB）：
 *   ADMIN_BUDGET_UI_VENDOR_KB=1200
 *   ADMIN_BUDGET_INDEX_KB=125
 *   ADMIN_BUDGET_LEAFLET_KB=220
 *   ADMIN_BUDGET_ECHARTS_KB=600
 *   ADMIN_BUDGET_ROUTE_KB=150
 *   ADMIN_BUDGET_TOTAL_JS_KB=3400
 *
 * 分类按 chunk **名前缀**判定（`ui-vendor-` / `index-` / `leaflet-` / `echarts-vendor-`，
 * 其余全算 route）。⇒ 新增一类必须同时改这里与 vite.config.ts 的 manualChunks，
 * 两边不同源就会让整类掉进 route 桶。
 */
import { readdirSync, statSync, writeFileSync, mkdirSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const assetsDir = join(
  root,
  'services',
  'trade-service',
  'src',
  'main',
  'resources',
  'static',
  'admin',
  'assets'
);

function envKb(name, fallback) {
  const raw = process.env[name];
  if (raw == null || raw === '') return fallback;
  const n = Number(raw);
  if (!Number.isFinite(n) || n <= 0) throw new Error(`invalid ${name}=${raw}`);
  return n;
}

const BUDGET = {
  uiVendorKb: envKb('ADMIN_BUDGET_UI_VENDOR_KB', 1200),
  // 120 定工时几乎零余量（2026-10-21 产物 122834B = 119.95KB，仅空 51B）⇒ 加两条 API 路径
  // 字面量就必红（9935133d 实测 123087B = 120.20KB，逐字比对确认增量就是 endpoints.ts 里
  // model-sync-explicit / model-push-explicit 两个串，无整包误引入）。抬到 125 ≈ 4% 余量。
  indexKb: envKb('ADMIN_BUDGET_INDEX_KB', 125),
  leafletKb: envKb('ADMIN_BUDGET_LEAFLET_KB', 220),
  // echarts+zrender 按需注册后的实测体积 ≈513KB（全量引入约 1MB）。600KB 留 ~17% 余量，
  // 既能容忍 minor 升级，又能抓住「有人退回 import * as echarts from 'echarts'」这类回归。
  echartsVendorKb: envKb('ADMIN_BUDGET_ECHARTS_KB', 600),
  routeKb: envKb('ADMIN_BUDGET_ROUTE_KB', 150),
  // 3200 定于 f030f09f（74 页时代）。2026-10-13 平台资金池/设备租赁两个新财务页
  // （CB-033/034）落地后实测 3208.3KB ⇒ 3300，留 ~3% 余量给后续页面；单项预算不变。
  // 2026-10-21 设备详情/将邑 SN、SKU 件装数、按单拉取视频入口、资金动作在途防重落地后
  // 实测 3300.4KB（较上版 +2.9KB，最大单页 +1.1KB OrderListView）⇒ 3400，仍留 ~3%。
  // 抬预算前先看 chunk 级增量：若某一页暴涨，优先查是否误引入整包，而不是抬总额度。
  totalJsKb: envKb('ADMIN_BUDGET_TOTAL_JS_KB', 3400)
};

function kb(bytes) {
  return bytes / 1024;
}

function fail(msg) {
  console.error(`[check-admin-bundle] ${msg}`);
  process.exit(1);
}

const files = readdirSync(assetsDir)
  .filter((f) => f.endsWith('.js'))
  .map((f) => {
    const size = statSync(join(assetsDir, f)).size;
    return { name: f, bytes: size, kb: kb(size) };
  })
  .sort((a, b) => b.bytes - a.bytes);

if (!files.length) fail(`no js in ${assetsDir}`);

const uiVendor = files.filter((f) => f.name.startsWith('ui-vendor-'));
const index = files.filter((f) => f.name.startsWith('index-'));
const leaflet = files.filter((f) => f.name.startsWith('leaflet-'));
const echartsVendor = files.filter((f) => f.name.startsWith('echarts-vendor-'));
const routes = files.filter(
  (f) =>
    !f.name.startsWith('ui-vendor-') &&
    !f.name.startsWith('index-') &&
    !f.name.startsWith('leaflet-') &&
    !f.name.startsWith('echarts-vendor-')
);

const totalKb = files.reduce((s, f) => s + f.kb, 0);
const violations = [];

function checkNamed(label, list, maxKb, required = true) {
  if (!list.length) {
    if (required) violations.push(`missing ${label} chunk`);
    return;
  }
  for (const f of list) {
    if (f.kb > maxKb) {
      violations.push(`${label} ${f.name} = ${f.kb.toFixed(1)}KB > ${maxKb}KB`);
    } else {
      console.log(`  OK ${label} ${f.name} ${f.kb.toFixed(1)}KB (≤${maxKb})`);
    }
  }
}

checkNamed('ui-vendor', uiVendor, BUDGET.uiVendorKb);
checkNamed('index', index, BUDGET.indexKb);
checkNamed('leaflet', leaflet, BUDGET.leafletKb, false);
checkNamed('echarts-vendor', echartsVendor, BUDGET.echartsVendorKb, false);

for (const f of routes) {
  if (f.kb > BUDGET.routeKb) {
    violations.push(`route ${f.name} = ${f.kb.toFixed(1)}KB > ${BUDGET.routeKb}KB`);
  }
}
const biggestRoute = routes[0];
if (biggestRoute) {
  // 别无条件打 OK —— 超标时也打 OK 就是把判据的结论说谎（本条曾长期掩盖 EChart 513KB 违规）。
  const over = biggestRoute.kb > BUDGET.routeKb;
  console.log(
    `  ${over ? 'OVER' : 'OK'} largest route ${biggestRoute.name} ${biggestRoute.kb.toFixed(1)}KB (budget ≤${BUDGET.routeKb})`
  );
}

if (totalKb > BUDGET.totalJsKb) {
  violations.push(`total JS ${totalKb.toFixed(1)}KB > ${BUDGET.totalJsKb}KB`);
} else {
  console.log(`  OK total JS ${totalKb.toFixed(1)}KB (≤${BUDGET.totalJsKb})`);
}

mkdirSync(join(root, 'target'), { recursive: true });
writeFileSync(
  join(root, 'target', 'admin-bundle-budget.json'),
  JSON.stringify(
    {
      budgets: BUDGET,
      totalJsKb: Number(totalKb.toFixed(2)),
      top: files.slice(0, 15).map((f) => ({ name: f.name, kb: Number(f.kb.toFixed(2)) })),
      violations
    },
    null,
    2
  )
);

if (violations.length) {
  console.error('[check-admin-bundle] FAILED:');
  for (const v of violations) console.error(`  - ${v}`);
  process.exit(1);
}

console.log('[check-admin-bundle] OK');
