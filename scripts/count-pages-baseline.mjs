import fs from 'node:fs';
import path from 'node:path';

function pagesFromJson(file) {
  const j = JSON.parse(fs.readFileSync(file, 'utf8'));
  const paths = [];
  for (const p of j.pages || []) {
    if (p.path) paths.push(p.path);
  }
  for (const sp of j.subPackages || []) {
    const root = String(sp.root || '').replace(/\/$/, '');
    for (const p of sp.pages || []) {
      if (p.path) paths.push(`${root}/${p.path}`);
    }
  }
  return paths;
}

function quotedPaths(src) {
  return [...src.matchAll(/path:\s*['"]([^'"]+)['"]/g)].map((m) => m[1]);
}

const consumer = pagesFromJson('clients/consumer-mp/src/pages.json');
const merchant = pagesFromJson('clients/merchant-mp/src/pages.json');
const router = fs.readFileSync('clients/admin-vue/src/router/index.ts', 'utf8');
const menu = fs.readFileSync('clients/admin-vue/src/config/menu.ts', 'utf8');
const adminPaths = quotedPaths(router).filter((p) => p && p !== '/' && !p.includes(':'));
const menuPaths = quotedPaths(menu);

const out = {
  consumerPages: consumer.length,
  merchantPages: merchant.length,
  adminRoutePathLiterals: adminPaths.length,
  menuPaths: menuPaths.length,
  baseline: { consumer: 24, merchant: 22, adminRoutes: 76, menu: 66 },
  menuSample: menuPaths.slice(0, 15),
  allMenu: menuPaths
};
fs.writeFileSync('docs/uat-screenshots/2026-09-12/page-count.json', JSON.stringify(out, null, 2));
console.log(JSON.stringify({ ...out, allMenu: undefined }, null, 2));
