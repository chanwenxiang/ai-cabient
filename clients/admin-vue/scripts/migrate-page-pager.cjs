const fs = require('fs');
const path = require('path');

const root = path.join(__dirname, '../src/views');

function walk(dir, out = []) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) walk(p, out);
    else if (e.name.endsWith('.vue')) out.push(p);
  }
  return out;
}

function pickHydrated(s, file) {
  const base = path.basename(file);
  if (base === 'FundBillView.vue') return 'MULTI_FUNDBILL';
  if (base === 'LineManagerView.vue' || base === 'MerchantWithdrawView.vue') return 'MULTI_TAB';
  if (base === 'MerchantSplitsView.vue') return 'MULTI_MERCHANT';
  if (base === 'WarehouseView.vue') return 'hydratedTabs.has(tab)';
  if (base === 'DashboardView.vue') return 'listHydrated';
  if (/const listHydrated = ref/.test(s) || /listHydrated\.value\s*=/.test(s)) return 'listHydrated';
  if (/hydratedTabs/.test(s)) return 'hydratedTabs.has(tab)';
  return null;
}

function ensureImport(src) {
  if (src.includes("from '@/components/PagePager.vue'") || src.includes('from "@/components/PagePager.vue"')) {
    return src;
  }
  if (src.includes("from '@/components/TableActions.vue'")) {
    return src.replace(
      "from '@/components/TableActions.vue';",
      "from '@/components/TableActions.vue';\nimport PagePager from '@/components/PagePager.vue';"
    );
  }
  if (/^import .+ from ['"]vue['"];/m.test(src)) {
    return src.replace(
      /^(import .+ from ['"]vue['"];)/m,
      "$1\nimport PagePager from '@/components/PagePager.vue';"
    );
  }
  return src.replace(/<script setup[^>]*>/, (m) => `${m}\nimport PagePager from '@/components/PagePager.vue';`);
}

function replacePagers(src, hydratedExpr) {
  const re = /<(?:div)([^>]*)\bclass="page-pager"([^>]*)>\s*<el-pagination([\s\S]*?)(?:\/>|><\/el-pagination>)\s*<\/div>/g;
  let count = 0;
  const next = src.replace(re, (full, before, after, attrs) => {
    count += 1;
    const openAttrs = `${before || ''}${after || ''}`;
    const vif = /v-if="([^"]+)"/.exec(openAttrs);
    let h = hydratedExpr;
    // multi-tab heuristics by surrounding context not available; caller handles MULTI_*
    if (h === 'MULTI_FUNDBILL') {
      h = attrs.includes('billPage') || attrs.includes('onBill') || attrs.includes('billSize') ? 'listHydrated' : 'ledgerHydrated';
      // inspect attrs for clues
      if (/billPage|billSize|onBillSizeChange|displayBills/.test(attrs + full)) h = 'listHydrated';
      else if (/ledgerPage|ledgerSize|onLedger/.test(attrs)) h = 'ledgerHydrated';
      else h = count === 1 ? 'listHydrated' : 'ledgerHydrated';
    }
    if (h === 'MULTI_TAB') {
      h = count === 1 ? 'listHydrated' : 'listHydrated';
    }
    if (h === 'MULTI_MERCHANT') {
      h = count === 1 ? 'merchantsHydrated' : 'splitsLoaded';
    }
    const prefix = vif ? ` v-if="${vif[1]}"` : '';
    const cleaned = attrs.replace(/^\r?\n/, '\n');
    return `<PagePager${prefix} :hydrated="${h}"${cleaned}/>`;
  });
  return { next, count };
}

const files = walk(root);
const report = [];
let changed = 0;

for (const file of files) {
  let s = fs.readFileSync(file, 'utf8');
  if (!s.includes('<el-pagination') || !s.includes('page-pager')) continue;
  if (s.includes('<PagePager')) {
    report.push(`skip-already ${path.relative(root, file)}`);
    continue;
  }
  const hydrated = pickHydrated(s, file);
  if (!hydrated) {
    report.push(`skip-no-flag ${path.relative(root, file)}`);
    continue;
  }
  const { next, count } = replacePagers(s, hydrated);
  if (!count || next === s) {
    report.push(`no-replace ${path.relative(root, file)} flag=${hydrated}`);
    continue;
  }
  let out = ensureImport(next);
  fs.writeFileSync(file, out);
  changed += 1;
  report.push(`ok(${count}) ${path.relative(root, file)} -> ${hydrated}`);
}

console.log(report.join('\n'));
console.log('CHANGED', changed);
