#!/usr/bin/env node
/**
 * 门禁：推送前预检必须**真的执行**两端小程序 type-check，不得退化成「打印提示」。
 *
 * 背景（真实事故，2026-09-26）：CI 的 `mini-programs` job 会跑
 * `Consumer MP type-check` / `Merchant MP type-check` 两步；而
 * `scripts/pre-push-ci-preflight.mjs` 当时只在 `--full` 下 `console.log`
 * 一句「请本机另跑 …」——**并不执行**。结果 2026-09-25 23:41 → 09-26 11:22
 * 连续 20 个 run 挂在 Consumer type-check 上，只能靠每轮约 6 分钟的 CI 反馈才发现。
 *
 * 教训：**预检里「写了」不等于「跑了」**；只打印提示的检查点等于没有检查点。
 *
 * 判据一律判**结构**、不判文案句子（合理改写不该红）：
 *   1. `clients/` 下每个 `*-mp` 端，都必须在预检脚本里有 `runMpTypeCheck('<端>')` 调用；
 *   2. `runMpTypeCheck` 的函数体必须真的执行子进程（含 `run(` 调用 + vue-tsc bin），
 *      只剩 `console.log` 一律判红；
 *   3. 这些调用不得落在 `if (full) { … }` 块内 —— 默认档必须跑（挪进 --full 等于没跑）。
 *
 * 注释一律先剥离：写在注释里的调用语句不算数（否则注释能骗过门禁）。
 *
 *   node scripts/check-preflight-wiring.mjs
 */
import { existsSync, readFileSync, readdirSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const TAG = '[check-preflight-wiring]';
const TARGET = 'scripts/pre-push-ci-preflight.mjs';

function fail(msg) {
  console.error(`${TAG} FAIL: ${msg}`);
  process.exit(1);
}

const problems = [];

/**
 * 简易 JS 注释剥离：跟踪单/双引号与模板字符串，再删块注释与行注释。
 * 门禁读源码文本时若不剥离注释，注释里的调用语句会让它恒绿（假绿）。
 */
function stripComments(src) {
  let out = '';
  let i = 0;
  let quote = null;
  while (i < src.length) {
    const c = src[i];
    const next = src[i + 1];
    if (quote) {
      out += c;
      if (c === '\\') {
        out += next ?? '';
        i += 2;
        continue;
      }
      if (c === quote) quote = null;
      i += 1;
      continue;
    }
    if (c === '"' || c === "'" || c === '`') {
      quote = c;
      out += c;
      i += 1;
      continue;
    }
    if (c === '/' && next === '*') {
      const end = src.indexOf('*/', i + 2);
      i = end === -1 ? src.length : end + 2;
      out += ' ';
      continue;
    }
    if (c === '/' && next === '/') {
      const end = src.indexOf('\n', i);
      i = end === -1 ? src.length : end;
      out += ' ';
      continue;
    }
    out += c;
    i += 1;
  }
  return out;
}

/** 从 `needle` 之后第一个 `{` 起做花括号配对，返回 [start, end)；找不到返回 null */
function braceRange(src, needle) {
  const at = src.indexOf(needle);
  if (at === -1) return null;
  const open = src.indexOf('{', at);
  if (open === -1) return null;
  let depth = 0;
  for (let i = open; i < src.length; i += 1) {
    const c = src[i];
    // 括号在字符串里的情况在本脚本的判据范围内可忽略（判据只读函数名与调用名）
    if (c === '{') depth += 1;
    else if (c === '}') {
      depth -= 1;
      if (depth === 0) return [open, i + 1];
    }
  }
  return null;
}

const targetPath = join(ROOT, TARGET);
if (!existsSync(targetPath)) fail(`找不到 ${TARGET}（预检脚本被改名/删除，门禁已失效）`);
const src = stripComments(readFileSync(targetPath, 'utf8'));

// ── 判据 1：每个 *-mp 端都要有 runMpTypeCheck('<端>') 调用 ──────────────────
const clientsDir = join(ROOT, 'clients');
const mpDirs = existsSync(clientsDir)
  ? readdirSync(clientsDir, { withFileTypes: true })
      .filter((d) => d.isDirectory() && /-mp$/.test(d.name))
      .map((d) => d.name)
      .sort()
  : [];
if (mpDirs.length === 0) fail('clients/ 下没有 *-mp 目录，锚点可能已被重写');

const calls = [...src.matchAll(/runMpTypeCheck\(\s*['"]([^'"]+)['"]\s*\)/g)].map((m) => ({
  name: m[1],
  index: m.index ?? 0
}));
for (const mp of mpDirs) {
  if (!calls.some((c) => c.name === mp)) {
    problems.push(
      `${TARGET} 缺少 \`runMpTypeCheck('${mp}')\` 调用 —— CI 的 mini-programs job 会跑该端 ` +
        `type-check，预检不跑就只能等 CI 反馈`
    );
  }
}

// ── 判据 2：runMpTypeCheck 函数体必须真执行（run( + vue-tsc），不是只打印 ──
const body = braceRange(src, 'function runMpTypeCheck');
if (!body) {
  problems.push(`${TARGET} 里找不到 \`function runMpTypeCheck\`，预检不再执行任何端 type-check`);
} else {
  const text = src.slice(body[0], body[1]);
  if (!/\brun\(/.test(text)) {
    problems.push(
      'runMpTypeCheck 函数体里没有 run( 调用 —— 退化成只打印提示（2026-09-26 20 连 CI 红就是这个形态）'
    );
  }
  if (!/vue-tsc/.test(text)) {
    problems.push('runMpTypeCheck 函数体里没有 vue-tsc —— 与 CI type-check 不同源，等于没查');
  }
}

// ── 判据 3：调用点不得被挪进 if (full) { … } ───────────────────────────────
const fullBlock = braceRange(src, 'if (full)');
if (fullBlock) {
  const [blockStart, blockEnd] = fullBlock;
  for (const c of calls) {
    if (c.index >= blockStart && c.index < blockEnd) {
      problems.push(
        `runMpTypeCheck('${c.name}') 被挪进 \`if (full)\` 块 —— 默认档不再跑，等于没跑`
      );
    }
  }
}

if (problems.length) {
  fail(`\n  ${problems.join('\n  ')}\n`);
}

console.log(
  `${TAG} OK：${TARGET} 默认档真跑 ${mpDirs.length} 端 type-check（${mpDirs.join(', ')}），` +
    `runMpTypeCheck 函数体确认执行 vue-tsc，且调用点均在 if (full) 之外`
);
