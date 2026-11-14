#!/usr/bin/env node
/**
 * 门禁：佣金/分成参数写侧必须过限幅（MA-069 / MA-090 / MA-100 同族防复发）。
 *
 * 缺陷类（R17 复核实锤）：线长 `commission_rate_bps`、机器账单 `share_bps`、线路成员
 * `commission_rate_bps/commission_per_unit_cents` 都是**裸 Map / 裸实体直落**，越界值被
 * `LineCommissionJob` 与账单生成按万分比乘进真实出账（bps=5000 ⇒ 5 倍 GMV 佣金）。
 * 修法统一走 `CommissionParamBounds`，本门禁保证「以后再写一处分成就必须先过闸」。
 *
 * 🔴 2026-11-14 升级：判定从**文件级**改**逐调用点**。旧判据「文件含 GUARD_IMPORT 即过」
 * 有盲区——已过闸文件里再补一记裸 `setShareBps(99999)` 门禁照绿（负向注入实证）。
 * 新判据：每个 setter 调用点的**参数表达式**必须内含
 *   `CommissionParamBounds.requireBps|requireFixedCents|requireCents`（限定名或静态导入形态），
 *   否则该调用点即红；domain/**（setter 定义本身）豁免；
 *   `scripts/commission-param-bounds-allowlist.json`（文件 + 未过闸命中数，棘轮双向）收自带闸先例。
 * 锚点：命中写侧文件数 < 5 即红（正则退化会让零命中变假绿）。
 *
 *   node scripts/check-commission-param-bounds.mjs
 */
import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const TAG = '[check-commission-param-bounds]';
const ALLOWLIST = join('scripts', 'commission-param-bounds-allowlist.json');
const SETTER_RE =
  /\bset(CommissionRateBps|CommissionFixedCents|ShareBps|CommissionPerUnitCents)\s*\(/g;
const GUARD_ARG_RE =
  /CommissionParamBounds\s*\.\s*(?:requireBps|requireFixedCents|requireCents)\s*\(|(?<![.\w])(?:requireBps|requireFixedCents|requireCents)\s*\(/;
const MIN_WRITERS = 5;

function walk(dir, out = []) {
  for (const name of readdirSync(dir)) {
    const full = join(dir, name);
    const st = statSync(full);
    if (st.isDirectory()) {
      if (name === 'node_modules' || name === 'target' || name === 'static') continue;
      walk(full, out);
    } else if (name.endsWith('.java')) {
      out.push(full);
    }
  }
  return out;
}

/**
 * 逐调用点抽取：找到 setter 调用后从 `(` 起做括号计数（跳过字符串字面量与注释），
 * 深度归零即参数终点——正确处理 `setX(requireBps("label", getBps()))` 的两层嵌套与多行调用。
 * @returns {{line:number, setter:string, arg:string, guarded:boolean}[]}
 */
export function extractCalls(src) {
  const out = [];
  const lineStarts = [0];
  for (let i = 0; i < src.length; i++) {
    if (src[i] === '\n') lineStarts.push(i + 1);
  }
  const lineAt = (idx) => {
    // 二分找 idx 之前的最近行首偏移（1-based 行号）
    let lo = 0, hi = lineStarts.length - 1;
    while (lo < hi) {
      const mid = (lo + hi + 1) >> 1;
      if (lineStarts[mid] <= idx) lo = mid;
      else hi = mid - 1;
    }
    return lo + 1;
  };
  for (const m of src.matchAll(SETTER_RE)) {
    const openIdx = m.index + m[0].length - 1;
    let depth = 0, end = -1, inStr = false, inLineCmt = false, inBlockCmt = false;
    for (let i = openIdx; i < src.length; i++) {
      const ch = src[i];
      const next = src[i + 1];
      if (inLineCmt) { if (ch === '\n') inLineCmt = false; continue; }
      if (inBlockCmt) { if (ch === '*' && next === '/') { inBlockCmt = false; i++; } continue; }
      if (inStr) {
        if (ch === '\\') { i++; continue; }
        if (ch === '"') inStr = false;
        continue;
      }
      if (ch === '/' && next === '/') { inLineCmt = true; i++; continue; }
      if (ch === '/' && next === '*') { inBlockCmt = true; i++; continue; }
      if (ch === '"') { inStr = true; continue; }
      if (ch === '(') depth++;
      if (ch === ')') {
        depth--;
        if (depth === 0) { end = i; break; }
      }
    }
    if (end < 0) continue; // 括号不平衡（非合法 Java），跳过
    const arg = src.slice(openIdx + 1, end);
    out.push({
      line: lineAt(m.index),
      setter: m[1],
      arg,
      guarded: GUARD_ARG_RE.test(arg),
    });
  }
  return out;
}

/**
 * 纯判定：写侧文件 × 逐调用点 vs 白名单（未过闸命中数棘轮双向）。
 * @param {{rel:string,calls:{line:number,setter:string,guarded:boolean}[]}[]} writers
 * @param {Record<string,{count:number,reason?:string}>} allow
 */
export function evaluate(writers, allow) {
  const problems = [];
  for (const w of writers) {
    const unguarded = w.calls.filter((c) => !c.guarded);
    if (unguarded.length === 0) continue;
    const entry = allow[w.rel];
    if (entry && entry.count === unguarded.length) continue; // 白名单棘轮对平
    if (entry) {
      problems.push(
        `${w.rel}：白名单登记 ${entry.count} 处未过闸，实际 ${unguarded.length} 处（行 ${unguarded
          .map((c) => c.line)
          .join(',')}）——棘轮要求同步`
      );
    } else {
      for (const c of unguarded) {
        problems.push(
          `${w.rel}:${c.line}：set${c.setter} 绕过 CommissionParamBounds（参数未过 require* 闸，上线写入会越界出账）`
        );
      }
    }
  }
  const stale = Object.keys(allow).filter((k) => !writers.some((w) => w.rel === k));
  for (const k of stale) problems.push(`白名单残留已不存在的写侧文件：${k}（请删除条目）`);
  return problems;
}

function main() {
  const allow = existsSync(join(ROOT, ALLOWLIST))
    ? JSON.parse(readFileSync(join(ROOT, ALLOWLIST), 'utf8'))
    : {};

  const writers = [];
  for (const service of ['trade-service', 'device-service', 'common', 'jiangyi-gateway']) {
    const base = join(ROOT, 'services', service, 'src', 'main', 'java');
    if (!existsSync(base)) continue;
    for (const file of walk(base)) {
      const rel = relative(ROOT, file).replace(/\\/g, '/');
      // 实体类只是定义 setter 本身，不是「写侧」
      if (/\/domain\/[^/]+\.java$/.test(rel)) continue;
      const calls = extractCalls(readFileSync(file, 'utf8'));
      if (!calls.length) continue;
      writers.push({ rel, calls });
    }
  }

  const failures = [];
  if (writers.length < MIN_WRITERS) {
    failures.push(
      `锚点失效：只扫到 ${writers.length} 个写侧文件（预期 ≥ ${MIN_WRITERS}），正则或目录可能已退化`
    );
  }
  failures.push(...evaluate(writers, allow));

  if (failures.length) {
    console.error(`${TAG} FAIL（${failures.length} 项）`);
    for (const f of failures) console.error('  ✗ ' + f);
    process.exit(1);
  }
  const totalCalls = writers.reduce((n, w) => n + w.calls.length, 0);
  const guardedCalls = writers.reduce((n, w) => n + w.calls.filter((c) => c.guarded).length, 0);
  console.log(
    `${TAG} OK：${writers.length} 个写侧文件 ${totalCalls} 个调用点逐点过闸` +
      `（${guardedCalls} 走 CommissionParamBounds，${Object.keys(allow).length} 个自带闸先例在白名单）`
  );
}

if (process.argv[1] && resolve(process.argv[1]) === resolve(fileURLToPath(import.meta.url))) {
  main();
}
