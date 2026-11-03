#!/usr/bin/env node
/**
 * 门禁：裸业务 hex 棘轮（UI 样式统一计划 Phase 1，2026-11-03）。
 *
 * 🔴 缺陷类：语义色板已落（admin main.css 的 --color-* / --chart-* / --kpi-accent-* 系列；
 *   mp theme.css --color-pos/-neg + 既有语义别名/圆角四档/--chart-1..5），但页面仍在
 *   继续写裸 hex（admin views 235 / mp 1057 处存量）——不设棘轮，存量只会在
 *   「每次局部改样式」中继续增长，令牌体系永远落不了地。
 *
 * 判据（棘轮 = 只减不增）：两个口径的 6 位 hex 计数不得超过基线：
 *   - admin：clients/admin-vue/src/views 下递归全部 .vue（基线 235）
 *   - mp：consumer 与 merchant 的 pages 递归 .vue、两端 src 下 .css、
 *     shared-uni 的 components .vue / 根 .css / utils .ts
 *     （排除 theme.css —— 它是令牌定义文件，色值合法；基线 1057）
 * 超基线 ⇒ FAIL 并列出新增最多的文件；减少任意量 ⇒ 更新本文件基线（棘轮向下）。
 *
 * 自检：内置 fixture 走判据函数（防恒真/恒假），断言失败即 exit 1。
 */
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const TAG = '[check-ui-barehex-ratchet]';

const BASELINES = {
  admin: 235,
  mp: 1013
};

const ADMIN_GLOBS = ['clients/admin-vue/src/views/**/*.vue'];
const MP_GLOBS = [
  'clients/consumer-mp/src/pages/**/*.vue',
  'clients/consumer-mp/src/**/*.css',
  'clients/merchant-mp/src/pages/**/*.vue',
  'clients/merchant-mp/src/**/*.css',
  'packages/shared-uni/src/components/**/*.vue',
  'packages/shared-uni/src/*.css',
  'packages/shared-uni/src/utils/**/*.ts'
];
const HEX_RE = /#[0-9a-fA-F]{6}\b/g;

/** 递归展开目录下匹配后缀的文件（相对 root 的 POSIX 路径）。 */
function walk(dir, exts, out) {
  let entries;
  try {
    entries = readdirSync(dir);
  } catch {
    return out;
  }
  for (const name of entries) {
    const full = join(dir, name);
    const st = statSync(full);
    if (st.isDirectory()) walk(full, exts, out);
    else if (exts.some((e) => name.endsWith(e))) out.push(full);
  }
  return out;
}

/** glob 极简实现：仅支持 递归目录加扩展名 与 单文件 两形态（本门禁够用）。 */
function expandGlob(glob) {
  const m = glob.match(/^(.*)\/\*\*\/\*\.(.+)$/);
  const files = [];
  if (m) {
    walk(join(root, m[1]), [`.${m[2]}`], files);
    return files;
  }
  const single = join(root, glob);
  try {
    if (statSync(single).isFile()) files.push(single);
  } catch {
    /* 缺文件忽略 */
  }
  return files;
}

/** 计数某口径下每文件 6 位 hex 出现次数，返回 { total, perFile }。 */
export function countHex(files, contentById) {
  const perFile = {};
  let total = 0;
  for (const id of files) {
    const text = contentById(id);
    const n = (text.match(HEX_RE) || []).length;
    if (n > 0) perFile[id] = n;
    total += n;
  }
  return { total, perFile };
}

// ---------- 自检（防恒真/恒假） ----------
(function selftest() {
  const fake = { 'a.vue': '#2dd4bf #2dd4bf', 'b.vue': 'no hex here', 'c.css': '#FFFFFF' };
  const r = countHex(Object.keys(fake), (id) => fake[id]);
  if (r.total !== 3) fail(`selftest: 计数应为 3，实际 ${r.total}`);
  if (r.perFile['a.vue'] !== 2 || r.perFile['c.css'] !== 1) fail('selftest: perFile 分布错误');
})();

function fail(msg) {
  console.error(`${TAG} FAIL: ${msg}`);
  process.exit(1);
}

function scopeCount(globs, excludes = []) {
  const files = globs
    .flatMap((g) => expandGlob(g))
    .filter((f) => !excludes.some((e) => f.includes(e)));
  const perFile = {};
  let total = 0;
  for (const f of files) {
    const text = readFileSync(f, 'utf8');
    const n = (text.match(HEX_RE) || []).length;
    if (n > 0) perFile[f] = n;
    total += n;
  }
  return { total, perFile };
}

// theme.css 是令牌定义文件（色值合法），豁免。
const admin = scopeCount(ADMIN_GLOBS);
const mp = scopeCount(MP_GLOBS, ['theme.css']);

const report = [];
let bad = false;
if (admin.total > BASELINES.admin) {
  bad = true;
  const top = Object.entries(admin.perFile)
    .sort((a, b) => b[1] - a[1])
    .slice(0, 5);
  report.push(
    `admin 裸 hex ${admin.total} > 基线 ${BASELINES.admin}（+${admin.total - BASELINES.admin}）新增最多：${JSON.stringify(top)}`
  );
}
if (mp.total > BASELINES.mp) {
  bad = true;
  const top = Object.entries(mp.perFile)
    .sort((a, b) => b[1] - a[1])
    .slice(0, 5);
  report.push(
    `mp 裸 hex ${mp.total} > 基线 ${BASELINES.mp}（+${mp.total - BASELINES.mp}）新增最多：${JSON.stringify(top)}`
  );
}

if (bad) {
  fail(
    `裸业务 hex 超出棘轮基线（只减不增）。\n  ${report.join('\n  ')}\n  修法：改用语义令牌（admin main.css --color-*/--chart-*/--kpi-accent-*；mp theme.css --color-pos/-neg 等）；确属令牌定义文件新增色值，把该文件加进本脚本豁免并说明。`
  );
}
console.log(
  `${TAG} OK：admin=${admin.total}≤${BASELINES.admin} / mp=${mp.total}≤${BASELINES.mp}（棘轮只减不增；减少后请下调脚本内基线）`
);
