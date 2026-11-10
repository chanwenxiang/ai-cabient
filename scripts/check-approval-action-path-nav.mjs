#!/usr/bin/env node
/**
 * 门禁：深链跳转路径必须能被**菜单登记**解析，否则点进去必被 fail-closed 判「无权限」。
 *
 * 背景（2026-11-10 实测缺陷）：仓储页从单页 `/warehouse` 拆成 8 个子页后，
 * 审批待办仍吐旧入口 `/warehouse?tab=purchase`。前端 `canAccessPath()` 会先砍掉 query
 * 得到 `/warehouse`，再 `findNavByPath()` **精确匹配** `config/menu.ts` —— 旧入口没有登记项
 * ⇒ 返回 undefined ⇒ 默认拒绝 ⇒ 任何账号（含超管）点待办都弹「当前账号无业务页权限」。
 * 症状像权限故障，实际是「跳转目标与菜单登记表脱钩」；同一份代码里
 * 「路由 redirect 兼容旧路径」与「前置权限检查只认精确登记」两套口径并存，只盯一侧必然漏。
 *
 * 四条规则（判据一律从**源码实测**取，不用人工白名单）：
 *  A. 后端 `ApprovalWorkflowService.actionPath()` 产出的每个路径字面量都可解析；
 *  B. 前端 `OpsApprovalInbox.resolvePath()` 产出的每个路径字面量都可解析（与 A 同源的第二份表）；
 *  C. admin 前端凡以**字面量**实参调 `goPath()/canAccessPath()` 的路径都可解析（卡片/深链调用点）；
 *  D. 自检：`findNavByPath()` 若新增了除「精确匹配 / path=== / path.startsWith」之外的解析形态，
 *     本门禁的判据就可能不再等价于运行时 —— 直接判红要求同步升级（防止门禁静默失效）。
 *
 * 可解析 = menu.ts 里 `path:` 登记的项 ∪ findNavByPath 内 `path === 'X'` / `path.startsWith('P')` 特例。
 * 源码扫描前统一 maskComments() 抹注释（等长空格，保行号）：本文件与锚点里都写了旧路径示例。
 *
 *   node scripts/check-approval-action-path-nav.mjs
 */
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const TAG = '[check-approval-action-path-nav]';
const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');

const MENU_FILE = 'clients/admin-vue/src/config/menu.ts';
const FRONT_INBOX = 'clients/admin-vue/src/components/OpsApprovalInbox.vue';
const BACKEND_APPROVAL =
  'services/trade-service/src/main/java/com/aicabinet/trade/service/ApprovalWorkflowService.java';
const ADMIN_SRC = 'clients/admin-vue/src';

function fail(msg) {
  console.error(`${TAG} FAIL: ${msg}`);
  process.exit(1);
}

function read(rel) {
  try {
    return readFileSync(join(ROOT, rel), 'utf8');
  } catch {
    return null;
  }
}

// ── 注释抹除（等长空格，保行号；字符串内容原样保留） ─────────────────────────
function maskComments(src, lang) {
  const out = [...src];
  const n = src.length;
  let i = 0;
  const isStringStart = (c) => c === "'" || c === '"' || (lang === 'js' && c === '`');
  while (i < n) {
    const c = src[i];
    const d = src[i + 1];
    if (c === '/' && d === '/') {
      while (i < n && src[i] !== '\n') {
        out[i] = ' ';
        i++;
      }
      continue;
    }
    if (c === '/' && d === '*') {
      out[i] = ' ';
      out[i + 1] = ' ';
      i += 2;
      while (i < n && !(src[i] === '*' && src[i + 1] === '/')) {
        if (src[i] !== '\n') out[i] = ' ';
        i++;
      }
      if (i < n) {
        out[i] = ' ';
        out[i + 1] = ' ';
        i += 2;
      }
      continue;
    }
    if (isStringStart(c)) {
      const q = c;
      i++;
      while (i < n) {
        if (src[i] === '\\') {
          i += 2;
          continue;
        }
        if (src[i] === q) {
          i++;
          break;
        }
        i++;
      }
      continue;
    }
    i++;
  }
  return out.join('');
}

/** 平衡花括号取函数体（跳过字符串内的括号） */
function functionBody(src, signatureMatch, lang) {
  const bodyStart = src.indexOf('{', signatureMatch.index + signatureMatch[0].length);
  if (bodyStart < 0) return '';
  let depth = 0;
  for (let i = bodyStart; i < src.length; i++) {
    const c = src[i];
    if (c === "'" || c === '"' || (lang === 'js' && c === '`')) {
      const q = c;
      i++;
      while (i < src.length) {
        if (src[i] === '\\') {
          i += 2;
          continue;
        }
        if (src[i] === q) break;
        i++;
      }
      continue;
    }
    if (c === '{') depth++;
    else if (c === '}') {
      depth--;
      if (depth === 0) return src.slice(bodyStart, i + 1);
    }
  }
  return src.slice(bodyStart);
}

/** 收集源码里的字符串字面量（含模板字符串原文） */
function collectStrings(src, lang) {
  const found = [];
  const n = src.length;
  let i = 0;
  while (i < n) {
    const c = src[i];
    if (c === "'" || c === '"' || (lang === 'js' && c === '`')) {
      const q = c;
      let val = '';
      const start = i;
      i++;
      while (i < n) {
        if (src[i] === '\\') {
          val += src[i] + (src[i + 1] ?? '');
          i += 2;
          continue;
        }
        if (src[i] === q) {
          i++;
          break;
        }
        val += src[i];
        i++;
      }
      found.push({ value: val, index: start });
      continue;
    }
    i++;
  }
  return found;
}

/** 字面量 → 路由路径（切掉 query 与模板插值）；不像路径的返回 null。
 *  ⚠️ 刻意**不**归一化尾部斜杠：`'/warehouse/'` 在本仓是「子页前缀」用法
 *  （`path.startsWith('/warehouse/')`），不是导航目标；而真把 `'/warehouse/'` 交给
 *  canAccessPath 时它同样查不到（navPathOf 不做归一化）⇒ 保留原样才能两侧口径一致。 */
function routePathOf(literal) {
  const cut = literal.split(/[?$#\s]/)[0].trim();
  if (cut.length < 2 || !/^\/[A-Za-z0-9\-_/]*$/.test(cut)) return null;
  return cut;
}

// ── 菜单侧：登记表 + findNavByPath 特例（自动对齐运行时实现） ────────────────
const menuRaw = read(MENU_FILE);
if (!menuRaw) fail(`未找到 ${MENU_FILE}`);
const menuSrc = maskComments(menuRaw, 'js');

const registered = new Set([...menuSrc.matchAll(/path:\s*'([^']+)'/g)].map((m) => m[1]));
if (registered.size === 0) fail(`${MENU_FILE} 里没解析到任何 path 登记项，锚点可能已被重写`);

const navFn = /export function findNavByPath\s*\([^)]*\)[^{]*/.exec(menuSrc);
if (!navFn) fail(`${MENU_FILE} 里没找到 findNavByPath()，锚点可能已被重写`);
const navBody = functionBody(menuSrc, navFn, 'js');

const specialExact = new Set([...navBody.matchAll(/path\s*===\s*'([^']+)'/g)].map((m) => m[1]));
const specialPrefix = [...navBody.matchAll(/path\.startsWith\('([^']+)'\)/g)].map((m) => m[1]);

// 规则 D 自检：findNavByPath 里出现的 `path.xxx` 形态必须都是本门禁认识的解析形态
const navPathOps = [
  ...new Set([...navBody.matchAll(/path\.([A-Za-z]+)\s*\(/g)].map((m) => m[1]))
].filter((op) => op !== 'startsWith');
if (navPathOps.length) {
  fail(
    `findNavByPath() 出现本门禁未覆盖的解析形态 path.${navPathOps.join('/path.')}()：` +
      `本门禁的「可解析」判据已与运行时不等价，先升级判据再放行（否则门禁静默假绿）。`
  );
}

function whyUnresolvable(path) {
  if (registered.has(path)) return null;
  if (specialExact.has(path)) return null;
  if (specialPrefix.some((p) => path.startsWith(p) && path !== p)) return null;
  return true;
}

// ── A：后端 actionPath() ────────────────────────────────────────────────────
const backendRaw = read(BACKEND_APPROVAL);
if (!backendRaw) fail(`未找到 ${BACKEND_APPROVAL}`);
const backendSrc = maskComments(backendRaw, 'java');
const actionPathSig = /static String actionPath\s*\(\s*String bizType\s*,\s*String bizId\s*\)/.exec(
  backendSrc
);
if (!actionPathSig)
  fail(`${BACKEND_APPROVAL} 里没找到 actionPath(String, String)，锚点可能已被重写`);
const actionPathBody = functionBody(backendSrc, actionPathSig, 'java');

// ── B：前端 resolvePath() ───────────────────────────────────────────────────
const inboxRaw = read(FRONT_INBOX);
if (!inboxRaw) fail(`未找到 ${FRONT_INBOX}`);
const inboxSrc = maskComments(inboxRaw, 'js');
const resolvePathSig = /function resolvePath\s*\([^)]*\)/.exec(inboxSrc);
if (!resolvePathSig) fail(`${FRONT_INBOX} 里没找到 resolvePath()，锚点可能已被重写`);
const resolvePathBody = functionBody(inboxSrc, resolvePathSig, 'js');

const violations = [];
function checkAnchor(label, body, lang) {
  const paths = new Set(
    collectStrings(body, lang)
      .map((s) => routePathOf(s.value))
      .filter((p) => p && whyUnresolvable(p))
  );
  for (const p of paths)
    violations.push(`${label} 产出 \`${p}\` —— 菜单未登记且 findNavByPath 无特例`);
}

checkAnchor('后端 actionPath()', actionPathBody, 'java');
checkAnchor('前端 resolvePath()', resolvePathBody, 'js');

// ── C：admin 前端 goPath('x') / canAccessPath('x') 字面量调用点 ──────────────
function* walk(dir) {
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    if (statSync(p).isDirectory()) yield* walk(p);
    else if (/\.(ts|vue)$/.test(name)) yield p;
  }
}

const CALL_RE = /\b(?:goPath|canAccessPath)\s*\(\s*(['"])((?:\\.|(?!\1).)*)\1\s*[,)]/g;

// ── E：已拆页模块的**旧入口路径**不得再作为字面量出现（任意形态） ─────────────
// 拆分后旧入口只剩 router 里的 redirect 兼容：手输地址能进，但 canAccessPath 精确匹配查不到
// ⇒ 凡是走 goPath()/canAccessPath() 的入口（快捷卡 path 字段、行操作按钮）都会被静默隐藏或拦下。
// 判据从 router 自动取（不用白名单）：redirect 目标是无斜杠的段 P，且菜单里存在 `/P/xxx` 子页
// ⇒ `/P` 就是旧入口。引用它的字面量一律判红，要求直接写子页路径。
const ROUTER_FILE = 'clients/admin-vue/src/router/index.ts';
const routerSrc = maskComments(read(ROUTER_FILE) || '', 'js');
const legacyEntries = new Set();
for (const m of routerSrc.matchAll(/redirect\s*[:=]/g)) {
  const before = routerSrc.slice(Math.max(0, m.index - 400), m.index);
  const pathM = [...before.matchAll(/path:\s*'([^']*)'/g)].pop();
  if (!pathM) continue;
  const seg = pathM[1].replace(/^\/+|\/+$/g, '');
  if (!seg || seg.includes('/')) continue;
  if ([...registered].some((r) => r.startsWith(`/${seg}/`))) legacyEntries.add(`/${seg}`);
}
if (legacyEntries.size === 0) {
  fail(`${ROUTER_FILE} 里没解析到任何「拆页旧入口 redirect」，锚点可能已被重写（判据会恒真）`);
}

for (const file of walk(join(ROOT, ADMIN_SRC))) {
  const rel = relative(ROOT, file).replace(/\\/g, '/');
  // 路由表自己就是定义 redirect 的地方；单测里的字面量不参与运行时导航
  if (rel === ROUTER_FILE || /\.test\.ts$/.test(rel)) continue;
  const src = maskComments(readFileSync(file, 'utf8'), 'js');
  for (const { value } of collectStrings(src, 'js')) {
    const p = routePathOf(value);
    if (p && legacyEntries.has(p)) {
      violations.push(
        `${rel} 引用了拆页旧入口 \`${p}\`（字面量 ${JSON.stringify(value)}；应直接写子页路径，如 ${[...registered].find((r) => r.startsWith(`${p}/`))}）`
      );
    }
  }
  for (const m of src.matchAll(CALL_RE)) {
    const p = routePathOf(m[2]);
    if (p && whyUnresolvable(p)) {
      violations.push(`${rel} 调用 ${m[0].trim()} —— 菜单未登记且 findNavByPath 无特例`);
    }
  }
}

// ── 判定 ───────────────────────────────────────────────────────────────────
if (violations.length) {
  console.error(
    `${TAG} FAIL: 以下跳转路径无法被菜单登记解析（点进去必被 fail-closed 判「无权限」）：`
  );
  for (const v of violations) console.error(`  - ${v}`);
  console.error(
    '  修复：① 跳转改用拆分后的子页路径（如 /warehouse/procurement）；' +
      '② 或在 config/menu.ts 登记该路径并给 perm；③ 别只改路由 redirect —— canAccessPath 不认 redirect。'
  );
  process.exit(1);
}

console.log(
  `${TAG} OK：actionPath()/resolvePath() 与前端字面量调用点全部可解析` +
    `（菜单登记 ${registered.size} 项 + findNavByPath 特例 ${specialExact.size} 精确 / ${specialPrefix.length} 前缀）`
);
