#!/usr/bin/env node
/**
 * 门禁：admin 的按钮/行操作权限判定，不得把「查看码」当成「写码」用。
 *
 * 背景（2026-11-10 四端审计 P1-3）：`FeedbackView.vue` 的删除动作写成
 * `perm: ['ops:feedback', 'ops:feedback:reply']`、`canDelete` 写成
 * `hasPerm('ops:feedback') || hasPerm('ops:feedback:reply')`。权限判定是 **OR** 语义，
 * 于是只持有查看码的人也能看到「删除」，点下去被后端
 * `@RequiresPermissions("ops:feedback:reply")` 判 403 —— 幽灵按钮。
 * 该形态 MA-111 已修过一次，属复发类，故落判据。
 *
 * 判据（结构性、不看业务词）：
 *   同一处权限判定里若同时出现码 A 与 A 的后缀码（`A:xxx`），判红。
 *   因为 `A` 一定是该实体的查看/列表码，`A:xxx` 是写码；OR 之下查看码放行 ⇒ 必然 403。
 *
 * 覆盖形态：
 *   - `perm: ['a', 'a:b']`（CrudRowAction / 组件 props）
 *   - `v-hasPermi="['a', 'a:b']"`（模板指令）
 *   - `exportPerm/importPerm: [...]`
 *   - `hasPerm('a') || hasPerm('a:b')`
 *
 * 反例（不算违规）：
 *   - `perm: ['ops:a:edit', 'ops:a:delete']` —— 两个都是写码，无前缀关系。
 *   - 后端端点本身就是 OR 双门（`@RequiresPermissions(value={"ops:rbac:assign",
 *     "ops:rbac:assign:device"}, logical=OR)`），前端照抄同一组合属于**正确对齐**：
 *     此时任一码都能真正调用成功，不存在 403。故本门禁从后端注解里自动提取
 *     「显式 OR 的同族码组合」作为合法白名单——不靠人工维护。
 *
 * 用法：node scripts/check-admin-write-perm-codes.mjs
 */
import { readFileSync, readdirSync, existsSync } from 'node:fs';
import { join, dirname, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

const TAG = '[check-admin-write-perm-codes]';
const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const SRC = process.env.ADMIN_WRITE_PERM_ROOT
  ? process.env.ADMIN_WRITE_PERM_ROOT
  : join(ROOT, 'clients', 'admin-vue', 'src');
const SERVICES = process.env.ADMIN_WRITE_PERM_BACKEND
  ? process.env.ADMIN_WRITE_PERM_BACKEND
  : join(ROOT, 'services');

const ARRAY_PATTERNS = [
  /\bperm\s*:\s*\[([^\]]*)\]/g,
  /v-hasPermi\s*=\s*["']\[([^\]]*)\]["']/g,
  /\bexportPerm\s*:\s*\[([^\]]*)\]/g,
  /\bimportPerm\s*:\s*\[([^\]]*)\]/g
];
// `hasPerm('a') || hasPerm('b')`，允许右侧带 `auth.` 之类标识符前缀
const HASPERM_OR_RE =
  /hasPerm\(\s*'([^']+)'\s*\)\s*\|\|\s*(?:[A-Za-z_$][\w$]*\.)?hasPerm\(\s*'([^']+)'\s*\)/g;
const QUOTED_RE = /'([^'\n]+)'|"([^"\n]+)"/g;

function walk(dir, out = []) {
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const full = join(dir, entry.name);
    if (entry.isDirectory()) {
      if (entry.name === 'node_modules' || entry.name === 'dist') continue;
      walk(full, out);
      continue;
    }
    if (!/\.(vue|ts)$/.test(entry.name)) continue;
    out.push(full);
  }
  return out;
}

function lineOf(text, index) {
  let line = 1;
  for (let i = 0; i < index && i < text.length; i += 1) {
    if (text[i] === '\n') line += 1;
  }
  return line;
}

/**
 * 把注释抹成空格（长度与换行原样保留，行号不受影响）。
 * 不这么做的话「// 历史写法：perm: ['a','a:b']」这类说明文字会被当成真代码判红
 * （负向验证的注释用例踩到过）。
 */
function maskComments(text) {
  const out = text.split('');
  let state = 'code';
  let i = 0;
  const n = text.length;
  while (i < n) {
    const c = text[i];
    const d = text[i + 1];
    if (state === 'code') {
      if (c === '<' && d === '!') {
        // Vue 模板注释 <!-- -->
        const end = text.indexOf('-->', i + 4);
        if (end !== -1) {
          for (let k = i; k < end + 3; k += 1) if (out[k] !== '\n') out[k] = ' ';
          i = end + 3;
          continue;
        }
      }
      if (c === '/' && d === '/') {
        state = 'line';
        out[i] = ' ';
        out[i + 1] = ' ';
        i += 2;
        continue;
      }
      if (c === '/' && d === '*') {
        state = 'block';
        out[i] = ' ';
        out[i + 1] = ' ';
        i += 2;
        continue;
      }
      if (c === "'") {
        state = 'sq';
        i += 1;
        continue;
      }
      if (c === '"') {
        state = 'dq';
        i += 1;
        continue;
      }
      if (c === '`') {
        state = 'tpl';
        i += 1;
        continue;
      }
      i += 1;
      continue;
    }
    if (state === 'line') {
      if (c === '\n') {
        state = 'code';
        i += 1;
        continue;
      }
      out[i] = ' ';
      i += 1;
      continue;
    }
    if (state === 'block') {
      if (c === '*' && d === '/') {
        out[i] = ' ';
        out[i + 1] = ' ';
        state = 'code';
        i += 2;
        continue;
      }
      if (c !== '\n') out[i] = ' ';
      i += 1;
      continue;
    }
    // 字符串内部：原样保留，处理转义
    if (c === '\\') {
      i += 2;
      continue;
    }
    if (
      (state === 'sq' && c === "'") ||
      (state === 'dq' && c === '"') ||
      (state === 'tpl' && c === '`')
    ) {
      state = 'code';
    }
    i += 1;
  }
  return out.join('');
}

/**
 * 解出数组字面量里的权限码（忽略注释与空串）。
 *
 * 注意这里接受**两段码**（如 `ops:feedback`）——它就是典型的查看/列表码，
 * 而 P1-3 的违规形态正是「两段查看码 + 三段写码」。若只收三段码，
 * `['ops:feedback', 'ops:feedback:reply']` 会退化成单码而漏判（负向验证踩到过）。
 * 两段码只在「与某三段码构成前缀关系」时才参与判定，所以事件名（update:latitude
 * 这类不含 realm 前缀的串）不会造成误报。
 */
function codesOf(inner) {
  const codes = [];
  for (const m of inner.matchAll(QUOTED_RE)) {
    const code = (m[1] ?? m[2] ?? '').trim();
    if (/^[a-z][a-z0-9-]*(?::[a-z0-9-]+){1,}$/.test(code)) codes.push(code);
  }
  return codes;
}

/** 返回第一对「A 与 A:xxx 并存」的组合，没有则 null。 */
function findViewCodeLeak(codes) {
  for (const a of codes) {
    for (const b of codes) {
      if (a !== b && b.startsWith(`${a}:`)) return [a, b];
    }
  }
  return null;
}

/**
 * 后端「显式 OR」注解里的同族码组合。
 * 只有 `logical = RequiresPermissions.Logical.OR` 的注解才算数——Spring 默认是 AND，
 * 多码 AND 时前端拿 OR 去判确实会放宽，不能白名单。
 */
function collectBackendOrPairs() {
  const pairs = new Set();
  const ANNOTATION_RE = /@RequiresPermissions\s*\(([\s\S]{0,400}?)\)\s*(?:\n|\r)/g;
  const CODE_RE = /"([a-z][a-z0-9-]*(?::[a-z0-9-]+){2,})"/g;

  function walkJava(dir, out = []) {
    if (!existsSync(dir)) return out;
    for (const entry of readdirSync(dir, { withFileTypes: true })) {
      const full = join(dir, entry.name);
      if (entry.isDirectory()) {
        walkJava(full, out);
        continue;
      }
      if (entry.name.endsWith('.java')) out.push(full);
    }
    return out;
  }

  for (const file of walkJava(SERVICES)) {
    const text = readFileSync(file, 'utf8');
    for (const m of text.matchAll(ANNOTATION_RE)) {
      const body = m[1] ?? '';
      if (!/Logical\.OR/.test(body)) continue;
      const codes = [...body.matchAll(CODE_RE)].map((x) => x[1]);
      for (const a of codes) {
        for (const b of codes) {
          if (a !== b && b.startsWith(`${a}:`)) pairs.add(`${a}|${b}`);
        }
      }
    }
  }
  return pairs;
}

const problems = [];
const backendOrPairs = collectBackendOrPairs();
const files = walk(SRC)
  .map((f) => ({ full: f, rel: relative(ROOT, f).replace(/\\/g, '/') }))
  .sort((x, y) => x.rel.localeCompare(y.rel));

/** 该前缀对是否为后端显式 OR 的同族组合（合法）。 */
function isBackendOrPair(a, b) {
  return backendOrPairs.has(`${a}|${b}`);
}

let expressionCount = 0;
for (const { full, rel } of files) {
  // 注释先抹掉：注释里的历史写法不是真代码
  const text = maskComments(readFileSync(full, 'utf8'));
  for (const re of ARRAY_PATTERNS) {
    re.lastIndex = 0;
    for (const m of text.matchAll(re)) {
      const codes = codesOf(m[1] ?? '');
      if (codes.length < 2) continue;
      expressionCount += 1;
      const leak = findViewCodeLeak(codes);
      if (leak && !isBackendOrPair(leak[0], leak[1])) {
        problems.push({
          rel,
          line: lineOf(text, m.index ?? 0),
          detail: `[${codes.join(', ')}] —— 查看码 ${leak[0]} 会把 ${leak[1]} 的动作一并放行`
        });
      }
    }
  }
  HASPERM_OR_RE.lastIndex = 0;
  for (const m of text.matchAll(HASPERM_OR_RE)) {
    const [a, b] = [m[1], m[2]];
    expressionCount += 1;
    const leak = findViewCodeLeak([a, b]);
    if (leak && !isBackendOrPair(leak[0], leak[1])) {
      problems.push({
        rel,
        line: lineOf(text, m.index ?? 0),
        detail: `hasPerm('${a}') || hasPerm('${b}') —— 查看码 ${leak[0]} 放行了 ${leak[1]} 的动作`
      });
    }
  }
}

// 解析退化保护：扫描面必须非空，否则判据改坏时会静默假绿
// （负向验证的沙箱树很小，用 env 下调下限；真实树恒为默认 20）
const MIN_FILES = Number(process.env.ADMIN_WRITE_PERM_MIN_FILES || 20);
if (files.length < MIN_FILES) {
  console.error(`${TAG} FAIL: 只扫到 ${files.length} 个文件，扫描根疑似错误（${SRC}）`);
  process.exit(1);
}
if (backendOrPairs.size === 0) {
  console.error(`${TAG} FAIL: 后端 OR 注解一个都没解析到，注解正则疑似失效（${SERVICES}）`);
  process.exit(1);
}

if (problems.length) {
  console.error(`${TAG} FAIL: ${problems.length} 处把查看码用成了写动作门闩`);
  for (const p of problems) {
    console.error(`  ${p.rel}:${p.line}  ${p.detail}`);
  }
  console.error(
    '\n修法：写动作只写写码（如 ops:feedback:reply），不要把查看码放进 OR 数组。\n' +
      '依据：前端权限判定是 OR，查看码持有人点下去必被后端 @RequiresPermissions 拒。'
  );
  process.exit(1);
}

console.log(`${TAG} OK: ${files.length} 个文件、${expressionCount} 处权限判定，无「查看码当写码」`);
