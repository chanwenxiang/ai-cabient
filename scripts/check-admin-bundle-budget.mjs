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
 *   ADMIN_BUDGET_INDEX_KB=120
 *   ADMIN_BUDGET_LEAFLET_KB=220
 *   ADMIN_BUDGET_ROUTE_KB=150
 *   ADMIN_BUDGET_TOTAL_JS_KB=3200
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
  indexKb: envKb('ADMIN_BUDGET_INDEX_KB', 120),
  leafletKb: envKb('ADMIN_BUDGET_LEAFLET_KB', 220),
  routeKb: envKb('ADMIN_BUDGET_ROUTE_KB', 150),
  totalJsKb: envKb('ADMIN_BUDGET_TOTAL_JS_KB', 3200)
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
const routes = files.filter(
  (f) =>
    !f.name.startsWith('ui-vendor-') &&
    !f.name.startsWith('index-') &&
    !f.name.startsWith('leaflet-')
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

for (const f of routes) {
  if (f.kb > BUDGET.routeKb) {
    violations.push(`route ${f.name} = ${f.kb.toFixed(1)}KB > ${BUDGET.routeKb}KB`);
  }
}
const biggestRoute = routes[0];
if (biggestRoute) {
  console.log(
    `  OK largest route ${biggestRoute.name} ${biggestRoute.kb.toFixed(1)}KB (budget ≤${BUDGET.routeKb})`
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
