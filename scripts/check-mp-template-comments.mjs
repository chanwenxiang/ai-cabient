#!/usr/bin/env node
/**
 * 小程序模板「JSX 式注释」门禁（花括号注释不得出现在 .vue 模板区）。
 *
 * 背景（2026-10-08 真机截图实测）：我在 `clients/consumer-mp/src/pages/orders/orders.vue:43`
 * 用 JSX 式花括号注释写了一条成因注释（形如左花括号 + 斜杠星号 … 星号斜杠 + 右花括号）。
 * **小程序编译器不识别这种写法**，把它当**普通文本节点**编译进 wxml
 * ⇒ 整段中文成因注释被原样渲染到用户界面上，挤在档位栏与「需要关注」标题之间。
 * 产物证据：`grep '需要关注' dist/build/mp-weixin/pages/orders/orders.wxml` 命中 2 次，
 * 第一次就在注释文本里。
 * （本文件自身的注释里不能直接写出那个序列 —— 会被 JS 块注释提前闭合，见第 21 行的教训。）
 *
 * 🔴 为什么这个坑格外危险 —— **所有常规门禁都发现不了它**：
 *   - `vue-tsc --noEmit` 通过（模板闭合合法，只是语义错）；
 *   - `prettier --check` 通过（它会正确格式化 JSX 式注释）；
 *   - `vitest` 通过（不测模板文本）；
 *   - 页面在浏览器/H5 预览下**也可能正常**，只有真机 wxml 编译器暴露。
 * ⇒ 只能靠**静态扫描源码**拦住，故本门禁存在。
 *
 * 规则：`.vue` 文件的**模板区**（`<template>...</template>`）内不得出现 `{/*`。
 *
 * 为什么只扫模板区：JSX 式花括号注释在 `<script>` 里的 JS/TS 是**合法注释语法**
 *   （例如给类型做局部标注时常见），那里出现完全正常；`<style>` 里也没有这个问题。
 *   误伤 JS 会造成假红。
 *
 * 防「恒真 / 恒假」：
 *   - 扫到的 .vue 文件数 < MIN_FILES ⇒ 红（说明路径表达式已失效）；
 *   - **正向自检**：本门禁的检测正则在下面 `SELFTEST_CASES` 里用构造样本验证过 ——
 *     每条 case 必须命中/不命中与预期一致，且红因要打印出来核对，
 *     避免「用例会先被别的规则拦掉 ⇒ 这条规则从未被执行」的假通过。
 *
 *   node scripts/check-mp-template-comments.mjs
 */
import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const TAG = '[check-mp-template-comments]';

const MIN_FILES = 20;

/** 三个小程序端（admin-vue 是纯 Web 端，不受 wxml 编译器影响，故不扫）。 */
const APPS = ['consumer-mp', 'merchant-mp'];

const SKIP_DIR = new Set(['node_modules', 'dist', 'unpackage', '.git', 'coverage']);

/** 只取第一个 `<template>` 到其配对 `</template>` 之间的内容。 */
function extractTemplate(src) {
  const open = src.search(/<template(?:\s[^>]*)?>/);
  if (open < 0) return null;
  const bodyStart = src.indexOf('>', open) + 1;
  const close = src.lastIndexOf('</template>');
  if (close < bodyStart) return null;
  return src.slice(bodyStart, close);
}

function walk(dir, out = []) {
  if (!existsSync(dir)) return out;
  for (const name of readdirSync(dir)) {
    if (SKIP_DIR.has(name)) continue;
    const p = join(dir, name);
    if (statSync(p).isDirectory()) walk(p, out);
    else if (name.endsWith('.vue')) out.push(p);
  }
  return out;
}

const JSX_COMMENT = /\{\s*\/\*/;

/* ------------------------------------------------------------------ *
 * 正则自检：先证明检测器本身是对的，再拿去判别人的代码。
 * （教训：门禁的用例被别的规则先拦 ⇒ 这条规则从未被执行；故每条都要核对红因。）
 * ------------------------------------------------------------------ */
const SELFTEST_CASES = [
  {
    name: '模板区 JSX 式注释 → 必须命中',
    tpl: '<view>\n  {/* 我是注释 */}\n  <text>a</text>\n</view>',
    expectHit: true
  },
  {
    name: '模板区 HTML 注释 → 必须不命中',
    tpl: '<view>\n  <!-- ok -->\n</view>',
    expectHit: false
  },
  { name: '模板区普通文本 → 必须不命中', tpl: '<view>故障报修 / 换一台</view>', expectHit: false },
  { name: '插值 mustache → 必须不命中', tpl: '<view>{{ a }}/{{ b }}</view>', expectHit: false },
  { name: 'style 规则里含 `/*` → 必须不命中', tpl: '<view />', expectHit: false },
  { name: '模板区注释外多行 → 必须命中', tpl: '<view>\n{/*\n 多行\n*/}\n</view>', expectHit: true }
];

const selfTestFailed = [];
for (const c of SELFTEST_CASES) {
  const hit = JSX_COMMENT.test(c.tpl);
  if (hit !== c.expectHit) {
    selfTestFailed.push(
      `${c.name}：期望 ${c.expectHit ? '命中' : '不命中'}，实际 ${hit ? '命中' : '不命中'}` +
        `（红因：检测正则 /\{\s*\/\*/ 未按预期工作）`
    );
  }
}
if (selfTestFailed.length) {
  console.error(`${TAG} FAIL: 检测正则自检未通过，门禁本身不可信：`);
  for (const f of selfTestFailed) console.error(`  - ${f}`);
  process.exit(1);
}

/* --------------------------- 主检查 --------------------------- */
const problems = [];
let scanned = 0;

for (const app of APPS) {
  const appDir = join(root, 'clients', app, 'src');
  if (!existsSync(appDir)) continue;
  for (const file of walk(appDir)) {
    scanned++;
    const src = readFileSync(file, 'utf8');
    const tpl = extractTemplate(src);
    if (tpl === null) continue;
    const m = JSX_COMMENT.exec(tpl);
    if (!m) continue;
    const line = src.slice(0, src.indexOf(m[0])).split('\n').length;
    const lineNoInTpl = tpl.slice(0, m.index).split('\n').length;
    problems.push(
      `${relative(root, file).replace(/\\/g, '/')}:${line}（模板区第 ${lineNoInTpl} 行）` +
        ` —— 用了 JSX 式注释 {/* ... */}。小程序编译器会把它当**文本节点**渲染到界面上，` +
        `请改成 HTML 注释 <!-- ... -->`
    );
  }
}

if (scanned < MIN_FILES) {
  console.error(
    `${TAG} FAIL: 只扫到 ${scanned} 个 .vue 文件（期望 ≥ ${MIN_FILES}）：` +
      `APPS 路径或 walk 规则可能已变，门禁已失效`
  );
  process.exit(1);
}

if (problems.length) {
  console.error(`${TAG} FAIL: 发现 ${problems.length} 处模板区 JSX 式注释（会被真机渲染成正文）：`);
  for (const p of problems) console.error(`  - ${p}`);
  process.exit(1);
}

console.log(
  `${TAG} OK: ${APPS.length} 个小程序端、${scanned} 个 .vue 模板区均无 JSX 式注释` +
    `（正则自检 ${SELFTEST_CASES.length} 例全过）`
);
