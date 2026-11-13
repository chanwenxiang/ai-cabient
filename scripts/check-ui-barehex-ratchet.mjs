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
 * 超基线 ⇒ FAIL 并列出新增最多的文件；**低于基线同样 FAIL**（E-R3-5）：改进必须显式锁进基线
 *   （把脚本内基线常量更新为新的实测值后重跑）——「只提示」会让棘轮静默漂移：基线永远停在
 *   历史值，后面的人看到的余量是虚的，等于变相放松「只减不增」。等于基线 ⇒ 绿。
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

/**
 * 基线取「剥注释后」的真实计数。2026-11-06 接注释剥离时一并下调：
 * admin hex3 47→40；mp hex6 1048→1026、hex3 79→69、rgba 166→161（合计 44 处伪命中来自注释文字）。
 */
const BASELINES = {
  // 2026-11-10 上调冻结：商户端 UI 重设计（6f7c3ba8：柜机列表卡/盘点/订单/补货/详情）引入了
  // 一批语义化前的裸色（hex6 +52、hex3 +2、rgba +3），先冻结在当前实测值以放行；后续应逐步令牌化，
  // 本棘轮仍「只减不增」——再增会红，逐批令牌化后请同步下调。
  // 2026-11-11 E-R3-5 加固（低于基线由提示改 FAIL）后重冻结：hex6 1078→1076、rgba 164→163
  //（HEAD 实测，既有令牌化改进显式锁进基线）；admin 四项实测与基线恰好相等，不动。
  // 2026-11-11 晚再冻结：consumer-mp 移除震动反馈开关时删掉了 `color="#0d9488"`（hex6 1076→1075），
  //  属真实减少 ⇒ 显式下调基线，保持「低于基线即 FAIL」的判据有效。
  // 2026-11-13 判据校准重冻结：var(--token, #hex) 令牌兜底剥离（P2-2/mp-css-token-fallback
  //  强制形态，不是裸色选择）后实测——admin hex6 239→188 / hex3 40→19 / rgba 144→143；
  //  mp hex6 1075→205 / hex3 71→8 / rgba 163→161。数百处伪命中原为令牌兜底语法，
  //  剩余为真裸色，棘轮继续只减不增。
  admin: { hex6: 188, hex3: 19, hex8: 1, rgba: 143 },
  mp: { hex6: 205, hex3: 8, hex8: 0, rgba: 161 }
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

/**
 * 计数前剥注释：门禁量的是「代码里的裸色值」，注释里的 `#107`、`<!-- #fff -->` 不是色值。
 * 2026-11-06 实测：两处 `lessons #107` 注释把 mp.hex3 顶到 81 > 基线 79，全仓注释内还有
 * 28 处同类伪命中 ⇒ 不剥注释时，任何人写一句带 `#数字` 的留痕都会把这条棘轮判红。
 * 🔴 `//` 只在**前面不是冒号**时才算行注释起点：否则 `url(https://cdn/x.png) #ffffff`
 * 会被从 `//` 起整行吃掉，连带漏计后面的真色值（selftest 的 d.vue 专门钉这一条）。
 */
const HTML_COMMENT_RE = /<!--[\s\S]*?-->/g;
const BLOCK_COMMENT_RE = /\/\*[\s\S]*?\*\//g;
const LINE_COMMENT_RE = /(^|[^:])\/\/[^\n]*/gm;

function stripComments(text) {
  return (
    text
      .replace(HTML_COMMENT_RE, ' ')
      // 块注释先于行注释剥：`// 见 /* ... */` 这类嵌套写法不留死角
      .replace(BLOCK_COMMENT_RE, ' ')
      .replace(LINE_COMMENT_RE, '$1')
  );
}

/**
 * 令牌兜底剥离：`var(--token, #hex)` / `var(--token, rgba(...))` 的兜底色值不算裸色。
 * 2026-11-13 实测：商户端 video.vue 新增 `color: var(--text-muted, #64748b)` 把 mp.hex6
 * 顶到 1076 > 基线 1075 —— 该形态是 P2-2 / mp-css-token-fallback 门禁**强制要求**的
 * WXSS 兜底写法（小程序不认 :root，令牌未注入时不能变成黑块），不是裸色选择；
 * 与本门禁头注释「含 var(...) 的不算」的既有意图对齐（此前只对 rgba(var(--x)) 兑现）。
 * 🔴 必须在剥注释**之后**、计数之前剥离；兜底里嵌 var()（var(--a, var(--b))）不含字面色值，自然不受影响。
 */
const VAR_FALLBACK_COLOR_RE = /var\(--[A-Za-z0-9-]+\s*,\s*(?:#[0-9a-fA-F]{3,8}|rgba?\([^()]*\))\s*\)/g;

/** 统计一段文本的四类裸色值（先剥注释与令牌兜底，再匹配）。 */
export function countColors(text) {
  const code = stripComments(String(text)).replace(VAR_FALLBACK_COLOR_RE, 'VAR_TOKEN_FALLBACK');
  return {
    hex6: (code.match(HEX6_RE) || []).length,
    hex3: (code.match(HEX3_RE) || []).length,
    hex8: (code.match(HEX8_RE) || []).length,
    rgba: (code.match(RGB_RE) || []).length
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

/**
 * 返回低于基线的指标名（E-R3-5：棘轮双向锁）。低于基线=有人改进了却没锁进基线 ⇒
 * 基线与现实的偏差本身就是「棘轮没咬合」，必须红，逼着显式重冻结。
 */
function underages(counts, baselines) {
  return METRICS.filter((m) => counts.total[m] < baselines[m]);
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
    'c.css': '#FFFFFF #ABC #12345678',
    // 注释里的 `#107`/`#fff`/`#ABC`/`#123456` 一律不算；URL 里的 `//` 不得吃掉后面的真色值
    'd.vue':
      '/* 见 lessons #107 与 #fff 的说明 */\n' +
      'background: url(https://cdn.example.com/a.png) #ffffff; // 尾注 #123456 不算\n' +
      '<!-- 模板注释里的 #ABC 也不算 -->',
    // 令牌兜底不算裸色（P2-2 强制形态）；rgba(var(--x), .2) 的 var() 用法也不算
    'e.vue': 'color: var(--text-muted, #64748b);\nbackground: rgba(var(--x), 0.2);\nborder: var(--x, rgba(0, 0, 0, 0.2));'
  };
  const agg = totalOf(
    Object.fromEntries(Object.entries(fake).map(([id, text]) => [id, countColors(text)]))
  );
  if (agg.hex6 !== 4) fail(`selftest: hex6 应为 4（var() 兜底不算），实际 ${agg.hex6}`);
  if (agg.hex3 !== 2) fail(`selftest: hex3 应为 2，实际 ${agg.hex3}`);
  if (agg.hex8 !== 1) fail(`selftest: hex8 应为 1，实际 ${agg.hex8}`);
  if (agg.rgba !== 1) fail(`selftest: rgba 应为 1（var() 用法与 var() 兜底都不算），实际 ${agg.rgba}`);

  // 剥注释三态各自钉死：剥多了（URL 吃掉真色值 ⇒ hex6 掉到 0）与剥少了（注释里的算进来）都要红。
  const d = countColors(fake['d.vue']);
  if (d.hex6 !== 1 || d.hex3 !== 0 || d.hex8 !== 0) {
    fail(
      `selftest: 注释剥离判据漂移（d.vue 应 hex6=1/hex3=0/hex8=0，实际 hex6=${d.hex6} hex3=${d.hex3} hex8=${d.hex8}）`
    );
  }

  // 负向：超基线必须报出、等于基线不得误报、components 路径必须能被识别。
  const above = { total: { hex6: 2, hex3: 0, hex8: 0, rgba: 0 } };
  if (!overages(above, { hex6: 1, hex3: 0, hex8: 0, rgba: 0 }).includes('hex6')) {
    fail('selftest: 超基线未报出');
  }
  if (overages(above, above.total).length !== 0) fail('selftest: 等于基线误报');
  // E-R3-5 双向锁的负向自检：低于基线必须报出、等于基线不得误报。
  if (!underages(above, { hex6: 3, hex3: 0, hex8: 0, rgba: 0 }).includes('hex6')) {
    fail('selftest: 低于基线未报出（underages）');
  }
  if (underages(above, above.total).length !== 0) fail('selftest: 等于基线误报（underages）');
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
    // R5-E5：basename 严格相等，防 *theme.css 同族文件整文件误豁免
    .filter((f) => !excludes.some((e) => f.split(/[\\/]/).pop() === e));
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
  // E-R3-5：低于基线不再是提示 —— 棘轮双向锁。改进（哪怕是真的）没锁进基线，
  // 基线就是假的：下次有人加色，余量判断建立在虚构的历史值上。
  for (const metric of underages(counts, BASELINES[scope])) {
    bad = true;
    const base = BASELINES[scope][metric];
    const now = counts.total[metric];
    report.push(
      `裸色数低于基线 ${now}<${base}（${scope}.${metric}）：改进必须显式锁进基线` +
        '（跑冻结命令更新后重跑）'
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
console.log(
  `${TAG} OK（棘轮双向锁：全部指标恰好等于冻结基线）：${summary}` +
    '（改进后请把脚本内基线更新为新实测值再重跑——低于基线同样红）'
);
