#!/usr/bin/env node
/**
 * 门禁：三端前端引用的权限码必须真实存在于后端（幽灵码零容忍）。
 *
 * 背景（2026-10-14 双小程序审计）：merchant-mp alerts.vue 用了后端从不存在的
 * `merchant:disputes:view` ⇒ 争议告警入口对所有商户恒隐藏。既有门禁
 * check:menu-permissions / check:nav-perms 只覆盖**集中配置文件**
 * （admin menu.ts / merchant-nav.ts），页面与按钮里散落的
 * hasPerm('merchant:x:y') 不在扫描面——幽灵码恰好漏网。
 *
 * 本门禁扫「散落引用」方向：
 *   - merchant-mp / admin-vue：提取源码内全部权限码字面量（≥1 个冒号的引号串），
 *     必须 ⊆ 后端全集（services 各模块 main/java 注解与编程式检查 + Flyway 迁移种子）。
 *   - consumer-mp：消费者自助端**按设计无 RBAC 门控**，出现任何权限码即 FAIL。
 *
 * 豁免（ALLOWLIST）：与 check-menu-permissions 同规——有据可查才允许，不得新增同类豁免。
 *
 * 用法：node scripts/check-frontend-perm-codes.mjs
 */
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const TAG = '[check-frontend-perm-codes]';

/** 豁免清单（键=权限码，值=设计依据）。新增前先补注释说明设计意图。 */
const ALLOWLIST = new Map([
  // V138 刻意的菜单/按钮两级权限分离设计（按钮用 ops:sku:demo，菜单可见性码不在后端）
  [
    'ops:recognition-demo:view',
    'V138 刻意的菜单/按钮两级权限分离设计（同 check-menu-permissions 豁免）'
  ]
]);

/**
 * 权限码形态：至少 2 个冒号（realm:group:action，段内允许连字符）。
 * 两段串多为 Vue emit 事件名（update:latitude）或广播名（aicabinet:xxx），不是权限码；
 * 两段式 realm 根码（'merchant'/'ops'）属菜单配置面，由 check:menu-permissions / check:nav-perms 覆盖。
 */
const CODE_RE = /^[a-z][a-z0-9-]*(?::[a-z0-9-]+){2,}$/;
const QUOTED_RE = /'([^'\n]+)'|"([^"\n]+)"/g;

function fail(msg) {
  console.error(`${TAG} FAIL: ${msg}`);
  process.exit(1);
}

function* walk(dir, exts) {
  let entries;
  try {
    entries = readdirSync(dir);
  } catch {
    return;
  }
  for (const name of entries) {
    if (name === 'node_modules' || name === 'dist' || name === '.workbuddy') continue;
    const p = join(dir, name);
    const st = statSync(p);
    if (st.isDirectory()) yield* walk(p, exts);
    else if (exts.some((e) => name.endsWith(e))) yield p;
  }
}

function extractCodes(src) {
  const found = [];
  for (const m of src.matchAll(QUOTED_RE)) {
    const literal = m[1] || m[2];
    // 只收纯码形态（避免把 URL / 文案 / import 路径误当权限码）
    if (literal && CODE_RE.test(literal) && !literal.includes('/')) found.push(literal);
  }
  return found;
}

function collectFrontend(rel, label) {
  const dir = join(ROOT, rel);
  const hits = new Map(); // code -> [file]
  for (const file of walk(dir, ['.ts', '.vue', '.js', '.mjs'])) {
    if (file.endsWith('.test.ts') || file.endsWith('.test.mjs')) continue;
    const src = readFileSync(file, 'utf8');
    for (const code of extractCodes(src)) {
      const relFile = file.slice(ROOT.length + 1).replace(/\\/g, '/');
      if (!hits.has(code)) hits.set(code, []);
      hits.get(code).push(relFile);
    }
  }
  console.log(`${TAG} ${label}: ${hits.size} 个权限码引用`);
  return hits;
}

// ── 后端全集 ──────────────────────────────────────────────────────────────
const backend = new Set();
for (const file of walk(join(ROOT, 'services'), ['.java', '.sql'])) {
  const src = readFileSync(file, 'utf8');
  for (const code of extractCodes(src)) backend.add(code);
}
// shared-rbac 可能内置 realm/分组常量，也并入全集
for (const file of walk(join(ROOT, 'packages', 'shared-rbac', 'src'), ['.ts'])) {
  for (const code of extractCodes(readFileSync(file, 'utf8'))) backend.add(code);
}
console.log(`${TAG} 后端全集（java+sql+shared-rbac）: ${backend.size} 个权限码`);

// ── 各端校验 ──────────────────────────────────────────────────────────────
const problems = [];

function checkSubset(hits, label) {
  for (const [code, files] of hits) {
    if (backend.has(code) || ALLOWLIST.has(code)) continue;
    problems.push(`${label} 幽灵权限码 "${code}"（后端无出处）：${files.slice(0, 3).join(', ')}`);
  }
}

checkSubset(collectFrontend('clients/merchant-mp/src', 'merchant-mp'), 'merchant-mp');
checkSubset(collectFrontend('clients/admin-vue/src', 'admin-vue'), 'admin-vue');

// consumer-mp：按设计无 RBAC 门控，出现任何权限码即违规
const consumerHits = collectFrontend('clients/consumer-mp/src', 'consumer-mp');
for (const [code, files] of consumerHits) {
  problems.push(
    `consumer-mp 不应出现权限码 "${code}"（消费者自助端按登录态+本人数据隔离，无 RBAC 门控）：${files.slice(0, 3).join(', ')}`
  );
}

if (problems.length) {
  console.error(`\n${TAG} 共 ${problems.length} 处违规：`);
  for (const p of problems) console.error(`  - ${p}`);
  console.error(
    `\n${TAG} 修法：改用后端真实存在的权限码（查 services 各模块 java 的 @RequiresPermissions 或迁移种子）；` +
      `确属「菜单可见性/API 执行两级分离」设计的，先在本脚本 ALLOWLIST 补设计依据注释。`
  );
  process.exit(1);
}

// ── merchant 菜单 URL 必须存在于 pages.json（防死链菜单） ─────────────────
try {
  const navSrc = readFileSync(join(ROOT, 'clients/merchant-mp/src/config/merchant-nav.ts'), 'utf8');
  const pagesJson = JSON.parse(
    readFileSync(join(ROOT, 'clients/merchant-mp/src/pages.json'), 'utf8')
  );
  const knownPaths = new Set();
  for (const p of pagesJson.pages || []) knownPaths.add(`/${p.path}`);
  for (const sub of pagesJson.subPackages || []) {
    for (const p of sub.pages || []) knownPaths.add(`/${sub.root}/${p.path}`);
  }
  const navUrls = [...navSrc.matchAll(/url:\s*'([^']+)'/g)].map((m) => m[1].split('?')[0]);
  const deadUrls = navUrls.filter((u) => !knownPaths.has(u));
  if (deadUrls.length) {
    console.error(`\n${TAG} merchant-nav 菜单死链（pages.json 无此页面）：${deadUrls.join(', ')}`);
    process.exit(1);
  }
  console.log(`${TAG} OK：merchant 菜单 ${navUrls.length} 个 URL 全部存在于 pages.json`);
} catch (e) {
  fail(`merchant 菜单 URL 校验失败：${e.message}`);
}

console.log(`${TAG} OK：三端权限码全部有后端出处（豁免 ${ALLOWLIST.size} 条）`);
