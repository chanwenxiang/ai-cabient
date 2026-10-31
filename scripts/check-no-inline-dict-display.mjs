#!/usr/bin/env node
/**
 * 门禁：admin 模板里**不得把枚举值直接翻中文**（棘轮）。
 *
 * 缺陷类（R7 实测）：
 *   - `{{ row.status === 'ACTIVE' ? '启用' : row.status }}` —— 非 ACTIVE 时**回退裸码**（已修）；
 *   - 同一状态在不同页写成「已启用/停用」「启用/已归档」——字典改了页面不跟。
 * 正确形态：`displayLabel('<dict_type>', value, '未知')` / `dictOptions(...)`。
 *
 * 判据：扫 `clients/admin-vue/src/**\/*.vue` 的模板插值 `{{ … === '<ENUM>' ? '<中文>' … }}`；
 * 每处必须命中 `scripts/inline-dict-display-allowlist.json`（文件+计数，棘轮双向）。
 * 锚点：扫到的 .vue 文件数 < 100 即红（解析退化会让零命中变假绿）。
 *
 *   node scripts/check-no-inline-dict-display.mjs
 */
import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const TAG = '[check-no-inline-dict-display]';
const SCAN_DIR = join('clients', 'admin-vue', 'src');
const ALLOWLIST = join('scripts', 'inline-dict-display-allowlist.json');
const MIN_FILES = 100;

const ENUM = [
  'ACTIVE',
  'INACTIVE',
  'DISABLED',
  'ENABLED',
  'PENDING',
  'OPEN',
  'CLOSED',
  'HANDLED',
  'REPLIED',
  'SUBMITTED',
  'FAILED',
  'LEDGER_ONLY',
  'ACCRUED',
  'SETTLED',
  'VOIDED',
  'ON_SALE',
  'DEPLETED'
];
const PATTERN = new RegExp(`\\{\\{[^}]*?(?:===|!==)\\s*'(${ENUM.join('|')})'\\s*\\?`, 'g');

function walk(dir, out = []) {
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    if (statSync(p).isDirectory()) walk(p, out);
    else if (name.endsWith('.vue')) out.push(p);
  }
  return out;
}

const files = walk(join(ROOT, SCAN_DIR));
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
      `${file}：模板里把枚举直接翻中文 ${count} 处且未登记（改用 displayLabel/dictOptions）`
    );
    continue;
  }
  if (!entry.reason || !entry.reason.trim()) problems.push(`${file}：白名单缺 reason`);
  if ((entry.count ?? 1) !== count) {
    problems.push(
      `${file}：白名单登记 ${entry.count ?? 1} 处，实际 ${count} 处（棘轮：改少要下调）`
    );
  }
}
for (const entry of allow) {
  if (!found.has(entry.file))
    problems.push(`${entry.file}：已无对应写法（修好请删除该条，防清单腐烂）`);
}

if (problems.length) {
  console.error(`${TAG} FAIL: 模板内枚举硬编码中文：\n  - ` + problems.join('\n  - '));
  process.exit(1);
}
console.log(
  `${TAG} OK（扫 ${files.length} 个 .vue；硬编码 ${found.size} 文件 / ${[...found.values()].reduce((a, b) => a + b, 0)} 处，全部在白名单内）`
);
