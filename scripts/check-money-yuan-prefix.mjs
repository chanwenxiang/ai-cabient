#!/usr/bin/env node
/**
 * 金额展示「货币符号重复 / 剥符号手术」门禁。
 *
 * 背景（2026-10-16 三端单位审计）：三端有两类金额格式化函数，长得一模一样、语义相反：
 *   A. **自带 `¥`**：`fmtMoney`（shared-uni）、`yuanText` / `yuanTextZero` / `yuanTextNone`（display.ts）
 *      → 输出 `¥12.34`，负数 `-¥5.00`
 *   B. **只出数字**：各页历史的本地 `money()`（`(cents/100).toFixed(2)`）、`centsToYuanDigits()`
 *      → 输出 `12.34`
 * 模板里写字面 `¥` 配 A 就渲染成 `¥¥12.34`（负额 `¥-¥5.00`），配 B 才是对的。
 * 当时商户端账单页 10 处、结算页 6 处、运营台若干处都是 A 类重复，靠人眼发现 ⇒ 会长回来。
 *
 * 所以本门禁**按绑定来源判定**：先从每个文件解析出该标识符到底属于 A 还是 B，
 * 再只对「字面 ¥ + A 类调用」报错。不做全局字符串匹配 —— 那会把 B 类的正确写法
 * 一并误报（第一版就这么误报了 27 处，故改为解析绑定）。
 *
 * 另一条永远违规的形态：`money(x).replace(/^¥/, '')`。锚定 `^` 的正则对负数（`-¥5.00`）
 * 根本不匹配 ⇒ "去符号"变成"只有正数去了符号"，且最容易被人复制成真正的换算 bug。
 *
 * 正解：A 类 → 模板只写 `{{ fmtMoney(x) }}`；需要不带符号的元数字 → `centsToYuanDigits(x)`。
 *
 *   node scripts/check-money-yuan-prefix.mjs
 */
import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const TAG = '[check-money-yuan-prefix]';

const APPS = ['admin-vue', 'merchant-mp', 'consumer-mp'];
const SKIP_DIR = new Set(['node_modules', 'dist', 'unpackage', '.git', 'coverage']);

/** 至少扫到这么多 .vue，否则视为目录布局变了、门禁本身失效。 */
const MIN_FILES = 50;

/** A 类（自带 ¥）在 import 里的原名。 */
const EMITTING_IMPORTED = /^yuanText/; // display.ts: yuanText / yuanTextZero / yuanTextNone
const EMITTING_SHARED = new Set(['fmtMoney']); // shared-uni/format.ts

/** 插值体：允许一层内嵌花括号，才能兜住 `` {{ `¥${fmtMoney(x)}` }} ``。 */
const INTERP_BODY = '(?:[^{}]|\\{[^{}]*\\})*';
/** 字面 ¥ 紧跟插值：`¥{{ money(x) }}`、`¥ {{ a ? fmtMoney(b) : '' }}`。 */
const YEN_THEN_INTERP = new RegExp(`¥\\s*\\{\\{(${INTERP_BODY})\\}\\}`, 'g');
/** 整段插值（用于识别体内再拼 ¥ 的写法）。 */
const ANY_INTERP = new RegExp(`\\{\\{(${INTERP_BODY})\\}\\}`, 'g');
const INTERP_CALL = /([A-Za-z_$][\w$]*)\s*\(/g;
/**
 * 🔴 脚本侧（`<script setup>` 里的 JS 模板串 / 字符串相加）：这两类**不在 `{{ }}` 里**，
 * 上面两条永远看不到（2026-10-17 实测漏掉 3 个提现/退款确认弹窗共 7 处 `¥${yuan(x)}`，
 * 页面渲染成 `¥¥100.00` 而门禁全绿）。
 * 只认「紧跟 `${` 的调用」或「`'¥' +` 后的调用」，`¥${v.toFixed(2)}`、`¥${Number(x)}` 这类
 * 手写元换算仍是正确写法，由绑定集合（A 类才报）放行。
 */
const YEN_THEN_JS_TEMPLATE = /¥\s*\$\{\s*([A-Za-z_$][\w$]*)\s*\(/g;
const YEN_PLUS_JS_CALL = /['"]¥['"]\s*\+\s*([A-Za-z_$][\w$]*)\s*\(/g;

/**
 * 全文判定：返回 `[{ line, name }]` —— 「字面 ¥ 被加到 A 类（自带 ¥ 的）调用上」的每一处。
 * 两种形态都算：¥ 写在插值外面、¥ 作为字符串拼在插值里面。
 *
 * 🔴 必须**整文件**扫而非逐行：模板被 prettier 折行后，`¥{{` 与调用会落在两行。
 * （逐行版实测漏掉 PromotionsView 剩余预算那一处，2026-10-16 发现。）
 */
function findDuplicateYen(src, emitting) {
  if (emitting.size === 0) return [];
  const out = [];
  const seen = new Set();
  const push = (index, name) => {
    const line = src.slice(0, index).split('\n').length;
    const key = `${line}:${name}`;
    if (seen.has(key)) return;
    seen.add(key);
    out.push({ line, name });
  };
  for (const interp of src.matchAll(YEN_THEN_INTERP)) {
    for (const call of interp[1].matchAll(INTERP_CALL)) {
      if (emitting.has(call[1])) push(interp.index, call[1]);
    }
  }
  // ``{{ `¥${fmtMoney(x)}` }}`` / ``{{ '¥' + fmtMoney(x) }}``：¥ 拼在调用**前面**才算重复。
  // 放在调用后面的是 `.replace('¥', '')` 剥符号参数，属另一类写法，不在这里报。
  for (const interp of src.matchAll(ANY_INTERP)) {
    const body = interp[1];
    for (const call of body.matchAll(INTERP_CALL)) {
      if (emitting.has(call[1]) && body.slice(0, call.index).includes('¥')) {
        push(interp.index, call[1]);
      }
    }
  }
  // 脚本侧：`` `…¥${yuan(x)}…` `` 与 `'¥' + yuan(x)`（ElMessageBox / ElMessage 文案最常踩）
  for (const js of src.matchAll(YEN_THEN_JS_TEMPLATE)) {
    if (emitting.has(js[1])) push(js.index, js[1]);
  }
  for (const js of src.matchAll(YEN_PLUS_JS_CALL)) {
    if (emitting.has(js[1])) push(js.index, js[1]);
  }
  return out;
}

/** 靠剥前缀拼字符串，负额会漏剥 ⇒ 永远不允许。 */
const YEN_STRIPPING = /\.replace\(\s*\/\^¥\/\s*,\s*''\s*\)/;

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

/**
 * 解析本文件里「输出自带 ¥」的金额函数名。
 * 依据：A 类 import（含 `as` 别名）+ 函数体里出现 ¥ 字面量的本地函数/箭头函数。
 */
function collectYenEmittingNames(src) {
  const names = new Set();

  for (const spec of src.matchAll(/import\s*\{([^}]*)\}\s*from\s*'[^']*'/g)) {
    for (const raw of spec[1].split(',')) {
      const entry = raw.trim();
      if (!entry) continue;
      const [original, alias] = entry.split(/\s+as\s+/).map((s) => s.trim());
      const finalName = alias || original;
      if (EMITTING_IMPORTED.test(original) || EMITTING_SHARED.has(original)) {
        names.add(finalName);
      }
    }
  }

  // 本地 function foo(...) { ... }：体内出现 ¥ 即 A 类（允许 TS 返回类型标注 `): string {`）
  for (const m of src.matchAll(
    /function\s+([A-Za-z_$][\w$]*)\s*\([^)]*\)\s*(?::[^{]*)?\{([\s\S]{0,300}?)\n\}/g
  )) {
    if (m[2].includes('¥')) names.add(m[1]);
  }
  // 本地 const foo = (...) => `¥...`
  for (const m of src.matchAll(
    /(?:const|let)\s+([A-Za-z_$][\w$]*)\s*=\s*\([^)]*\)\s*=>([^\n;]*)/g
  )) {
    if (m[2].includes('¥')) names.add(m[1]);
  }
  return names;
}

/* ------------------------------------------------------------------ *
 * 自检：先证明检测器本身是对的，再拿去判别人的代码。
 * ------------------------------------------------------------------ */
function selfCheck() {
  const sampleEmitting = [
    // A 类：import yuanTextZero as yuan ⇒ yuan 自带 ¥
    ["import { yuanTextZero as yuan } from '@/utils/display';\n", ['yuan']],
    // A 类：fmtMoney
    ["import { fmtMoney } from '@aicabinet/shared-uni/format';\n", ['fmtMoney']],
    // B 类：本地 money 返回纯数字 ⇒ 不该进集合
    ['function money(cents?: number) {\n  return ((cents || 0) / 100).toFixed(2);\n}\n', []],
    // A 类：本地函数体里带 ¥ ⇒ 该进集合
    [
      'function yuan(cents: number): string {\n  return `¥${(cents / 100).toFixed(2)}`;\n}\n',
      ['yuan']
    ]
  ];
  for (const [src, expected] of sampleEmitting) {
    const got = [...collectYenEmittingNames(src)].sort();
    if (got.join(',') !== [...expected].sort().join(',')) {
      console.error(`${TAG} FAIL: 绑定解析自检不通过\n  期望 ${expected}\n  实际 ${got}`);
      process.exit(1);
    }
  }

  const stripCases = [
    [`money(gross).replace(/^¥/, ''),`, true],
    [`gross: money(gross),`, false]
  ];
  for (const [line, expected] of stripCases) {
    if (YEN_STRIPPING.test(line) !== expected) {
      console.error(`${TAG} FAIL: 剥前缀自检不通过（expected=${expected}）: ${line}`);
      process.exit(1);
    }
  }

  // 判定函数：只有「字面 ¥ 落在 A 类调用上」才报，B 类必须放行
  const yenCallCases = [
    ['<text>¥{{ money(x) }}</text>', [], null], // B 类调用 ⇒ 正确写法
    ['<text>¥{{ yuan(x) }}</text>', ['yuan'], 'yuan'],
    ['<text>¥ {{ fmtMoney(row.a) }}</text>', ['fmtMoney'], 'fmtMoney'],
    ['<text>¥{{ a > b ? yuanTextZero(x) : "" }}</text>', ['yuanTextZero'], 'yuanTextZero'],
    ['<text>{{ `¥${fmtMoney(x)}` }}</text>', ['fmtMoney'], 'fmtMoney'],
    ["<text>{{ fmtMoney(x).replace('¥', '') }} 元</text>", ['fmtMoney'], null], // 剥符号在调用之后
    ['<text>可用余额 ≥ {{ needYuan }}</text>', ['fmtMoney'], null], // ¥ 不在插值旁
    ['<text>{{ money(x) }}</text>', ['fmtMoney'], null],
    ['<text>¥{{ centsToYuanDigits(x) }}</text>', ['fmtMoney'], null], // B 类，靠绑定集合放行
    // 折行：`¥{{` 与调用分在两行（prettier 会这么排），逐行版漏报过
    ['<span>¥{{\n  yuan(Math.max(0, row.budgetCents))\n}}</span>', ['yuan'], 'yuan'],
    // 脚本侧（无 {{ }}）：这两类是 2026-10-17 实测的漏报形态
    ['const t = `确认打款 ¥${yuan(row.amountCents)}？`', ['yuan'], 'yuan'],
    ["const t = '打款 ' + '¥' + yuan(row.amountCents)", ['yuan'], 'yuan'],
    // 手写元换算（B 类不在绑定集合里）必须放行，否则真实代码里 15 处全炸
    ['const t = `¥${v.toFixed(2)}`', ['yuan'], null],
    ['const t = `合计 ¥${money(total)}`', ['yuan'], null],
    ['const t = `¥${Number(v).toFixed(2)}`', ['yuan'], null]
  ];
  for (const [src, emitting, expected] of yenCallCases) {
    const got = findDuplicateYen(src, new Set(emitting)).map((d) => d.name);
    const joined = got.join(',');
    const want = expected === null ? '' : expected;
    if (joined !== want) {
      console.error(
        `${TAG} FAIL: ¥ 重复判定自检不通过\n  ${src}\n  期望 ${want || '(无)'} 实际 ${joined || '(无)'}`
      );
      process.exit(1);
    }
  }
  // 行号必须指向 `¥{{` 那一行，否则报错位置没法用
  const lineProbe = findDuplicateYen('a\nb\n<span>¥{{\n  yuan(c)\n}}</span>', new Set(['yuan']));
  if (lineProbe.length !== 1 || lineProbe[0].line !== 3) {
    console.error(`${TAG} FAIL: 折行命中的行号应为 3，实际 ${JSON.stringify(lineProbe)}`);
    process.exit(1);
  }
}
selfCheck();

/* ------------------------------------------------------------------ *
 * 扫描
 * ------------------------------------------------------------------ */
const files = APPS.flatMap((app) => walk(join(root, 'clients', app, 'src')));
if (files.length < MIN_FILES) {
  console.error(
    `${TAG} FAIL: 只扫到 ${files.length} 个 .vue（< ${MIN_FILES}），目录布局可能变了，门禁已失效`
  );
  process.exit(1);
}

const hits = [];
for (const file of files) {
  const src = readFileSync(file, 'utf8');
  const emitting = collectYenEmittingNames(src);
  const rel = relative(root, file);
  src.split(/\r?\n/).forEach((line, i) => {
    if (YEN_STRIPPING.test(line)) {
      hits.push(`剥 ¥ 前缀拼字符串（负额漏剥）\n  ${rel}:${i + 1}\n    ${line.trim()}`);
    }
  });
  for (const dup of findDuplicateYen(src, emitting)) {
    const line = src.split(/\r?\n/)[dup.line - 1] || '';
    hits.push(
      `货币符号重复（模板补 ¥ + ${dup.name}() 已自带 ¥）\n  ${rel}:${dup.line}\n    ${line.trim()}`
    );
  }
}

if (hits.length) {
  console.error(`${TAG} FAIL: ${hits.length} 处金额展示写法有问题\n`);
  for (const hit of hits) console.error(hit);
  console.error(
    '\n修法：模板里去掉字面 ¥，直接 {{ fmtMoney(x) }} / {{ yuan(x) }}；' +
      '需要不带符号的元数字用 centsToYuanDigits(x)。'
  );
  process.exit(1);
}

console.log(`${TAG} OK (${files.length} 个 .vue：无重复货币符号、无剥前缀手术)`);
