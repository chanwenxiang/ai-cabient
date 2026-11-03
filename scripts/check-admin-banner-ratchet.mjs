#!/usr/bin/env node
/**
 * 门禁：admin 常驻横幅棘轮（UI 样式统一计划 Phase 3，2026-11-03）。
 *
 * 🔴 缺陷类：规则/口径解释型说明常驻成 el-alert 横幅，页面越加越长；应改成
 *   HelpTip（标题旁问号）按需展示。本门禁只允许横幅数量下降，不允许新增。
 *
 * 判据：clients/admin-vue/src/views/**\/*.vue 中 `<el-alert` 出现次数 ≤ 基线。
 * 范围刻意只数 el-alert（标准常驻横幅）；自定义 `*-banner` / `*-alert` class
 * 暂未纳入（下一批迁移时再扩，避免一次改面过大）。
 *
 * 自检：fixture 计数 + 空 scope 必红；超基线列出新增最多的文件。
 */
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const TAG = '[check-admin-banner-ratchet]';
const PRINT_ONLY = process.argv.includes('--print');
const BASELINE = 52;
const VIEWS_DIR = join(root, 'clients/admin-vue/src/views');
const ALERT_RE = /<el-alert\b/g;

function walk(dir, out = []) {
  let entries;
  try {
    entries = readdirSync(dir);
  } catch {
    return out;
  }
  for (const name of entries) {
    const full = join(dir, name);
    const st = statSync(full);
    if (st.isDirectory()) walk(full, out);
    else if (name.endsWith('.vue')) out.push(full);
  }
  return out;
}

export function countAlerts(text) {
  return (text.match(ALERT_RE) || []).length;
}

(function selftest() {
  const fake = {
    'a.vue': '<el-alert title="x" />\n<el-button>y</el-button>',
    'b.vue': 'no banner',
    'c.vue': '<el-alert/><el-alert>'
  };
  const total = Object.values(fake).reduce((sum, text) => sum + countAlerts(text), 0);
  if (total !== 3) fail(`selftest: 应计 3，实际 ${total}`);
})();

function fail(msg) {
  console.error(`${TAG} FAIL: ${msg}`);
  process.exit(1);
}

const files = walk(VIEWS_DIR);
const perFile = {};
let total = 0;
for (const f of files) {
  const n = countAlerts(readFileSync(f, 'utf8'));
  if (n > 0) {
    perFile[f] = n;
    total += n;
  }
}

if (PRINT_ONLY) {
  console.log(JSON.stringify({ files: files.length, total }, null, 2));
  process.exit(0);
}

if (files.length === 0) fail('views scope 扫到 0 个 .vue（glob 失效？）');
if (total > BASELINE) {
  const top = Object.entries(perFile)
    .sort((a, b) => b[1] - a[1])
    .slice(0, 8)
    .map(([f, n]) => `${f.replaceAll('\\', '/')}(${n})`);
  fail(
    `常驻 el-alert ${total} > 基线 ${BASELINE}（+${total - BASELINE}）。\n  新增最多：${top.join(', ')}\n  修法：规则/口径解释改用 <HelpTip>（标题旁问号）；确需即时行动提示，用 message/inline error，不要新增常驻横幅。`
  );
}

console.log(`${TAG} OK：admin 常驻 el-alert=${total}≤${BASELINE}（只减不增）`);
