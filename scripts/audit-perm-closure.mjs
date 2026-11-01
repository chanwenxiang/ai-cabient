/**
 * R10 权限闭环三方对账（分析脚本，**未**进 `check:audit-gates` 聚合链）。
 * Usage: node scripts/audit-perm-closure.mjs   （必须从仓库根跑；需要活库 docker 可连）
 *   A. 后端 @RequiresPermissions 码（全服务）
 *   B. ops_permission 表行（perm_code / perm_type / status）
 *   C. 前端导航码（admin-vue menu.ts + merchant-mp merchant-nav.ts）+ 全前端字面量散引
 * 重点是既有 `check:menu-permissions` / `check:frontend-perm-codes` 都没查的反向：
 *   「后端有码但 ops_permission 无行」⇒ hasPerm 恒 false；
 *   「menu.ts 引用了行存在但非 ACTIVE nav 的码」⇒ 过 hasPerm 却卡 isNavMenuActive ⇒ 403（D28-B15 同形）。
 * 🔴 若要升级成门禁，A 集必须同时扫「注解」与「字符串用法」两类：
 *   `ops:audit:recent` 在 AdminDashboardController / OpsAuditQueryService 里是非注解用法，
 *   只扫注解会把它误判成幽灵码（2026-11-02 R10 实测到的脚本盲区）。
 * 结论与 4 个真幽灵码见 docs/module-audit/rounds/R10-crosscut.md §10.2（台账 MA-036）。
 */
import { execSync, execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';

const root = path.resolve('.');
const read = (rel) => fs.readFileSync(path.join(root, rel), 'utf8');

// ---- A. 后端码 ----
const backend = new Map(); // code -> Set<file>
const javaFiles = execSync('git ls-files "services/**/src/main/java/*.java" | tr -d "\\\r"', {
  encoding: 'utf8',
  maxBuffer: 1 << 26
})
  .split('\n')
  .filter(Boolean);
const reAnno = /@RequiresPermissions\s*\(\s*(?:\{\s*)?"([^"]+)"([^)]*)\)?\s*\)?/g;
for (const f of javaFiles) {
  const src = read(f);
  if (!src.includes('@RequiresPermissions')) continue;
  let m;
  reAnno.lastIndex = 0;
  while ((m = reAnno.exec(src))) {
    const code = m[1];
    if (!backend.has(code)) backend.set(code, new Set());
    backend.get(code).add(f);
  }
  // 数组形式的第二个及以后元素
  const reArr = /@RequiresPermissions\s*\(\s*\{([^}]*)\}/g;
  while ((m = reArr.exec(src))) {
    for (const c of m[1].match(/"([^"]+)"/g) || []) {
      const code = c.replace(/"/g, '');
      if (!backend.has(code)) backend.set(code, new Set());
      backend.get(code).add(f);
    }
  }
}

// ---- B. ops_permission ----
// 🔴 execFileSync + 参数数组：绕过 Windows cmd 的引号地狱（嵌套单引号会被拆成额外参数）
const csv = execFileSync(
  'docker',
  [
    'exec',
    'ai-cabinet-postgres-1',
    'psql',
    '-U',
    'aicabinet',
    '-d',
    'aicabinet',
    '-At',
    '-F',
    '|',
    '-c',
    "select perm_code, perm_type, coalesce(status,''), coalesce(path,'') from ops_permission"
  ],
  { encoding: 'utf8', maxBuffer: 1 << 26 }
)
  .split('\n')
  .filter(Boolean);
const db = new Map();
for (const line of csv) {
  const [code, type, status, p] = line.split('|');
  if (!db.has(code)) db.set(code, { types: new Set(), statuses: new Set(), paths: new Set() });
  const e = db.get(code);
  e.types.add(type);
  e.statuses.add(status);
  if (p) e.paths.add(p);
}

// ---- C. 前端导航码 ----
const navFiles = [
  'clients/admin-vue/src/config/menu.ts',
  'clients/merchant-mp/src/config/merchant-nav.ts'
];
const front = new Map();
for (const f of navFiles) {
  if (!fs.existsSync(path.join(root, f))) {
    console.log('!! 缺文件', f);
    continue;
  }
  const src = read(f);
  for (const m of src.matchAll(/perm:\s*(?:'([^']+)'|"([^"]+)"|\[([^\]]+)\])/g)) {
    const list = m[1] || m[2] ? [m[1] || m[2]] : (m[3] || '').match(/'[^']+'|"[^"]+"/g) || [];
    for (const raw of list) {
      const code = String(raw).replace(/['"]/g, '').trim();
      if (!code || code.includes('${')) continue;
      if (!front.has(code)) front.set(code, new Set());
      front.get(code).add(f);
    }
  }
}

const report = (title, lines) => {
  console.log(`\n### ${title} — ${lines.length} 条`);
  lines.slice(0, 40).forEach((l) => console.log('  ', l));
  if (lines.length > 40) console.log('   …', lines.length - 40, '条省略');
};

// 1) 后端有码、表里完全没有行
const missingRow = [...backend.keys()].filter((c) => !db.has(c)).sort();
report(
  'A∖B 后端引用但 ops_permission 无行（⇒ 除超管通配外没人能过）',
  missingRow.map((c) => `${c}  ← ${[...backend.get(c)][0]}`)
);

// 2) 前端导航引用、但表里非 ACTIVE
const notActive = [...front.keys()]
  .filter((c) => {
    const e = db.get(c);
    return e && ![...e.statuses].includes('ACTIVE');
  })
  .sort();
report(
  'C∩B 但 status 非 ACTIVE（过 hasPerm 卡 isNavMenuActive 的 403 面）',
  notActive.map(
    (c) => `${c}  status=${[...db.get(c).statuses].join('/')}  ← ${[...front.get(c)][0]}`
  )
);

// 3) 前端导航引用、表里没有
const frontMissing = [...front.keys()].filter((c) => !db.has(c)).sort();
report('C∖B 前端导航引用但表无行', frontMissing);

// 4) 表里 ACTIVE 但既不在后端也不在任何前端引用（真·幽灵码）
//    🔴 C 只取 nav 配置会造成巨大盲区：按钮级 type=F（ops:device:create）与 mp 侧码
//    是在 .vue/.ts 里散引的 ⇒ 这里把「全前端引用」单独扫一遍再判幽灵。
const clientFiles = execSync(
  'git ls-files "clients/**/*.ts" "clients/**/*.vue" "packages/**/*.ts" | grep -v node_modules | grep -v "/dist/" | grep -v "static/" | tr -d "\\\r"',
  { encoding: 'utf8', maxBuffer: 1 << 26 }
)
  .split('\n')
  .filter(Boolean);
const anyFront = new Set(front.keys());
for (const f of clientFiles) {
  let src;
  try {
    src = read(f);
  } catch {
    continue;
  }
  for (const m of src.matchAll(
    /['"`](ops:[A-Za-z0-9_:.*-]+|merchant:[A-Za-z0-9_:.*-]+|consumer:[A-Za-z0-9_:.*-]+)['"`]/g
  )) {
    anyFront.add(m[1]);
  }
}
console.log('\n全前端引用码数（含散引）=', anyFront.size, '（nav 配置内', front.size, '）');

const ghost = [...db.keys()]
  .filter((c) => {
    const e = db.get(c);
    return [...e.statuses].includes('ACTIVE') && !backend.has(c) && !anyFront.has(c);
  })
  .sort();
report(
  'B∖(A∪C全) ACTIVE 但两端零引用（真幽灵权限码）',
  ghost.map(
    (c) => `${c}  type=${[...db.get(c).types].join('/')} path=${[...db.get(c).paths].join('/')}`
  )
);

console.log('\n--- 规模 ---');
console.log(
  'backend codes',
  backend.size,
  '| db rows',
  db.size,
  '| nav codes',
  front.size,
  '| 全前端引用',
  anyFront.size
);
