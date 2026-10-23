#!/usr/bin/env node
/**
 * 门禁：门禁脚本自身的运行时卫生——**假红/假绿同罪**。
 *
 * 背景（lessons #292）：WorkBuddy 环境下 Node **同步**子进程一律
 * `status=null` + `EBUSY`，`run-audit-gates.mjs` 曾把 44 条命令全记成「失败」，
 * 实际一个都没跑成。正解是 `scripts/lib/async-spawn.mjs` 的异步 spawn（失败
 * fail-closed），拿不到 stdout 的 `nopipe.cjs` 垫片无效。
 *
 * 背景（lessons #230）：`NODE_OPTIONS="--require .tmp/tools/nopipe.cjs"` 在 Node v24
 * 下按 CWD 解析相对路径失败，进程秒退 exit 1 —— 与 EBUSY 是两种故障，但都会把
 * 「没检查」伪装成「检查失败」。`--require` 必须绝对路径。
 *
 * 判据（注释先剥离，注释里提到这些 API 不算）：
 *   1. `scripts/check-*.mjs`（含 .test.mjs）与 `run-audit-gates.mjs` 不得 import
 *      或调用 spawnSync / execSync / execFileSync（本门禁自身除外，扫描规则里必然
 *      出现这些字面量）；
 *   2. `scripts/**`、`package.json`、`.github/workflows/**`、`infra/**` 的可执行/
 *      配置文件里不得出现相对路径的 `--require`（必须以 `/`、盘符或变量开头）。
 *      文档（.md）不扫：那是说明文字，不是会真正执行的命令。
 *
 * 负向验证：在任一 check-*.mjs 里加 `import { spawnSync } from 'node:child_process'`
 * => 红；把某条命令改成 `--require .tmp/tools/nopipe.cjs` => 红。
 *
 *   node scripts/check-gate-runtime-hygiene.mjs
 */
import { readFileSync, readdirSync } from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const TAG = '[check-gate-runtime-hygiene]';

function fail(msg) {
  console.error(`${TAG} FAIL: ${msg}`);
  process.exit(1);
}

function walk(dir, out = []) {
  let entries;
  try {
    entries = readdirSync(dir, { withFileTypes: true });
  } catch {
    return out;
  }
  for (const e of entries) {
    if (['node_modules', '.git', 'dist', 'target'].includes(e.name)) continue;
    const p = join(dir, e.name);
    if (e.isDirectory()) walk(p, out);
    else out.push(p);
  }
  return out;
}

/** 剥离行注释与块注释，保留字符串（避免注释里的反例让门禁假红） */
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
    if (c === '/' && next === '/') {
      const end = src.indexOf('\n', i);
      i = end === -1 ? src.length : end;
      out += ' ';
      continue;
    }
    if (c === '/' && next === '*') {
      const end = src.indexOf('*/', i + 2);
      i = end === -1 ? src.length : end + 2;
      out += ' ';
      continue;
    }
    out += c;
    i += 1;
  }
  return out;
}

const problems = [];

const SELF = 'check-gate-runtime-hygiene.mjs';
const scriptsDir = join(ROOT, 'scripts');
const gateFiles = readdirSync(scriptsDir).filter((f) => /^check-.*\.mjs$/.test(f));
const extraFiles = ['run-audit-gates.mjs'].map((f) => join(scriptsDir, f));
for (const file of [...gateFiles.map((f) => join(scriptsDir, f)), ...extraFiles]) {
  const rel = relative(ROOT, file).replace(/\\/g, '/');
  if (rel.endsWith(SELF)) continue;
  let src;
  try {
    src = stripComments(readFileSync(file, 'utf8'));
  } catch (e) {
    problems.push(`${rel}: 读取失败 ${e.message}`);
    continue;
  }
  const hit = src.match(/\b(spawnSync|execSync|execFileSync)\b/);
  if (hit) {
    problems.push(
      `${rel}: 使用了同步子进程 ${hit[1]}（WorkBuddy 下 EBUSY 会把「没跑」记成「全红」）——改用 scripts/lib/async-spawn.mjs`
    );
  }
}

// 相对路径 --require
const requireScopes = [
  ...walk(join(ROOT, 'scripts')),
  join(ROOT, 'package.json'),
  ...walk(join(ROOT, '.github', 'workflows')),
  ...walk(join(ROOT, 'infra')).filter((f) => /\.(sh|ps1|ya?ml)$/.test(f))
];
for (const file of requireScopes) {
  if (!/\.(mjs|js|cjs|json|sh|ps1|ya?ml)$/.test(file)) continue;
  if (file.endsWith(SELF)) continue;
  let text;
  try {
    text = stripComments(readFileSync(file, 'utf8'));
  } catch {
    continue;
  }
  for (const line of text.split(/\r?\n/)) {
    const m = line.match(/--require\s+["']?([^\s"']+)/);
    if (!m) continue;
    const target = m[1];
    // 绝对路径：以 / 或盘符开头；变量/占位符：$VAR、${...}、$(...)、<repo> 都视为未知，不做判断
    if (/^([/\\]|[A-Za-z]:[\\/])/.test(target)) continue;
    if (/^[$<]/.test(target)) continue;
    problems.push(
      `${relative(ROOT, file).replace(/\\/g, '/')}: --require 使用相对路径 "${target}"（Node v24 按 CWD 解析必失败，改用绝对路径）`
    );
  }
}

if (problems.length) {
  fail(`门禁运行时卫生问题 ${problems.length} 处：\n  - ${problems.join('\n  - ')}`);
}

console.log(
  `${TAG} OK：${gateFiles.length} 个 check-*.mjs 均无同步子进程，scripts/CI 里无相对路径 --require`
);
