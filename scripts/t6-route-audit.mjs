import fs from 'node:fs';
import path from 'node:path';

const router = fs.readFileSync('clients/admin-vue/src/router/index.ts', 'utf8');
const all = [...router.matchAll(/path:\s*['"]([^'"]+)['"]/g)].map((x) => x[1]);
const unique = [...new Set(all)];
const withParam = all.filter((p) => p.includes(':'));
const dups = all.filter((p, i) => all.indexOf(p) !== i);

function walkViews(dir, acc = []) {
  for (const name of fs.readdirSync(dir)) {
    const p = path.join(dir, name);
    if (fs.statSync(p).isDirectory()) walkViews(p, acc);
    else if (name.endsWith('.vue')) acc.push(path.relative('clients/admin-vue/src/views', p).replace(/\\/g, '/'));
  }
  return acc;
}
const views = walkViews('clients/admin-vue/src/views');

// Classify: top-level auth vs children under AdminLayout
const topLevel = [];
const children = [];
let inChildren = false;
for (const line of router.split('\n')) {
  if (line.includes('bizChildren')) inChildren = true;
  if (line.includes('const routes')) inChildren = false;
  const m = line.match(/path:\s*['"]([^'"]+)['"]/);
  if (!m) continue;
  // crude: children paths usually lack leading slash in this file
  if (m[1].startsWith('/') || m[1] === ':pathMatch(.*)*') topLevel.push(m[1]);
  else children.push(m[1]);
}

const out = {
  pathDeclsTotal: all.length,
  uniquePaths: unique.length,
  paramPaths: withParam,
  duplicateDecls: [...new Set(dups)],
  viewVueCount: views.length,
  topLevelish: topLevel,
  childishCount: children.length,
  uniqueSorted: unique.sort()
};
fs.writeFileSync('docs/uat-screenshots/2026-09-12/t6-route-audit.json', JSON.stringify(out, null, 2));
console.log(JSON.stringify({
  pathDeclsTotal: out.pathDeclsTotal,
  uniquePaths: out.uniquePaths,
  paramPaths: out.paramPaths,
  duplicateDecls: out.duplicateDecls,
  viewVueCount: out.viewVueCount,
  topLevelish: out.topLevelish
}, null, 2));
