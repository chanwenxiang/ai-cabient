#!/usr/bin/env node
/**
 * 门禁：E2E/UAT 套件里用到的 CSS 类选择器，必须在被它驱动的那一端源码里真实存在。
 *
 * 为什么需要这道门禁
 * ------------------
 * `TC-BAL-001` 曾在余额明细页断言 `.transaction-row` / `.transaction-more`，而重构后的
 * 真实类名是 `.log-row` / `.more`（clients/consumer-mp/src/pages/balance/balance.vue）。
 * 两个选择器都取不到 → 用例恒为 `rows=0 more=false`，**结构上永远不可能 PASS**；
 * 但它被计入 `UAT_MAX_FAIL_CONSUMER` 基线，于是每个失败额度都被一条假红占着，
 * 余额区域的真实回归反而可以被静默吃掉。同类问题还有商家争议详情的
 * `.detail-panel` / `.detail-mask`（重构为 AppSheet 组件后应为 `.app-sheet` /
 * `.app-sheet-mask`）。
 *
 * 判据
 * ----
 * 每个套件声明它驱动哪个前端（见 SUITES）；把这些前端源码 + packages/ 共享层里
 * **元素真的可能带上的类名**收集成一个集合（模板 class / 动态 :class / 表格列类名 /
 * data-testid），套件里出现的每个类选择器都必须命中该集合。
 *
 * 🔴 反面教材（本闸门曾经漏掉的形态）：判据第一版把「文件里出现过的任意 `.xxx`」
 * 当作可用类名，于是 CSS 死规则与 JS 属性访问（`foo.mask`）都算数。
 * 实测 `App.vue` 遗留死规则 `.sheet .app-btn` 让 `.sheet` 变成"存在"，
 * 而 `merchant-h5-uat.mjs` 的 `document.querySelector('.sheet')` 恒取不到元素
 * （重构后的真类名是 `.app-sheet`）→ 用例永久假红、白占基线额度，闸门却全绿。
 * **可用集合的来源必须与"元素能否带上它"同源**，否则闸门会变成空管子。
 *
 * 例外
 * ----
 * 第三方/框架运行时类名不在源码里，按前缀放行（FRAMEWORK_PREFIXES）。
 * 新增框架前缀必须写清来源，否则门禁会变成"什么都放行"的空管子。
 *
 * 选择器「组」语义（2026-09-20 第二轮加固）
 * ----------------------------------------
 * 逗号分隔的选择器是**回退链**：`'.el-aside, .layout-aside, .sidebar'` 只要**任一分支**
 * 命中就能取到元素。原实现把整串拆成逐个类名、要求**全部**存在 ⇒ 回退链里那些
 * 「匹配不到但无所谓」的备选名被报成「定位永远取不到元素」，与本门禁自己的判据不符（假红）。
 * ⇒ 现在按**组**判定：组内 ≥1 个分支的类名全部命中 ⇒ 通过；**整组全死**才报红。
 *   （单分支组＝原语义：`.sheet` 这类独立选择器照旧必红。）
 *   ⚠️ 这不是放宽 —— 含回退链的那 4 个套件原先**整个没被本门禁覆盖**（见下「自动跟随」），
 *    现在它们的分支会被真检查、只有整组失效才红 ⇒ 严格强于原状。
 *   ⚠️ 反面教训：`fillElInput(page, placeholder, value)` 这类 helper 内部的
 *    `` `.el-input input[placeholder="${placeholder}"]` `` 是**模板插值**，静态无从判定，
 *    原实现把它当字面量 `${placeholder}` 报红（假红）⇒ 现在含 `${` 的一律跳过。
 *
 * 🔴 自动跟随（同一轮新增）
 * ------------------------
 * `SUITES` 曾是**手写清单**：新增 `clients/*\/tests/*-uat.mjs` 不会自动进闸门。
 * 实测漏洞（2026-09-20）：磁盘 9 个套件，`SUITES` 只映射 5 个 —— `admin-uat` /
 * `role-regression-uat` / `batch-imp-uat`（**21 处类选择器**）/ `admin-alert-channel-uat`
 * 长期**零校验**。⇒ 现在双向扫：磁盘上的每个 `*-uat.mjs` 必须出现在 `SUITES`；
 * `SUITES` 里的每个条目也必须仍在磁盘上（重命名后不留僵尸条目）。
 * 确实无法静态校验的套件须登记进 `UNMAPPED`（附可核对的理由）。
 *
 * 用法：node scripts/check-uat-selectors.mjs
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

/** 套件 → 它驱动的源码根（相对仓库根）。 */
const SUITES = [
  {
    file: 'clients/consumer-mp/tests/consumer-h5-uat.mjs',
    roots: ['clients/consumer-mp/src', 'packages']
  },
  {
    // 该套件同时驱动消费者端（cpage）与商家端（mpage），两个源码根都要收
    file: 'clients/consumer-mp/tests/imp-dispute-copy-uat.mjs',
    roots: ['clients/consumer-mp/src', 'clients/merchant-mp/src', 'packages']
  },
  {
    file: 'clients/merchant-mp/tests/merchant-h5-uat.mjs',
    roots: ['clients/merchant-mp/src', 'packages']
  },
  {
    file: 'clients/admin-vue/tests/three-end-business-uat.mjs',
    roots: ['clients/admin-vue/src', 'packages']
  },
  {
    file: 'clients/admin-vue/tests/three-end-dispute-ui-uat.mjs',
    roots: ['clients/admin-vue/src', 'packages']
  },
  // —— 2026-09-20 补齐：这 4 个 admin 套件此前**整个未被本门禁覆盖**（不是有意豁免，是遗漏）。
  //    `batch-imp-uat.mjs` 单文件就有 21 处类选择器在裸奔。
  { file: 'clients/admin-vue/tests/admin-uat.mjs', roots: ['clients/admin-vue/src', 'packages'] },
  {
    file: 'clients/admin-vue/tests/role-regression-uat.mjs',
    roots: ['clients/admin-vue/src', 'packages']
  },
  {
    file: 'clients/admin-vue/tests/batch-imp-uat.mjs',
    roots: ['clients/admin-vue/src', 'packages']
  },
  {
    file: 'clients/admin-vue/tests/admin-alert-channel-uat.mjs',
    roots: ['clients/admin-vue/src', 'packages']
  }
];

/**
 * 确实无法静态校验的套件 → 理由（必须可核对）。当前为空。
 *
 * 纪律：只放「它驱动的前端源码不在本仓库」这类**客观不可校验**的套件；
 * 「源码根写起来麻烦」不构成理由 —— 那是 SUITES 该补的活（补齐 4 个 admin 套件
 * 就是一次这类活）。空表是正常状态：说明 9/9 套件都在被校验。
 */
const UNMAPPED = new Map();

/** 发现 `clients/*\/tests/*-uat.mjs`（发现式，避免手写清单悄悄落后于磁盘）。 */
function discoverSuites() {
  const out = [];
  const clientsDir = path.join(ROOT, 'clients');
  if (!fs.existsSync(clientsDir)) return out;
  for (const client of fs.readdirSync(clientsDir, { withFileTypes: true })) {
    if (!client.isDirectory()) continue;
    const testsDir = path.join(clientsDir, client.name, 'tests');
    if (!fs.existsSync(testsDir)) continue;
    for (const f of fs.readdirSync(testsDir)) {
      if (/-uat\.mjs$/.test(f)) out.push(`clients/${client.name}/tests/${f}`);
    }
  }
  return out.sort();
}

/** 运行时才注入的第三方/框架类名，源码里查不到属正常。前缀 → 来源说明。 */
const FRAMEWORK_PREFIXES = [
  ['uni-', 'uni-app 运行时内部类（uni-input / uni-modal 等）'],
  ['el-', 'Element Plus 运行时类（admin 端组件库）'],
  ['van-', 'Vant 运行时类（若后续引入）'],
  ['u-', 'uview 运行时类（若后续引入）']
];

const SRC_EXT = new Set(['.vue', '.ts', '.js', '.css', '.scss', '.html']);
const SKIP_DIR = /(^|[\\/])(node_modules|output|dist|unpackage)[\\/]/;

function walk(dir, out = []) {
  if (!fs.existsSync(dir)) return out;
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, entry.name);
    if (SKIP_DIR.test(p)) continue;
    if (entry.isDirectory()) walk(p, out);
    else if (SRC_EXT.has(path.extname(entry.name))) out.push(p);
  }
  return out;
}

/**
 * 从源码文本里收集「**元素真的可能带上**的类名 token」。
 *
 * 🔴 这里**不能**用「文件里出现过的任意 `.xxx`」。第一版就是这么写的，于是：
 *   - CSS 里的**死规则**（如 `App.vue` 遗留的 `.sheet .app-btn` —— AppSheet 重构后
 *     再没有任何元素带 `sheet` 类）会被当成"该类名存在"；
 *   - JS 属性访问/对象键（`foo.mask`、`{ mask: false }`）同理。
 *   ⇒ 闸门被自己的"可用集合"污染，恰恰**抓不到它本来要抓的那类缺陷**。
 *     实测：`merchant-h5-uat.mjs` 里 `document.querySelector('.sheet')` 恒取不到元素
 *     （真类名是 `.app-sheet`），用例永久假红，而本闸门一路绿灯。
 *
 * 收集来源（都能让元素真的带上该类名）：
 *  1. 静态 class        class="a b c"
 *  2. 动态 class 表达式  :class="..." / v-bind:class="..."（对象键、字符串字面量、三元分支）
 *  3. 组件透传的列类名    class-name="x" / label-class-name="x"（Element Plus 表格列）
 *  4. data-testid      元素上的测试锚点
 * 用 token 集合（而非子串）比对，避免 `.more` 被任意含 "more" 的单词蒙混过关。
 */
function collectClassTokens(text) {
  const out = new Set();

  const addAttr = (m) => {
    for (const t of m[1].split(/\s+/)) {
      if (/^[A-Za-z][A-Za-z0-9_-]*$/.test(t)) out.add(t);
    }
  };
  // 1. 静态 class（含 class-name / label-class-name 这类后缀属性）
  for (const m of text.matchAll(/\b(?:class|class-name|label-class-name)\s*=\s*"([^"]*)"/g))
    addAttr(m);
  for (const m of text.matchAll(/\b(?:class|class-name|label-class-name)\s*=\s*'([^']*)'/g))
    addAttr(m);

  // 2. 动态 class 表达式：对象键 { 'a-b': cond }、字符串分支 'is-online'、数组元素
  for (const m of text.matchAll(/(?::|v-bind:)class\s*=\s*"([^"]*)"/g)) {
    const expr = m[1];
    for (const k of expr.matchAll(/['"]?([A-Za-z][A-Za-z0-9_-]*)['"]?\s*:/g)) out.add(k[1]);
    for (const s of expr.matchAll(/['"]([A-Za-z][A-Za-z0-9_-]*)['"]/g)) out.add(s[1]);
  }
  for (const m of text.matchAll(/(?::|v-bind:)class\s*=\s*'([^']*)'/g)) {
    for (const s of m[1].matchAll(/['"]([A-Za-z][A-Za-z0-9_-]*)['"]/g)) out.add(s[1]);
  }

  // 4. data-testid
  for (const m of text.matchAll(/data-testid\s*=\s*["']([^"']+)["']/g)) out.add(m[1]);
  return out;
}

/**
 * 遮掉注释，**长度不变**（注释字符换成空格、换行保留），这样行号仍与原文对齐。
 *
 * 为什么需要：判据应只看「代码里用了什么选择器」，而**注释里提到**某个已删除的选择器
 * 是合理的文档（本项目习惯把踩过的坑写在原地）。实测 `M-09b` 的说明注释里写了
 * `document.querySelector('.sheet')`，于是这条**已被修复**的选择器又被门禁报红 ——
 * 假报错会消耗对门禁的信任，最终导致门禁被绕过；而真正在骗人的判据反而留下。
 */
function stripComments(src) {
  const buf = [...src];
  const blank = (from, to) => {
    for (let k = from; k < to && k < buf.length; k += 1) {
      if (buf[k] !== '\n') buf[k] = ' ';
    }
  };
  let i = 0;
  let quote = null;
  while (i < src.length) {
    const c = src[i];
    const n = src[i + 1];
    if (quote) {
      if (c === '\\') i += 2;
      else {
        if (c === quote) quote = null;
        i += 1;
      }
      continue;
    }
    if (c === '"' || c === "'" || c === '`') {
      quote = c;
      i += 1;
      continue;
    }
    if (c === '/' && n === '*') {
      const e = src.indexOf('*/', i + 2);
      const end = e === -1 ? src.length : e + 2;
      blank(i, end);
      i = end;
      continue;
    }
    if (c === '/' && n === '/') {
      const e = src.indexOf('\n', i);
      const end = e === -1 ? src.length : e;
      blank(i, end);
      i = end;
      continue;
    }
    i += 1;
  }
  return buf.join('');
}

/** 按**顶层**逗号切分选择器组：括号/引号里的逗号（`[placeholder="a,b"]`）不切。 */
function splitTopLevel(raw) {
  const out = [];
  let depth = 0;
  let quote = null;
  let cur = '';
  for (const c of raw) {
    if (quote) {
      cur += c;
      if (c === quote) quote = null;
      continue;
    }
    if (c === '"' || c === "'") {
      quote = c;
      cur += c;
      continue;
    }
    if (c === '[' || c === '(') depth += 1;
    else if (c === ']' || c === ')') depth -= 1;
    if (c === ',' && depth === 0) {
      out.push(cur);
      cur = '';
      continue;
    }
    cur += c;
  }
  out.push(cur);
  return out;
}

/**
 * 从测试脚本里提取**选择器组**（只取我们自己写的 .xxx，忽略 #id / [attr] / 标签）。
 *
 * 每个组＝一条逗号分隔的回退链。组内各分支的类名 token 分开算，判定见主循环：
 * **≥1 个分支的类名全部命中 ⇒ 组通过**（回退链语义）；整组全死才报红。
 * 含模板插值（`${...}`）的选择器静态无从判定 ⇒ 跳过（那是变量，不是字面量）。
 */
function extractSelectorGroups(text) {
  const groups = [];
  const patterns = [
    /querySelector(?:All)?\(\s*(['"])(.*?)\1/g,
    /waitForSelector\(\s*(['"])(.*?)\1/g,
    /locator\(\s*(['"])(.*?)\1/g,
    /\$\(\s*(['"])(.*?)\1/g
  ];
  for (const re of patterns) {
    for (const m of text.matchAll(re)) {
      // 反向引用配对引号：`'…[data-testid="x"]…'` 里出现的双引号不再把字面量截断
      // （原实现用两个独立引号类 `['"]…['"]`，会在内层双引号处提前收尾 ⇒ 假红）。
      const raw = m[2];
      if (raw.includes('${')) continue;
      const branches = splitTopLevel(raw)
        .map((b) => b.trim())
        .filter(Boolean)
        .map((b) => ({
          raw: b,
          tokens: [...b.matchAll(/\.([A-Za-z][A-Za-z0-9_-]*)/g)].map((c) => c[1])
        }));
      groups.push({ raw, line: text.slice(0, m.index).split('\n').length, branches });
    }
  }
  return groups;
}

// ---------------------------------------------------------------------------
// placeholder 校验
//
// 同一类失效还有第二种形态：`fillPlaceholder(page, '例如 CAB-001', ...)`
// 里的字符串**在目标页面根本不存在**。`TC-RPT-002` 就是这样：它填的是 admin 端
// SkuVisionEnrollView 的 placeholder，而 consumer 报修页的 placeholder 是
// 「请输入柜机编号」→ 输入框永远填不进值 → 用例恒红、白占基线额度；
// `TC-FB-002` 更隐蔽：该字段是选填，填不进去也不报错，于是那一步**静默空转没人发现**。
// 所以 placeholder 字面量和 CSS 类一样，必须能在被驱动的源码里找到。
// ---------------------------------------------------------------------------

/** 收集源码里声明的 placeholder 字面量（含 :placeholder 三元表达式内的字符串）。 */
function collectPlaceholderTokens(text) {
  const out = new Set();
  for (const m of text.matchAll(/(?<!:)placeholder\s*=\s*(["'])([\s\S]*?)\1/g)) {
    const value = m[2];
    const inner = [...value.matchAll(/['"]([^'"]*)['"]/g)].map((x) => x[1]);
    if (inner.length) for (const s of inner) out.add(s);
    else out.add(value);
  }
  // 部分组件库用对象/配置声明 placeholder: '...'
  for (const m of text.matchAll(/(?<![\w:$])placeholder\s*:\s*(['"])([^'"]*)\1/g)) out.add(m[2]);
  return out;
}

/** 从测试脚本里提取被断言/填充的 placeholder 字面量。 */
function extractPlaceholders(text) {
  const found = new Map(); // 字符串 -> 行号
  const patterns = [
    /fillPlaceholder\(\s*\w+\s*,\s*['"]([^'"]+)['"]/g,
    /\[placeholder\s*=\s*\\?["']([^"'\\]+)/g
  ];
  const lineOf = (idx) => text.slice(0, idx).split('\n').length;
  for (const re of patterns) {
    for (const m of text.matchAll(re)) {
      // 模板插值（`[placeholder="${placeholder}"]`）是**变量**不是字面量，静态无从判定。
      // 不跳过的话 `fillElInput(page, placeholder, value)` 这类 helper 会稳定假红。
      if (m[1].includes('${')) continue;
      if (!found.has(m[1])) found.set(m[1], lineOf(m.index));
    }
  }
  return found;
}

function isFrameworkClass(name) {
  return FRAMEWORK_PREFIXES.some(([prefix]) => name.startsWith(prefix));
}

let failed = false;

// —— 自动跟随：双向扫。手写清单会悄悄落后于磁盘（实测漏了 4 个套件），必须让磁盘说话。 ——
const onDisk = discoverSuites();
const mapped = new Set([...SUITES.map((s) => s.file), ...UNMAPPED.keys()]);
for (const f of onDisk) {
  if (!mapped.has(f)) {
    failed = true;
    console.error(
      `✗ ${f}\n    未被本门禁覆盖：它的类选择器 / placeholder 处于**零校验**状态。\n` +
        `    请把驱动的前端源码根加进 SUITES；确实无法静态校验则登记进 UNMAPPED 并写明理由。`
    );
  }
}
for (const f of mapped) {
  if (!onDisk.includes(f)) {
    failed = true;
    console.error(
      `✗ ${f}\n    条目已过期：磁盘上已无此套件（重命名 / 删除后请同步 SUITES / UNMAPPED，别留僵尸条目）。`
    );
  }
}

for (const suite of SUITES) {
  const suitePath = path.join(ROOT, suite.file);
  if (!fs.existsSync(suitePath)) {
    console.error(`✗ 套件缺失：${suite.file}`);
    failed = true;
    continue;
  }

  const available = new Set();
  const placeholders = new Set();
  for (const root of suite.roots) {
    for (const file of walk(path.join(ROOT, root))) {
      const src = fs.readFileSync(file, 'utf8');
      for (const t of collectClassTokens(src)) available.add(t);
      for (const p of collectPlaceholderTokens(src)) placeholders.add(p);
    }
  }

  // 只看代码：注释里提到某个（已修复的）选择器属正常文档，不应报红
  const code = stripComments(fs.readFileSync(suitePath, 'utf8'));

  // 选择器**组**：组内 ≥1 个分支的类名全部命中 ⇒ 通过（回退链语义）；整组全死才报红。
  // 无类名的分支（#id / [attr] / 标签）本门禁判不了 ⇒ 不计入，也不算「活分支」。
  const checkedTokens = new Set();
  const deadGroups = [];
  for (const g of extractSelectorGroups(code)) {
    const branches = g.branches
      .filter((b) => b.tokens.length > 0)
      .map((b) => ({
        ...b,
        missing: b.tokens.filter((t) => !available.has(t) && !isFrameworkClass(t))
      }));
    if (!branches.length) continue;
    for (const b of branches) for (const t of b.tokens) checkedTokens.add(t);
    if (!branches.some((b) => b.missing.length === 0)) deadGroups.push({ ...g, branches });
  }

  const usedPlaceholders = extractPlaceholders(code);
  const missingPh = [...usedPlaceholders.entries()].filter(([s]) => !placeholders.has(s));

  if (deadGroups.length || missingPh.length) {
    failed = true;
    console.error(`✗ ${suite.file}`);
    for (const g of deadGroups) {
      console.error(`    选择器组 '${g.raw}'  :${g.line} —— 整组都取不到元素：`);
      for (const b of g.branches) {
        console.error(`        分支 '${b.raw}' 缺 ${b.missing.map((m) => `.${m}`).join('、')}`);
      }
    }
    for (const [s, line] of missingPh) {
      console.error(`    placeholder "${s}"  :${line} 在 ${suite.roots.join(' + ')} 中不存在`);
    }
  } else {
    console.log(
      `✓ ${suite.file}（类选择器 ${checkedTokens.size} 个、placeholder ${usedPlaceholders.size} 个全部命中）`
    );
  }
}

if (failed) {
  console.error(
    '\n::error::UAT 套件里存在源码中不存在的类选择器或 placeholder。' +
      '这类定位永远取不到元素 → 用例恒假红（选填字段则静默空转），并占用 UAT_MAX_FAIL 基线额度、掩盖真实回归。' +
      '请核对真值来源（组件模板的 class / placeholder）后修正；若为框架运行时类，请在 FRAMEWORK_PREFIXES 里登记。'
  );
  process.exit(1);
}
console.log(
  '\nPASS check-uat-selectors：所有 UAT 类选择器与 placeholder 都能在其驱动的前端源码中找到。'
);
