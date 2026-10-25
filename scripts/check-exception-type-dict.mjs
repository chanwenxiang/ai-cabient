#!/usr/bin/env node
/**
 * 异常类型字典覆盖门禁（G7 §7.27 FS-G7-5）。
 *
 * 判据：运营异常台按 `exception_type` 字典渲染类型标签，而类型串来自代码字面量
 * （`opsExceptionService.report("X", …)`）——字面量不在字典里 ⇒ 异常真产了但后台显示不出来。
 * 本门禁把两侧对账：① 抽取全部 report 站点首参（含本文件/被引类里的 static final String 常量）；
 * ② 解析 SysDictBootstrap.seedCatalog() 的 exception_type 内置集（启动时 ensureMissingDictItems
 * 会对已存在库补种 ⇒ 改这里就够，不需要迁移）；③ 差集必须为空。
 *
 * 动态站点（首参是变量/方法返回值）机器无法静态求值 ⇒ 记数并钉在上限 BASELINE_DYNAMIC 上，
 * 新增动态站点会红，逼人来登记它的取值集合。
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const errors = [];

function fail(msg) {
  errors.push(msg);
}

function stripComments(src) {
  return src.replace(/\/\*[\s\S]*?\*\//g, '').replace(/(^|[^:])\/\/.*$/gm, '$1');
}

function walkJava(dir, acc = []) {
  if (!fs.existsSync(dir)) return acc;
  for (const ent of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, ent.name);
    if (ent.isDirectory()) {
      if (ent.name === 'node_modules' || ent.name === 'target') continue;
      walkJava(p, acc);
    } else if (ent.name.endsWith('.java')) acc.push(p);
  }
  return acc;
}

const srcRoots = [
  'services/trade-service/src/main/java',
  'services/user-service/src/main/java',
  'services/payment-service/src/main/java',
  'services/order-service/src/main/java',
  'common'
];
const files = [];
for (const rel of srcRoots) {
  const abs = path.join(root, rel);
  if (!fs.existsSync(abs)) continue;
  if (fs.statSync(abs).isFile() && abs.endsWith('.java')) files.push(abs);
  else walkJava(abs, files);
}

const consts = new Map();
const bodies = new Map();
for (const f of files) {
  const src = stripComments(fs.readFileSync(f, 'utf8'));
  bodies.set(f, src);
  for (const m of src.matchAll(/\bstatic\s+final\s+String\s+([A-Z][A-Z0-9_]*)\s*=\s*"([^"]+)"/g)) {
    if (!consts.has(m[1])) consts.set(m[1], m[2]);
  }
}

/** 把类型实参解析成字面量：字符串字面量直接取，标识符查 static final String 常量。 */
function resolveType(token, file) {
  const lit = /^"([^"]+)"$/.exec(token.trim());
  if (lit) return lit[1];
  const name = token.trim();
  if (!/^[A-Z][A-Z0-9_]*$/.test(name)) return null;
  const local = new RegExp(`\\bString\\s+${name}\\s*=\\s*"([^"]+)"`).exec(bodies.get(file) ?? '');
  if (local) return local[1];
  return consts.get(name) ?? null;
}

const reportSites = [];
const dynamicSites = [];
// 首参是变量/方法返回值的站点：取值集合人工枚举自源码，新增此类站点必须先来这儿登记集合，否则红。
const DYNAMIC_TYPE_SETS = {
  // OpsExceptionScannerService：scanDoorOpen 传字面量，scanUpdatedState(…, type, …) 的两个调用点
  'OpsExceptionScannerService.java': [
    'RECOGNITION_STUCK',
    'SETTLEMENT_STUCK',
    'DOOR_OPEN_TOO_LONG'
  ],
  // JiangyiRecognitionTimeoutService:196-206 exceptionType(cause) 的 switch 三个分支
  'JiangyiRecognitionTimeoutService.java': [
    'JIANGYI_CLASS_MAPPING_MISS',
    'RECOGNITION_FAILED',
    'RECOGNITION_TIMEOUT'
  ]
};
for (const f of files) {
  const src = bodies.get(f);
  const baseline = DYNAMIC_TYPE_SETS[path.basename(f)];
  for (const m of src.matchAll(
    /\b(?:opsExceptionService|exceptionService)\.report\(\s*([^,()]*(?:\([^()]*\))?[^,()]*),/g
  )) {
    const arg = m[1].trim();
    const type = resolveType(arg, f);
    if (type === null) {
      if (!baseline) dynamicSites.push(`${path.relative(root, f)}: report( 首参未解析 = ${arg}`);
      else
        for (const t of baseline)
          reportSites.push({ type: t, where: `${path.relative(root, f)}（动态集合）` });
    } else reportSites.push({ type, where: path.relative(root, f) });
  }
  for (const m of src.matchAll(/\breport\(\s*session,\s*([^,]+),/g)) {
    const arg = m[1].trim();
    const type = resolveType(arg, f);
    if (type === null) {
      if (!baseline)
        dynamicSites.push(`${path.relative(root, f)}: report(session, …) 第 2 参未解析 = ${arg}`);
    } else reportSites.push({ type, where: path.relative(root, f) });
  }
}

const bootstrap = path.join(
  root,
  'services/trade-service/src/main/java/com/aicabinet/trade/service/SysDictBootstrap.java'
);
const bootSrc = stripComments(fs.readFileSync(bootstrap, 'utf8'));
const start = bootSrc.indexOf('map.put("exception_type"');
if (start < 0) fail('SysDictBootstrap 里找不到 exception_type 的内置集');
let block = '';
if (start >= 0) {
  let depth = 0;
  for (let i = bootSrc.indexOf('(', start); i < bootSrc.length; i++) {
    if (bootSrc[i] === '(') depth++;
    else if (bootSrc[i] === ')') depth--;
    block += bootSrc[i];
    if (depth === 0) break;
  }
}
const dictValues = new Set();
const mBlock = /m\(([\s\S]*)\)\s*\)/.exec(block);
if (mBlock) {
  const tokens = [];
  for (const t of mBlock[1].matchAll(/"([^"]+)"|([A-Z][A-Z0-9_]*)/g)) {
    tokens.push(t[1] !== undefined ? t[1] : t[2]);
  }
  for (let i = 0; i + 1 < tokens.length; i += 2) {
    dictValues.add(
      /^[A-Z][A-Z0-9_]*$/.test(tokens[i]) ? (consts.get(tokens[i]) ?? tokens[i]) : tokens[i]
    );
  }
}
if (dictValues.size === 0) fail('exception_type 内置集解析为空（写法变了，请同步本门禁）');

const missing = [...new Set(reportSites.filter((s) => !dictValues.has(s.type)).map((s) => s.type))];
for (const type of missing) {
  const where = reportSites.filter((s) => s.type === type).map((s) => s.where);
  fail(
    `代码会写但 exception_type 字典没有：${type}（${where.join(' / ')}）⇒ 后台异常台渲染不出该类型`
  );
}

if (dynamicSites.length > 0) {
  fail(
    '未登记的动态 report 站点（首参是变量/方法返回值，机器求不出值）——' +
      '请把它的取值集合加进 DYNAMIC_TYPE_SETS 再提交：\n' +
      dynamicSites.map((s) => `  - ${s}`).join('\n')
  );
}

const dictOnlyTypes = [...dictValues].filter((v) => !reportSites.some((s) => s.type === v));
if (errors.length) {
  console.error('check-exception-type-dict: FAIL');
  for (const e of errors) console.error(`  ✗ ${e}`);
  process.exit(1);
}
console.log(
  `check-exception-type-dict: PASS（report 站点 ${new Set(reportSites.map((s) => s.type)).size} 类 ⊆ ` +
    `exception_type 字典 ${dictValues.size} 类；字典内 ${dictOnlyTypes.length} 类由其他链路/历史写入；` +
    `动态站点全部已登记）`
);
