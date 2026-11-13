#!/usr/bin/env node
/**
 * 门禁（S3-3b）：前端「元 → 分」内联换算的**增量棘轮**。
 *
 * 背景：S3 双侧同源族的单位方向缺口——`check-no-inline-money-math` 只治「分→元」（/100），
 * 「元→分」（*100）方向 R1-§5.2 点名 5 处无门禁。但 `* 100` 同时混杂**百分比换算**语义
 * （bps→%、rate→percent），静态判据无法区分（§八 Q3：误报面大的不做硬扫）——
 * 所以按**增量棘轮**：现量全部逐文件登记（scripts/yuan-to-cents-baseline.json，
 * 26 文件 55 处），命中数与基线严格相等（多=新增未登记红，少=删旧没销基线也红）。
 *
 * 新代码要做元→分必须走 `yuanToCents`（shared）或带理由登记基线条目；
 * 存量 convert 一个、销一个条目（棘轮允许收缩）。
 *
 *   node scripts/check-yuan-to-cents.mjs
 */
import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const TAG = '[check-yuan-to-cents]';
const BASELINE_PATH = join('scripts', 'yuan-to-cents-baseline.json');
const DIRS = [
  join('clients', 'admin-vue', 'src', 'views'),
  join('clients', 'admin-vue', 'src', 'components'),
  join('clients', 'admin-vue', 'src', 'composables'),
  join('clients', 'merchant-mp', 'src'),
  join('clients', 'consumer-mp', 'src')
];
const PATTERN = /\*\s*100\b/g;

export function countHits(src) {
  return (src.match(PATTERN) || []).length;
}

/** 纯判定：实际命中 vs 基线（与 check-bare-localdate 同款双向棘轮）。 */
export function evaluate(actual, baseline) {
  const problems = [];
  const baseMap = new Map(baseline.map((e) => [e.file, e.count]));
  const actualMap = new Map(actual.map((e) => [e.file, e.count]));
  for (const [file, hit] of actualMap) {
    if (!baseMap.has(file)) {
      problems.push(
        `${file}: 新增 * 100 命中 ${hit} 处不在基线——元→分必须走 yuanToCents（shared），百分比换算也须登记基线（判据无法区分语义，登记即显式化）`
      );
      continue;
    }
    if (hit !== baseMap.get(file)) {
      problems.push(
        `${file}: * 100 命中 ${hit} 与基线 ${baseMap.get(file)} 不符` +
          (hit > baseMap.get(file) ? '（新增未登记）' : '（旧命中被删，基线条目应同步销账）')
      );
    }
  }
  for (const [file] of baseMap) {
    if (!actualMap.has(file)) {
      problems.push(`基线陈旧：${file} 已无 * 100 命中，基线条目必须删除（棘轮只许收缩）`);
    }
  }
  return problems;
}

function walk(dir, out = []) {
  if (!existsSync(dir)) return out;
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    const st = statSync(p);
    if (st.isDirectory()) {
      if (['node_modules', 'dist', 'dev'].includes(name)) continue;
      walk(p, out);
    } else if (/\.(vue|ts)$/.test(name)) out.push(p);
  }
  return out;
}

function main() {
  const baselinePath = join(ROOT, BASELINE_PATH);
  if (!existsSync(baselinePath)) fail(`基线缺失 ${BASELINE_PATH}`);
  const baseline = JSON.parse(readFileSync(baselinePath, 'utf8')).entries || [];

  const files = DIRS.flatMap((d) => walk(join(ROOT, d)));
  if (files.length < 400)
    fail(`扫描面退化：只扫到 ${files.length} 个文件（锚点 400，实测基线 412）`);

  const actual = [];
  for (const f of files) {
    const n = countHits(readFileSync(f, 'utf8'));
    if (n > 0) actual.push({ file: relative(ROOT, f).replace(/\\/g, '/'), count: n });
  }
  const problems = evaluate(actual, baseline);
  if (problems.length > 0) {
    for (const p of problems) console.error(`${TAG} FAIL ${p}`);
    console.error(`${TAG} 共 ${problems.length} 项（S3-3b 元→分增量棘轮）`);
    process.exit(1);
  }
  const total = actual.reduce((n, e) => n + e.count, 0);
  console.log(
    `${TAG} OK：${files.length} 个前端文件，* 100 命中 ${total} 处（${actual.length} 文件）全部与基线一致`
  );
}

function fail(msg) {
  console.error(`${TAG} FAIL: ${msg}`);
  process.exit(1);
}

if (process.argv[1] && resolve(process.argv[1]) === resolve(fileURLToPath(import.meta.url))) {
  main();
}
