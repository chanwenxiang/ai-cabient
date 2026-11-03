#!/usr/bin/env node
/**
 * 门禁：admin 视图里**不得直接做「分 → 元」的 /100 换算**（棘轮）。
 *
 * 缺陷类（lessons #304/#306）：
 *   - `/ 100` 后再 `toFixed(2)` 用**浮点**表示金额，四舍五入边界不稳；
 *   - 同一金额在不同页一处用工具函数、一处手算 ⇒ 显示口径漂移。
 * 正确形态：`centsToYuanDigits` / `yuanTextZero` / `money()`（见 `clients/admin-vue/src/utils/display.ts`
 * 与 `packages/shared-uni/format`）。
 *
 * 判据：扫 `clients/admin-vue/src/views`、`clients/admin-vue/src/components` 的 .vue，
 * 统计每个文件出现 `/ 100` 的次数；必须与 `scripts/inline-money-math-allowlist.json`
 * （文件+计数，棘轮双向）一致。`* 100` 是百分比换算，不在本门禁范围。
 * 锚点：扫到的 .vue 文件数 < 100 即红。
 *
 *   node scripts/check-no-inline-money-math.mjs
 */
import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const TAG = '[check-no-inline-money-math]';
const DIRS = [
  join('clients', 'admin-vue', 'src', 'views'),
  join('clients', 'admin-vue', 'src', 'components'),
  join('clients', 'admin-vue', 'src', 'composables')
];
const ALLOWLIST = join('scripts', 'inline-money-math-allowlist.json');
const MIN_FILES = 100;
const PATTERN = /\/\s*100\b/g;

function walk(dir, out = []) {
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    if (statSync(p).isDirectory()) walk(p, out);
    // MA-138：composables 目录是 .ts（utils 同理），一并纳入棘轮
    else if (name.endsWith('.vue') || name.endsWith('.ts')) out.push(p);
  }
  return out;
}

const files = DIRS.flatMap((d) => walk(join(ROOT, d)));
if (files.length < MIN_FILES) {
  console.error(
    `${TAG} FAIL: 只扫到 ${files.length} 个 .vue（锚点 ${MIN_FILES}）⇒ 解析退化，零命中不可信`
  );
  process.exit(1);
}

const found = new Map();
for (const file of files) {
  const src = readFileSync(file, 'utf8');
  const n = (src.match(PATTERN) ?? []).length;
  if (n > 0) found.set(relative(ROOT, file).replace(/\\/g, '/'), n);
}

const allow = existsSync(join(ROOT, ALLOWLIST))
  ? JSON.parse(readFileSync(join(ROOT, ALLOWLIST), 'utf8'))
  : [];
if (!Array.isArray(allow)) {
  console.error(`${TAG} FAIL: ${ALLOWLIST} 必须是数组`);
  process.exit(1);
}

const problems = [];
for (const [file, count] of found) {
  const entry = allow.find((a) => a.file === file);
  if (!entry) {
    problems.push(
      `${file}：新增 ${count} 处 /100 金额换算（改用 centsToYuanDigits/yuanTextZero/money）`
    );
    continue;
  }
  if ((entry.count ?? 1) !== count) {
    problems.push(`${file}：白名单 ${entry.count ?? 1} 处，实际 ${count} 处（棘轮：改少要下调）`);
  }
}
for (const entry of allow) {
  if (!found.has(entry.file))
    problems.push(`${entry.file}：已无 /100（修好请删除该条，防清单腐烂）`);
}

if (problems.length) {
  console.error(`${TAG} FAIL: 视图里直接做分→元换算：\n  - ` + problems.join('\n  - '));
  process.exit(1);
}
console.log(
  `${TAG} OK（扫 ${files.length} 个 .vue；存量 ${found.size} 文件 / ${[...found.values()].reduce((a, b) => a + b, 0)} 处，全部在白名单内）`
);
