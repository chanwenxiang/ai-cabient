#!/usr/bin/env node
/**
 * 门禁：admin 菜单项的权限码必须是「菜单级」权限（perm_type ∈ C/M 且 ACTIVE）。
 *
 * 🔴 为什么：前端路由守卫（router/index.ts 的 canAccessNav）在 hasPerm 之外还要过
 *   isNavMenuActive，而 `/rbac/me/nav` 只返回 perm_type ∈ (C,M) 的 ACTIVE 权限码
 *   —— 刻意设计：停用菜单后即使持有 ops:admin 也不该出现在导航里。
 *   ⇒ 菜单项一旦挂上「功能点(F)」类型的码，该页对**所有人**永久「无权访问」，超管也不例外，
 *     且侧栏也不会显示。2026-11-09 实测：/jiangyi-products 挂 ops:sku:edit(F) 即如此；
 *     把库里类型临时改成 C 立刻可进，改回 F 立刻又禁（单变量对照）。
 *
 * 判据来源：db/migration + db/seed 里的 ops_permission 定义（这是「全新库」的真值，
 *   比当前开发库更可信——开发库可能被人手工改过）。
 *   - VALUES / INSERT…SELECT 形态：`'<code>', '<名称>', '<C|M|F>'`
 *   - UPDATE 形态：`UPDATE ops_permission SET … perm_type = 'X' … WHERE perm_code [=|IN]`
 *   按迁移版本号**数值**顺序叠加（字符串序会把 V228 排在 V8 前面 ⇒ 旧定义覆盖新定义）。
 *
 * fail-closed：解析出的权限码过少（<150）视为解析器失效，直接判红，避免「什么都没解析到」假绿。
 *
 * 与 check-menu-permissions.mjs 的分工（不要合并成一条判据）：
 *   那个管「菜单 perm 码在后端代码里有出处」（@RequiresPermissions 字面量），
 *   本条管「该码在权限表里是菜单级 C/M」——两者都可能单独成立而另一条不成立
 *   （本例 ops:sku:edit 在后端有出处、却是 F ⇒ 只有本条能抓住）。
 */
import fs from 'node:fs';
import path from 'node:path';

const ROOT = process.cwd();
const MENU_TS = 'clients/admin-vue/src/config/menu.ts';
const DB_DIRS = [
  'services/trade-service/src/main/resources/db/migration',
  'services/trade-service/src/main/resources/db/seed'
];
const MIN_PARSED_CODES = 150;

function walk(dir, acc = []) {
  if (!fs.existsSync(dir)) return acc;
  for (const ent of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, ent.name);
    if (ent.isDirectory()) walk(p, acc);
    else if (ent.name.endsWith('.sql')) acc.push(p);
  }
  return acc;
}

/** 迁移按版本号数值升序；种子 R__ 排最后（种子是最终态） */
function versionOf(p) {
  const name = path.basename(p);
  const rSeed = /^R__/.test(name);
  if (rSeed) return Number.MAX_SAFE_INTEGER;
  const m = /^V(\d+)__/.exec(name);
  return m ? Number(m[1]) : 0;
}

function stripComments(sql) {
  return sql
    .replace(/\/\*[\s\S]*?\*\//g, ' ')
    .split('\n')
    .map((l) => l.replace(/--.*$/, ''))
    .join('\n');
}

// 定义三元组：'<code>', '<名称>', '<类型>' —— VALUES 与 INSERT…SELECT 两种形态都能命中
const DEF_RE = /'(ops:[a-z0-9:_-]+)'\s*,\s*'(?:[^']*)'\s*,\s*'([CMF])'/g;
// UPDATE 里把类型改掉的语句
const UPD_TYPE_RE = /perm_type\s*=\s*'([CMF])'/i;
const WHERE_CODE_RE = /perm_code\s*=\s*'(ops:[a-z0-9:_-]+)'/gi;
const WHERE_IN_RE = /perm_code\s+IN\s*\(([^)]*)\)/i;
const CODE_LITERAL_RE = /'(ops:[a-z0-9:_-]+)'/g;

const files = DB_DIRS.flatMap((d) => walk(path.join(ROOT, d))).sort(
  (a, b) => versionOf(a) - versionOf(b) || a.localeCompare(b)
);

const typeMap = new Map(); // code -> { type, file }
const retired = new Map(); // code -> file（UPDATE 里显式置 RETIRED 的）

for (const abs of files) {
  const rel = path.relative(ROOT, abs).replace(/\\/g, '/');
  const sql = stripComments(fs.readFileSync(abs, 'utf8'));

  for (const m of sql.matchAll(DEF_RE)) {
    typeMap.set(m[1], { type: m[2], file: rel });
  }

  for (const stmt of sql.split(';')) {
    if (!/UPDATE\s+ops_permission/i.test(stmt)) continue;
    const t = UPD_TYPE_RE.exec(stmt);
    const codes = new Set();
    for (const m of stmt.matchAll(WHERE_CODE_RE)) codes.add(m[1]);
    const inM = WHERE_IN_RE.exec(stmt);
    if (inM) for (const m of inM[1].matchAll(CODE_LITERAL_RE)) codes.add(m[1]);
    if (t) for (const c of codes) typeMap.set(c, { type: t[1], file: rel });
    if (/status\s*=\s*'RETIRED'/i.test(stmt)) for (const c of codes) retired.set(c, rel);
  }
}

const menuTs = fs.readFileSync(path.join(ROOT, MENU_TS), 'utf8');
const menuPerms = new Set();
for (const m of menuTs.matchAll(/perm:\s*'([^']+)'/g)) menuPerms.add(m[1]);

const errors = [];

if (typeMap.size < MIN_PARSED_CODES) {
  errors.push(
    `解析到的 ops_permission 定义只有 ${typeMap.size} 条（期望 ≥${MIN_PARSED_CODES}）` +
      '——解析器可能失效，判红以防假绿'
  );
}

const unknown = [];
const badType = [];
const isRetired = [];
for (const code of [...menuPerms].sort()) {
  const hit = typeMap.get(code);
  if (!hit) {
    unknown.push(code);
    continue;
  }
  if (hit.type !== 'C' && hit.type !== 'M')
    badType.push(`${code}（perm_type='${hit.type}'，${hit.file}）`);
  if (retired.has(code)) isRetired.push(`${code}（status='RETIRED'，${retired.get(code)}）`);
}

for (const c of unknown) {
  errors.push(
    `${MENU_TS}: 菜单权限码 ${c} 在 db/migration|db/seed 的 ops_permission 里找不到定义` +
      '——新库不会有这条权限，菜单永远不可达（或权限码拼错了）'
  );
}
for (const c of badType) {
  errors.push(
    `${MENU_TS}: 菜单权限码 ${c} 不是菜单级权限（只认 C/M）。` +
      '路由守卫除 hasPerm 外还要过「菜单是否启用」，/rbac/me/nav 只返回 C/M 的 ACTIVE 码，' +
      '挂 F 类型会让该页对所有人永久「无权访问」（超管也不例外）。' +
      '菜单请用该页的 list 级(C)码，写操作再在页内按 F 码单独控制'
  );
}
for (const c of isRetired) {
  errors.push(`${MENU_TS}: 菜单权限码 ${c} 已被迁移置为 RETIRED —— 该菜单对所有人不可达`);
}

if (errors.length) {
  console.error('[check-admin-menu-perm-type] FAILED:');
  for (const e of errors) console.error(' -', e);
  process.exit(1);
}

console.log(
  `[check-admin-menu-perm-type] OK（菜单权限码 ${menuPerms.size} 个；SQL 定义 ${typeMap.size} 条，来源 ${files.length} 个文件）`
);
