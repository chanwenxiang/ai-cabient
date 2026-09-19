#!/usr/bin/env node
/**
 * 门禁：admin 菜单 perm 码必须能在后端权限体系找到出处。
 *
 * 后端出处（任一即可）：
 *  - @RequiresPermissions("x") / @RequiresPermissions(value={"a","b"}) 注解（含多行/OR）
 *  - hasAnyPermission / hasPermission / requirePermission 编程式检查调用点附近的字面量
 *
 * 豁免（ALLOWLIST）：有据可查的「菜单可见性 / API 执行」两级分离设计，
 * 不得新增同类豁免；新增前先补迁移注释说明设计意图。
 */
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const MENU_FILE = 'clients/admin-vue/src/config/menu.ts';
const SERVICES_DIR = 'services';

/** 菜单可见性专用权限码豁免清单（键=perm 码，值=设计依据） */
const ALLOWLIST = new Map([
  // V138__align_menus_with_sidebar.sql：识别演示为 C 级菜单，侧栏可见性与按钮(API) ops:sku:demo 刻意分离
  ['ops:recognition-demo:view', 'V138 刻意的菜单/按钮两级权限分离设计（按钮用 ops:sku:demo）'],
]);

const PERM_LITERAL = /[a-z0-9-]+(?::[a-z0-9-]+)+/g;

function readIfExists(p) {
  try { return readFileSync(join(ROOT, p), 'utf8'); } catch { return null; }
}

// ── 菜单侧 ────────────────────────────────────────────────────────────────
const menuSrc = readIfExists(MENU_FILE);
if (!menuSrc) {
  console.error(`[check-menu-permissions] 未找到 ${MENU_FILE}`);
  process.exit(1);
}
const menuPerms = [...menuSrc.matchAll(/perm:\s*'([^']+)'/g)].map((m) => m[1]);

// ── 后端侧 ────────────────────────────────────────────────────────────────
function* walkJava(dir) {
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    if (statSync(p).isDirectory()) yield* walkJava(p);
    else if (name.endsWith('.java')) yield p;
  }
}

const backendPerms = new Set();
const servicesDir = join(ROOT, SERVICES_DIR);
for (const file of walkJava(servicesDir)) {
  const src = readFileSync(file, 'utf8');
  // 1) @RequiresPermissions(...)：取注解括号块（兼容 value=、多行、Logical.OR）
  for (const m of src.matchAll(/@RequiresPermissions\s*\(/g)) {
    let depth = 1, i = m.index + m[0].length;
    while (i < src.length && depth > 0) {
      if (src[i] === '(') depth++;
      else if (src[i] === ')') depth--;
      i++;
    }
    const block = src.slice(m.index + m[0].length, i - 1);
    for (const q of block.matchAll(/"([^"]+)"/g)) {
      for (const p of q[1].match(PERM_LITERAL) ?? []) backendPerms.add(p);
    }
  }
  // 2) 编程式检查调用点：只抽调用点附近窗口内的字面量，避免误吞无关字符串
  for (const m of src.matchAll(/(hasAnyPermission|hasPermission|requirePermission)\s*\(/g)) {
    const win = src.slice(m.index, m.index + 200);
    for (const p of win.match(PERM_LITERAL) ?? []) backendPerms.add(p);
  }
}

// ── 比对 ─────────────────────────────────────────────────────────────────
const violations = [];
for (const perm of menuPerms) {
  if (backendPerms.has(perm)) continue;
  if (ALLOWLIST.has(perm)) continue;
  violations.push(perm);
}

if (violations.length > 0) {
  console.error('[check-menu-permissions] 以下菜单 perm 码在后端无出处（@RequiresPermissions / 编程式检查均未命中）：');
  for (const p of violations) console.error(`  - ${p}`);
  console.error('  修复：改用后端真实权限码；如属菜单/按钮两级分离设计，先补迁移注释再加入 ALLOWLIST。');
  process.exit(1);
}
console.log(`[check-menu-permissions] OK：${menuPerms.length} 个菜单权限码全部可溯源（豁免 ${ALLOWLIST.size} 项）`);
