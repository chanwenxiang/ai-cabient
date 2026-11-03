#!/usr/bin/env node
/**
 * 门禁：裸业务色值棘轮（UI 样式统一计划 Phase 1，2026-11-03）。
 *
 * 🔴 缺陷类：语义色板已落（admin main.css 的 --color-* / --chart-* / --kpi-accent-* 系列；
 *   mp theme.css --color-pos/-neg + 既有语义别名/圆角四档/--chart-1..5），但页面仍在
 *   继续写裸色值——不设棘轮，存量只会在「每次局部改样式」中继续增长，令牌体系永远落不了地。
 *
 * 判据（棘轮 = 只减不增）：四种口径分别计数，任一不得超基线：
 *   hex6 / hex3 / hex8 / rgb()·rgba() 字面量（含 var(...) 的不算，如 rgba(var(--x), .2)）。
 * 范围：
 *   - admin：views + components + layouts 的全部 .vue（排除 styles/*.css、stores/settings.ts、
 *     utils/echarts.ts 等令牌/调色板定义源）；
 *   - mp：consumer / merchant 的 pages + components（.vue/.ts）+ 两端 src 下 .css、
 *     shared-uni 的 components（.vue/.ts）、utils（.ts）、根级 .css（排除 theme.css）；
 *     两端 App.vue 是 token fallback 定义文件，不在 pages/components 下，天然排除。
 * 超基线 ⇒ FAIL 并列出新增最多的文件；减少任意量 ⇒ 提示可下调基线（本次不自动改文件）。
 *
 * 自检：内置 fixture 走计数函数（防恒真/恒假），并断言 scope 至少覆盖到 components；
 *       断言失败即 exit 1。
 */
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const TAG = '[check-ui-barehex-ratchet]';
const PRINT_ONLY = process.argv.includes('--print');

const BASELINES = {
  admin: { hex6: 251, hex3: 47, hex8: 1, rgba: 144 },
  mp: { hex6: 1048, hex3: 79, hex8: 0, rgba: 166 }
};

const ADMIN_GLOBS = [
  'clients/admin-vue/src/views/**/*.vue',
  'clients/admin-vue/src/components/**/*.vue',
  'clients/admin-vue/src/layouts/**/*.vue'
];
const MP_GLOBS = [
  'clients/consumer-mp/src/pages/**/*.vue',
  'clients/consumer-mp/src/pages/**/*.ts',
  'clients/consumer-mp/src/components/**/*.vue',
  'clients/consumer-mp/src/components/**/*.ts',
  'clients/consumer-mp/src/**/*.css',
  'clients/merchant-mp/src/pages/**/*.vue',
  'clients/merchant-mp/src/pages/**/*.ts',
  'clients/merchant-mp/src/components/**/*.vue',
  'clients/merchant-mp/src/components/**/*.ts',
  'clients/merchant-mp/src/**/*.css',
  'packages/shared-uni/src/components/**/*.vue',
  'packages/shared-uni/src/components/**/*.ts',
  'packages/shared-uni/src/utils/**/*.ts',
  'packages/shared-uni/src/*.css'
];
const HEX6_RE = /#[0-9a-fA-F]{6}\b/g;
const HEX3_RE = /#[0-9a-fA-F]{3}\b/g;
const HEX8_RE = /#[0-9a-fA-F]{8}\b/g;
/** 仅数字字面量；rgba(var(--x), .2) 这类令牌用法不算裸色。 */
const RGB_RE = /\brgba?\(\s*(?!var\()/g;
const METRICS = ['hex6', 'hex3', 'hex8', 'rgba'];

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

/** glob 极简实现：支持 `dir/**\/*.ext`、`dir/*.ext` 与单文件。 */
function expandGlob(glob) {
  const files = [];
  const recursive = glob.match(/^(.*)\/\*\*\/\*\.(.+)$/);
  if (recursive) {
    walk(join(root, recursive[1]), [`.${recursive[2]}`], files);
    return files;
  }
  const single = glob.match(/^(.*)\/\*\.(.+)$/);
  if (single) {
    const dir = join(root, single[1]);
    const ext = `.${single[2]}`;
    try {
      for (const name of readdirSync(dir)) {
        const full = join(dir, name);
        if (statSync(full).isFile() && name.endsWith(ext)) files.push(full);
      }
    } catch {
      /* 缺目录忽略 */
    }
    return files;
  }
  const exact = join(root, glob);
  try {
    if (statSync(exact).isFile()) files.push(exact);
  } catch {
    /* 缺文件忽略 */
  }
  return files;
}

/** 统计一段文本的四类裸色值。 */
export function countColors(text) {
  return {
    hex6: (text.match(HEX6_RE) || []).length,
    hex3: (text.match(HEX3_RE) || []).length,
    hex8: (text.match(HEX8_RE) || []).length,
    rgba: (text.match(RGB_RE) || []).length
  };
}

function totalOf(perFile) {
  const total = { hex6: 0, hex3: 0, hex8: 0, rgba: 0 };
  for (const counts of Object.values(perFile)) {
    for (const m of METRICS) total[m] += counts[m];
  }
  return total;
}

/** 返回超出基线的指标名（纯函数，便于负向自检）。 */
function overages(counts, baselines) {
  return METRICS.filter((m) => counts.total[m] > baselines[m]);
}

/** perFile 是否覆盖到某个路径片段（兼容 Windows 反斜杠）。 */
function hasPath(counts, segment) {
  return Object.keys(counts.perFile).some((f) => f.replaceAll('\\', '/').includes(segment));
}

// ---------- 自检（防恒真/恒假） ----------
(function selftest() {
  const fake = {
    'a.vue': '#2dd4bf #fff #ffffff rgba(0, 0, 0, 0.2) rgba(var(--x), 0.2)',
    'b.vue': 'no color here',
    'c.css': '#FFFFFF #ABC #12345678'
  };
  const agg = totalOf(
    Object.fromEntries(Object.entries(fake).map(([id, text]) => [id, countColors(text)]))
  );
  if (agg.hex6 !== 3) fail(`selftest: hex6 应为 3，实际 ${agg.hex6}`);
  if (agg.hex3 !== 2) fail(`selftest: hex3 应为 2，实际 ${agg.hex3}`);
  if (agg.hex8 !== 1) fail(`selftest: hex8 应为 1，实际 ${agg.hex8}`);
  if (agg.rgba !== 1) fail(`selftest: rgba 应为 1（var() 用法不算），实际 ${agg.rgba}`);

  // 负向：超基线必须报出、等于基线不得误报、components 路径必须能被识别。
  const above = { total: { hex6: 2, hex3: 0, hex8: 0, rgba: 0 } };
  if (!overages(above, { hex6: 1, hex3: 0, hex8: 0, rgba: 0 }).includes('hex6')) {
    fail('selftest: 超基线未报出');
  }
  if (overages(above, above.total).length !== 0) fail('selftest: 等于基线误报');
  if (hasPath({ perFile: { 'a/views/x.vue': {} } }, '/components/')) {
    fail('selftest: hasPath 误报');
  }
  if (!hasPath({ perFile: { 'a\\components\\x.vue': {} } }, '/components/')) {
    fail('selftest: hasPath 漏报 Windows 路径');
  }
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
  for (const f of files) {
    const counts = countColors(readFileSync(f, 'utf8'));
    if (METRICS.some((m) => counts[m] > 0)) perFile[f] = counts;
  }
  return { files: files.length, perFile, total: totalOf(perFile) };
}

const admin = scopeCount(ADMIN_GLOBS);
// theme.css 是令牌定义文件（色值合法），豁免。
const mp = scopeCount(MP_GLOBS, ['theme.css']);

if (PRINT_ONLY) {
  console.log(
    JSON.stringify(
      {
        admin: { files: admin.files, total: admin.total },
        mp: { files: mp.files, total: mp.total }
      },
      null,
      2
    )
  );
  process.exit(0);
}

// 防空转：目录改名/glob 失效时必须红，而不是 0 == 0 假绿。
if (admin.files === 0) fail('admin scope 扫到 0 个文件（glob 失效？）');
if (mp.files === 0) fail('mp scope 扫到 0 个文件（glob 失效？）');
if (!hasPath(admin, '/components/')) {
  fail('admin scope 未覆盖 components（glob 失效？）');
}
if (!hasPath(mp, '/components/')) {
  fail('mp scope 未覆盖 components（glob 失效？）');
}
if (!hasPath(mp, 'shared-uni')) {
  fail('mp scope 未覆盖 shared-uni（glob 失效？）');
}

const report = [];
let bad = false;
for (const [scope, counts] of [
  ['admin', admin],
  ['mp', mp]
]) {
  for (const metric of overages(counts, BASELINES[scope])) {
    bad = true;
    const base = BASELINES[scope][metric];
    const now = counts.total[metric];
    const top = Object.entries(counts.perFile)
      .filter(([, c]) => c[metric] > 0)
      .sort((a, b) => b[1][metric] - a[1][metric])
      .slice(0, 5)
      .map(([f, c]) => `${f.replaceAll('\\', '/')}(${c[metric]})`);
    report.push(
      `${scope}.${metric} ${now} > 基线 ${base}（+${now - base}）新增最多：${top.join(', ')}`
    );
  }
}

if (bad) {
  fail(
    `裸业务色值超出棘轮基线（只减不增）。\n  ${report.join('\n  ')}\n  修法：改用语义令牌（admin main.css --color-*/--chart-*/--kpi-accent-*；mp theme.css --color-pos/-neg 等）；确属令牌定义文件新增色值，把该文件加进本脚本豁免并说明。`
  );
}

const summary = [
  ['admin', admin],
  ['mp', mp]
]
  .map(
    ([scope, counts]) =>
      `${scope}: hex6=${counts.total.hex6}/${BASELINES[scope].hex6} hex3=${counts.total.hex3}/${BASELINES[scope].hex3} hex8=${counts.total.hex8}/${BASELINES[scope].hex8} rgba=${counts.total.rgba}/${BASELINES[scope].rgba}`
  )
  .join(' | ');
console.log(`${TAG} OK（只减不增）：${summary}（减少后请下调脚本内基线）`);
