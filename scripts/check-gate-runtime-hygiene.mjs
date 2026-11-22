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
 *   3. `scripts/devops/verify-*.mjs`（负向验证脚本）写 `.tmp` 前必须 `mkdirSync`
 *      —— `.tmp` 是 gitignore 的，新检出（CI runner）根本不存在，否则
 *      `copyFileSync/writeFileSync` 抛 ENOENT；本机因 .tmp 常驻而恒绿 = 「本机绿、CI 红」。
 *
 * 负向验证：在任一 check-*.mjs 里加 `import { spawnSync } from 'node:child_process'`
 * => 红；把某条命令改成 `--require .tmp/tools/nopipe.cjs` => 红；删掉
 * `devops/verify-mp-template-comment-drift.mjs` 里 `copyFileSync(TARGET, BACKUP)` 前的
 * `mkdirSync(dirname(BACKUP), …)` => 红（判据 3，2026-11-22 逐条注入实测）。
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

const SELF = 'check-gate-runtime-hygiene.mjs';
const scriptsDir = join(ROOT, 'scripts');

/* ------------------------------------------------------------------ *
 * 三条判据都抽成纯函数（输入 = 相对路径 + 源码文本），供 .test.mjs 直接调用。
 * 不抽出来就只能靠「改真仓文件再跑脚本」，夹具必然要动工作区 —— 那是污染源。
 * ------------------------------------------------------------------ */

/** 判据 1：禁同步子进程（WorkBuddy 下 EBUSY 把「没跑」记成「全红」）。 */
export function problemsOfSyncSpawn(rel, rawSrc) {
  const src = stripComments(rawSrc);
  const hit = src.match(/\b(spawnSync|execSync|execFileSync)\b/);
  return hit
    ? [
        `${rel}: 使用了同步子进程 ${hit[1]}（WorkBuddy 下 EBUSY 会把「没跑」记成「全红」）——改用 scripts/lib/async-spawn.mjs`
      ]
    : [];
}

/**
 * 判据 3：负向验证脚本（`scripts/devops/verify-*.mjs`）写 `.tmp` 前必须自建父目录。
 * 成因（2026-11-22 CI run 45037911316 实证）：`.tmp` 是 gitignore 的 ⇒ runner 新检出上
 * **根本不存在**；`devops/verify-mp-template-comment-drift.mjs` 的
 * `copyFileSync(TARGET, BACKUP)`（BACKUP 在 `.tmp/` 下）直接
 * `ENOENT: copyfile ... -> .tmp/orders.vue.template-comment-backup`。
 * 本机因 `.tmp` 常驻而恒绿 —— 典型「本机绿、CI 红」，且报的是 fs 错误、看着像环境抖动。
 * 判据只认「写入目标确实落在 .tmp」（含 `const X = join(root, '.tmp/…')` 这类别名），
 * 不搞「凡写文件都要 mkdir」——那会把写既有目录的正常脚本全判红。
 */
export function problemsOfTmpWrite(rel, rawSrc) {
  if (!/^scripts\/devops\/verify-[^/]*\.mjs$/.test(rel)) return [];
  const src = stripComments(rawSrc);
  const aliases = new Set(
    [...src.matchAll(/\bconst\s+([A-Za-z_$][\w$]*)\s*=\s*[^;\n]*['"`]\.tmp['"`/\\]/g)].map(
      (m) => m[1]
    )
  );
  const mkdirAt = src.search(/\bmkdirSync\s*\(/);
  const writeCall = /\b(copyFileSync|cpSync|writeFileSync|appendFileSync|writeFile)\s*\(/g;
  let m;
  while ((m = writeCall.exec(src)) !== null) {
    const rest = src.slice(m.index, m.index + 400);
    const open = rest.indexOf('(');
    // 取该调用的实参文本（到行末即可，判据只看目标表达式）
    const lineEnd = rest.indexOf('\n', open);
    const args = rest.slice(open + 1, lineEnd === -1 ? rest.length : lineEnd);
    const isCopy = m[1] === 'copyFileSync' || m[1] === 'cpSync';
    const dest = isCopy ? args.split(',').slice(1).join(',') : args;
    const hitTmp =
      dest.includes('.tmp') || [...aliases].some((a) => new RegExp(`\\b${a}\\b`).test(dest));
    if (!hitTmp) continue;
    if (mkdirAt === -1 || mkdirAt > m.index) {
      return [
        `${rel}: 写入 .tmp 的调用 \`${m[1]}(…)\` 之前没有 mkdirSync ——`.concat(
          '.tmp 是 gitignore 的，新检出（CI runner）不存在 ⇒ ENOENT 假红；先 mkdirSync(dirname(DEST), { recursive: true })'
        )
      ]; // 每个文件只报一次，避免刷屏
    }
  }
  return [];
}

/** 判据 2：禁相对路径 `--require`（Node v24 按 CWD 解析必失败）。 */
export function problemsOfRelativeRequire(rel, rawSrc) {
  const text = stripComments(rawSrc);
  const out = [];
  for (const line of text.split(/\r?\n/)) {
    const m = line.match(/--require\s+["']?([^\s"']+)/);
    if (!m) continue;
    const target = m[1];
    // 绝对路径：以 / 或盘符开头；变量/占位符：$VAR、${...}、$(...)、<repo> 都视为未知，不做判断
    if (/^([/\\]|[A-Za-z]:[\\/])/.test(target)) continue;
    if (/^[$<]/.test(target)) continue;
    out.push(
      `${rel}: --require 使用相对路径 "${target}"（Node v24 按 CWD 解析必失败，改用绝对路径）`
    );
  }
  return out;
}

const gateFiles = readdirSync(scriptsDir).filter((f) => /^check-.*\.mjs$/.test(f));

/**
 * 全仓扫描。**只在被当作脚本直接执行时运行**——`.test.mjs` 会 import 本模块来调纯函数，
 * 若无条件执行，夹具一 import 就把整个门禁跑一遍，真红时 `fail()` 的 process.exit 会
 * 把测试进程一并带走（表现为「夹具跑不起来」而非「夹具绿」）。
 */
function main() {
  const problems = [];

  const extraFiles = ['run-audit-gates.mjs'].map((f) => join(scriptsDir, f));
  for (const file of [...gateFiles.map((f) => join(scriptsDir, f)), ...extraFiles]) {
    const rel = relative(ROOT, file).replace(/\\/g, '/');
    if (rel.endsWith(SELF)) continue;
    let src;
    try {
      src = readFileSync(file, 'utf8');
    } catch (e) {
      problems.push(`${rel}: 读取失败 ${e.message}`);
      continue;
    }
    problems.push(...problemsOfSyncSpawn(rel, src));
  }

  for (const file of walk(join(ROOT, 'scripts', 'devops')).filter((f) =>
    /verify-.*\.mjs$/.test(f)
  )) {
    const rel = relative(ROOT, file).replace(/\\/g, '/');
    try {
      problems.push(...problemsOfTmpWrite(rel, readFileSync(file, 'utf8')));
    } catch (e) {
      problems.push(`${rel}: 读取失败 ${e.message}`);
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
      text = readFileSync(file, 'utf8');
    } catch {
      continue;
    }
    problems.push(...problemsOfRelativeRequire(relative(ROOT, file).replace(/\\/g, '/'), text));
  }

  if (problems.length) {
    fail(`门禁运行时卫生问题 ${problems.length} 处：\n  - ${problems.join('\n  - ')}`);
  }

  console.log(
    `${TAG} OK：${gateFiles.length} 个 check-*.mjs 均无同步子进程，scripts/CI 里无相对路径 --require，devops/verify-* 写 .tmp 前均已自建目录`
  );
}

const invokedDirectly =
  process.argv[1] !== undefined && resolve(process.argv[1]) === fileURLToPath(import.meta.url);
if (invokedDirectly) main();
