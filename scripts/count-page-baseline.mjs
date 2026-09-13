import fs from 'node:fs';

function pagesJson(file) {
  const j = JSON.parse(fs.readFileSync(file, 'utf8'));
  const paths = [];
  for (const it of j.pages || []) if (it.path) paths.push(it.path);
  for (const sp of j.subPackages || []) {
    for (const p of sp.pages || []) {
      paths.push(`${String(sp.root).replace(/\/$/, '')}/${p.path}`);
    }
  }
  return paths;
}

const c = pagesJson('clients/consumer-mp/src/pages.json');
const m = pagesJson('clients/merchant-mp/src/pages.json');
const router = fs.readFileSync('clients/admin-vue/src/router/index.ts', 'utf8');
const menu = fs.readFileSync('clients/admin-vue/src/config/menu.ts', 'utf8');
const adminPaths = [...router.matchAll(/path:\s*['"]([^'"]+)['"]/g)].map((x) => x[1]);
const menuPaths = [...menu.matchAll(/path:\s*['"]([^'"]+)['"]/g)].map((x) => x[1]);
/** 全部 path 声明（含 /、动态段、兜底）——与 MASTER「76」对齐 */
const adminRouteDecls = [...new Set(adminPaths)];
/** 排除根路径与动态段后的可读 unique——脚本历史口径「73」 */
const adminUniqueNonDynamic = [
  ...new Set(adminPaths.filter((p) => p && p !== '/' && !p.includes(':')))
];

const baseline = {
  c: 24,
  m: 22,
  adminRouteDecls: 76,
  adminUniqueNonDynamic: 73,
  menu: 66
};

const report = {
  consumerPages: c.length,
  merchantPages: m.length,
  adminRouteDecls: adminRouteDecls.length,
  adminUniqueNonDynamic: adminUniqueNonDynamic.length,
  menuPaths: menuPaths.length,
  baseline,
  ok: {
    consumer: c.length === baseline.c,
    merchant: m.length === baseline.m,
    adminDecls: adminRouteDecls.length === baseline.adminRouteDecls,
    adminUnique: adminUniqueNonDynamic.length === baseline.adminUniqueNonDynamic,
    menu: menuPaths.length === baseline.menu
  },
  menuSample: menuPaths.slice(0, 15)
};

console.log(JSON.stringify(report, null, 2));
fs.mkdirSync('docs/uat-screenshots/2026-09-12', { recursive: true });
fs.writeFileSync(
  'docs/uat-screenshots/2026-09-12/menu-paths.json',
  JSON.stringify(
    {
      menuPaths,
      adminRouteDecls,
      adminUniqueNonDynamic,
      note: 'decls=76 includes /, devices/:id, :pathMatch; uniqueNonDynamic=73 excludes those'
    },
    null,
    2
  )
);

const failed = Object.entries(report.ok).filter(([, v]) => !v);
if (failed.length) {
  console.error('BASELINE DRIFT', failed);
  process.exit(1);
}
