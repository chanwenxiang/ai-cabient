#!/usr/bin/env node
/**
 * 门禁：<app-underline-tabs> 必须显式声明 layout，且标签项数 ≥5 时必须 scroll。
 *
 * 背景（2026-11-02 商户端样式扫查）：组件默认 `layout="auto"` —— ≤5 项**等分**，
 * 5 项且标签较长时每格被压成「已…」「争…」（订单页实测），文字直接不可读。
 * 等分还是横滑是**页面级决策**，不允许靠默认值碰运气；显式写 layout 才能被 review 看见。
 *
 * 规则：
 *  1) 每个 <app-underline-tabs> 必须显式写 layout，且取值 ∈ auto / equal / scroll。
 *  2) 若 `:items` 的项数可静态解析且 ≥5，layout 必须是 scroll（equal/auto 都会均分压扁）。
 *
 * 项数解析（静态、尽力而为）：同文件数组字面量、`X.map(...)` 且 X 为同文件字面量、
 * `computed(() => [...])`、`const A = B` 且 B 经 import 指向另一文件里的字面量。
 * 解析不出来的（依赖字典/接口的动态数组）只要求显式 layout，并在输出里列为「待人工确认」。
 */
import { readFileSync, readdirSync, statSync, existsSync } from 'node:fs';
import { join, dirname, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const TAG = '[check-mp-tabs-layout]';
const APPS = ['clients/consumer-mp', 'clients/merchant-mp'];
const SCAN_SUBDIR = 'src';
const ALLOWED = new Set(['auto', 'equal', 'scroll']);
const MIN_SCROLL = 5;

function* walk(dir) {
  let entries;
  try {
    entries = readdirSync(dir);
  } catch {
    return;
  }
  for (const name of entries) {
    if (name === 'node_modules' || name === 'dist') continue;
    const p = join(dir, name);
    const st = statSync(p);
    if (st.isDirectory()) yield* walk(p);
    else if (name.endsWith('.vue')) yield p;
  }
}

/* ---------- 极简源码扫描（跳过字符串/模板/注释，按括号深度取子串） ---------- */

function skipQuoted(src, i) {
  const q = src[i];
  i += 1;
  while (i < src.length) {
    if (src[i] === '\\') {
      i += 2;
      continue;
    }
    if (src[i] === q) return i + 1;
    i += 1;
  }
  return i;
}

function skipTemplate(src, i) {
  i += 1;
  while (i < src.length) {
    if (src[i] === '\\') {
      i += 2;
      continue;
    }
    if (src[i] === '`') return i + 1;
    if (src[i] === '$' && src[i + 1] === '{') {
      let depth = 1;
      i += 2;
      while (i < src.length && depth > 0) {
        const c = src[i];
        if (c === '"' || c === "'") {
          i = skipQuoted(src, i);
          continue;
        }
        if (c === '`') {
          i = skipTemplate(src, i);
          continue;
        }
        if (c === '{') depth += 1;
        else if (c === '}') depth -= 1;
        i += 1;
      }
      continue;
    }
    i += 1;
  }
  return i;
}

function skipComment(src, i) {
  if (src[i] === '/' && src[i + 1] === '/') {
    const nl = src.indexOf('\n', i);
    return nl < 0 ? src.length : nl;
  }
  if (src[i] === '/' && src[i + 1] === '*') {
    const end = src.indexOf('*/', i + 2);
    return end < 0 ? src.length : end + 2;
  }
  return i;
}

/** 从 start 起读到表达式结束（深度 0 的 ; / 换行 / 右括号前） */
function readExpression(src, start) {
  let depth = 0;
  let i = start;
  while (i < src.length) {
    const c = src[i];
    if (c === '"' || c === "'") {
      i = skipQuoted(src, i);
      continue;
    }
    if (c === '`') {
      i = skipTemplate(src, i);
      continue;
    }
    if (c === '/' && (src[i + 1] === '/' || src[i + 1] === '*')) {
      i = skipComment(src, i);
      continue;
    }
    if (c === '(' || c === '[' || c === '{') depth += 1;
    else if (c === ')' || c === ']' || c === '}') {
      if (depth === 0) break;
      depth -= 1;
    } else if ((c === ';' || c === '\n') && depth === 0) break;
    i += 1;
  }
  return src.slice(start, i).trim();
}

function collectTopLevel(body) {
  const parts = [];
  let depth = 0;
  let cur = '';
  let i = 0;
  while (i < body.length) {
    const c = body[i];
    if (c === '"' || c === "'") {
      const end = skipQuoted(body, i);
      cur += body.slice(i, end);
      i = end;
      continue;
    }
    if (c === '`') {
      const end = skipTemplate(body, i);
      cur += body.slice(i, end);
      i = end;
      continue;
    }
    if (c === '/' && (body[i + 1] === '/' || body[i + 1] === '*')) {
      const end = skipComment(body, i);
      i = end;
      continue;
    }
    if (c === '(' || c === '[' || c === '{') depth += 1;
    else if (c === ')' || c === ']' || c === '}') depth -= 1;
    if (c === ',' && depth === 0) {
      parts.push(cur);
      cur = '';
      i += 1;
      continue;
    }
    cur += c;
    i += 1;
  }
  if (cur.trim()) parts.push(cur);
  return parts.map((p) => p.trim()).filter(Boolean);
}

function arrayBody(expr) {
  if (!expr.startsWith('[')) return null;
  let depth = 0;
  for (let i = 0; i < expr.length; i += 1) {
    const c = expr[i];
    if (c === '"' || c === "'") {
      i = skipQuoted(expr, i) - 1;
      continue;
    }
    if (c === '`') {
      i = skipTemplate(expr, i) - 1;
      continue;
    }
    if (c === '[') depth += 1;
    else if (c === ']') {
      depth -= 1;
      if (depth === 0) return expr.slice(1, i);
    }
  }
  return null;
}

function literalCount(expr) {
  const body = arrayBody(expr);
  if (body === null) return null;
  const parts = collectTopLevel(body);
  if (parts.some((p) => p.startsWith('...') || p.includes('...'))) return null;
  return parts.length;
}

/* ---------- 解析 :items 指向的数组项数 ---------- */

const constCache = new Map();

function findConstExpr(src, name, file = '') {
  const key = `${file}::${name}`;
  if (constCache.has(key)) return constCache.get(key);
  const head = new RegExp(`(?:const|let|var)\\s+${name}\\b`).exec(src);
  let expr = null;
  if (head) {
    // 跳过类型注解（`const x: { a: string; b: string }[] =` 里含分号）
    let i = head.index + head[0].length;
    let typeDepth = 0;
    while (i < src.length) {
      const c = src[i];
      if (c === '"' || c === "'") {
        i = skipQuoted(src, i);
        continue;
      }
      if (c === '`') {
        i = skipTemplate(src, i);
        continue;
      }
      if (c === '/' && (src[i + 1] === '/' || src[i + 1] === '*')) {
        i = skipComment(src, i);
        continue;
      }
      if (c === '(' || c === '[' || c === '{' || c === '<') typeDepth += 1;
      else if (c === ')' || c === ']' || c === '}' || c === '>')
        typeDepth = Math.max(0, typeDepth - 1);
      else if (c === '=' && src[i + 1] !== '=' && typeDepth === 0) break;
      else if ((c === ';' || c === '\n') && typeDepth === 0) break;
      i += 1;
    }
    if (src[i] === '=') expr = readExpression(src, i + 1);
  }
  constCache.set(key, expr);
  return expr;
}

function stripComputed(expr) {
  const m = /^computed\s*\(/.exec(expr);
  if (!m) return expr;
  let depth = 0;
  for (let i = m[0].length - 1; i < expr.length; i += 1) {
    const c = expr[i];
    if (c === '"' || c === "'") {
      i = skipQuoted(expr, i) - 1;
      continue;
    }
    if (c === '`') {
      i = skipTemplate(expr, i) - 1;
      continue;
    }
    if (c === '(' || c === '[' || c === '{') depth += 1;
    else if (c === ')' || c === ']' || c === '}') {
      depth -= 1;
      if (depth === 0) return expr.slice(m[0].length, i).trim();
    }
  }
  return expr;
}

function resolveImport(file, appRoot, name) {
  let src;
  try {
    src = readFileSync(file, 'utf8');
  } catch {
    return null;
  }
  const re = /import\s+([\s\S]*?)\s+from\s+['"]([^'"]+)['"]/g;
  let m;
  while ((m = re.exec(src))) {
    const clause = m[1];
    const hasName =
      new RegExp(`[{,\\s]${name}\\s*(?:,|}|$)`).test(clause) ||
      new RegExp(`^\\s*${name}\\s*$`).test(clause);
    if (!hasName) continue;
    const spec = m[2];
    let base;
    if (spec.startsWith('@/')) base = join(appRoot, spec.slice(2));
    else if (spec.startsWith('.')) base = resolve(dirname(file), spec);
    else continue;
    for (const cand of [base, `${base}.ts`, `${base}.js`, join(base, 'index.ts')]) {
      if (existsSync(cand) && statSync(cand).isFile()) return cand;
    }
  }
  return null;
}

function resolveCount(expr, src, file, appRoot, depth = 0) {
  if (!expr || depth > 3) return null;
  const direct = literalCount(expr);
  if (direct !== null) return direct;

  let cur = stripComputed(expr);
  const arrow = /^\(?\s*\)?\s*=>\s*([\s\S]*)$/.exec(cur);
  if (arrow) cur = arrow[1].trim();

  // computed(() => { const tabs = [...]; return tabs; })
  if (cur.startsWith('{')) {
    const ret = /return\s+([A-Za-z_$][\w$]*)\s*;?/.exec(cur);
    if (ret) {
      const local = findConstExpr(cur.replace(/^\s*\{/, ''), ret[1], file);
      const n = resolveCount(local, src, file, appRoot, depth + 1);
      if (n !== null) return n;
    }
    return null;
  }
  if (cur !== expr) return resolveCount(cur, src, file, appRoot, depth + 1);

  const mapped = /^([A-Za-z_$][\w$]*)(?:\.value)?\s*\.\s*map\s*\(/.exec(cur);
  if (mapped) {
    const base = findConstExpr(src, mapped[1], file);
    return resolveCount(base, src, file, appRoot, depth + 1);
  }

  const ident = /^([A-Za-z_$][\w$]*)$/.exec(expr);
  if (ident) {
    const local = findConstExpr(src, ident[1], file);
    if (local !== null) return resolveCount(local, src, file, appRoot, depth + 1);
    const imported = resolveImport(file, appRoot, ident[1]);
    if (imported) {
      const importedSrc = readFileSync(imported, 'utf8');
      const importedExpr = findConstExpr(importedSrc, ident[1], imported);
      return resolveCount(importedExpr, importedSrc, imported, appRoot, depth + 1);
    }
  }
  return null;
}

/* ---------- 主流程 ---------- */

const problems = [];
const notes = [];
for (const app of APPS) {
  const appRoot = join(ROOT, app, SCAN_SUBDIR);
  for (const file of walk(appRoot)) {
    const src = readFileSync(file, 'utf8');
    const tagRe = /<app-underline-tabs\b([\s\S]*?)(\/>|>)/g;
    for (const m of src.matchAll(tagRe)) {
      const attrs = m[1] || '';
      const where = relative(ROOT, file);
      const layout = /\blayout\s*=\s*["']([^"']+)["']/.exec(attrs);
      if (!layout) {
        problems.push(`${where}（缺 layout）`);
        continue;
      }
      if (!ALLOWED.has(layout[1])) {
        problems.push(`${where}（layout="${layout[1]}" 非法）`);
        continue;
      }
      const items = /:items\s*=\s*["']([^"']+)["']/.exec(attrs);
      const count = items ? resolveCount(items[1].trim(), src, file, appRoot) : null;
      if (count === null) {
        notes.push(`${where}（项数动态/未能静态解析，layout="${layout[1]}" 需人工确认）`);
      } else if (count >= MIN_SCROLL && layout[1] !== 'scroll') {
        problems.push(
          `${where}（${count} 项却 layout="${layout[1]}"，≥${MIN_SCROLL} 项必须 scroll）`
        );
      }
    }
  }
}

if (notes.length) {
  console.log(`${TAG} 说明：以下标签项数动态，无法静态判定（仅提示，不判红）：`);
  for (const n of notes) console.log(`  - ${n}`);
}

if (problems.length) {
  console.error(
    `${TAG} FAIL: <app-underline-tabs> layout 声明不合规：\n` +
      problems.map((p) => `  - ${p}`).join('\n') +
      `\n说明：${MIN_SCROLL} 项及以上等分会被压成省略号，必须写 layout="scroll"；` +
      '≤4 项可 auto/equal，但必须显式声明。'
  );
  process.exit(1);
}
console.log(`${TAG} OK：layout 均显式合法，且 ≥${MIN_SCROLL} 项的标签均为 scroll`);
