#!/usr/bin/env node
/**
 * 门禁：admin 前端**自产** CSV 必须走共享转义，不得手写拼装。
 *
 * 背景（2026-11-10 四端审计 P2-6）：`SupplierReconciliationView.vue` 手写
 * `row.map(c => `"${String(c).replace(/"/g,'""')}"`).join(',')` 生成对账单 CSV ——
 * 只做了引号转义，没有中和公式注入。这份文件是**外发给供应商**的，
 * 供应商名等外部字段以 `= + - @` 开头时会被 Excel/WPS 当公式执行（OWASP CSV Injection）。
 * 同期 `check:csv-escape` 只扫 Java 的 `csv()` 助手，前端是空档。
 *
 * 判据：
 *   文件里**自己造 CSV 下载**（同时出现 `text/csv` 与 `new Blob(`）⇒ 必须引用共享函数
 *   `toCsv(` / `downloadCsv(` / `escapeCsvCell(` 之一；
 *   且不得再出现手写 `replace(/"/g` 转义（那是绕过共享实现的指纹）。
 *
 * 不算导出因而不查：`<input accept=".csv,text/csv">`（导入控件）、走后端下载的
 * `downloadAuthFile(...)`（转义在服务端 CsvCells）。
 *
 * 豁免：`src/utils/csv.ts` 是共享实现本身。
 *
 * 用法：node scripts/check-admin-csv-escape.mjs
 */
import { readFileSync, readdirSync } from 'node:fs';
import { join, dirname, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

const TAG = '[check-admin-csv-escape]';
const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const SRC = process.env.ADMIN_CSV_ESCAPE_ROOT
  ? process.env.ADMIN_CSV_ESCAPE_ROOT
  : join(ROOT, 'clients', 'admin-vue', 'src');

/** 共享实现自身与其单测豁免 */
const EXEMPT = [/\/utils\/csv\.ts$/, /\/utils\/csv\.test\.ts$/];

const SHARED_CALL_RE = /\b(?:downloadCsv|toCsv|escapeCsvCell)\s*\(/;
const HANDWRITTEN_ESCAPE_RE = /replace\(\s*\/"\s*\/g/;

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
  for (let i = 0; i < index; i += 1) if (text[i] === '\n') line += 1;
  return line;
}

const problems = [];
const files = walk(SRC).map((f) => ({
  full: f,
  rel: relative(ROOT, f).replace(/\\/g, '/')
}));

let csvProducers = 0;
for (const { full, rel } of files) {
  if (EXEMPT.some((re) => re.test(rel))) continue;
  const text = readFileSync(full, 'utf8');
  // 只有「自己在客户端造 CSV」才有转义责任；导入控件与后端下载不在此列
  if (!text.includes('text/csv') || !/new\s+Blob\s*\(/.test(text)) continue;
  csvProducers += 1;

  const handwritten = HANDWRITTEN_ESCAPE_RE.exec(text);
  if (handwritten && !SHARED_CALL_RE.test(text)) {
    problems.push({
      rel,
      line: lineOf(text, handwritten.index),
      detail: '手写引号转义拼 CSV，未走 escapeCsvCell/toCsv：公式注入未中和'
    });
    continue;
  }
  if (!SHARED_CALL_RE.test(text)) {
    problems.push({
      rel,
      line: lineOf(text, text.indexOf('text/csv')),
      detail: '自产 CSV 下载但未引用共享转义（toCsv/downloadCsv/escapeCsvCell）'
    });
  }
}

const MIN_FILES = Number(process.env.ADMIN_CSV_ESCAPE_MIN_FILES || 20);
if (files.length < MIN_FILES) {
  console.error(`${TAG} FAIL: 只扫到 ${files.length} 个文件，扫描根疑似错误（${SRC}）`);
  process.exit(1);
}

if (problems.length) {
  console.error(`${TAG} FAIL: ${problems.length} 处自产 CSV 未使用共享转义`);
  for (const p of problems) console.error(`  ${p.rel}:${p.line}  ${p.detail}`);
  console.error(
    '\n修法：改用 `@/utils/csv` 的 toCsv/downloadCsv（或逐格 escapeCsvCell）。\n' +
      '依据：Excel/WPS 会把 = + - @ 开头的单元格当公式执行；对外导出的文件属注入面。'
  );
  process.exit(1);
}

console.log(`${TAG} OK: ${files.length} 个文件，${csvProducers} 处自产 CSV 均走共享转义`);
