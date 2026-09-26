#!/usr/bin/env node
/**
 * 余额流水「纯冻结/释放」类型集合一致性门禁。
 *
 * 背景（W-4）：纯冻结/释放类流水只调整账户冻结额，可用余额前后不变，故后端不能用
 * 「余额差」算金额（否则恒为 ¥0.00），而是取操作金额 + 业务方向；前端对这类流水
 * 也不带正负号展示（否则「冻结 -¥50.00」与并列的「余额 ¥500.00」互相矛盾）。
 *
 * 这条语义由三处各自维护一份类型清单，跨越 Java / TypeScript：
 *   1. BalanceLedgerService#holdSignedAmount — 明细金额符号
 *   2. balance.vue#isHoldType — 前端是否隐藏正负号
 *   3. PaymentOperationMapper.HOLD_OPERATION_TYPES — SQL notIn 过滤（不进余额明细）
 * 一旦漂移就会出现：金额为 0、方向丢失、或明细里凭空多/少一种流水。
 * 因此用门禁把三份清单钉死（只钉两处会漏掉 SQL 过滤假绿）。
 *
 *   node scripts/check-balance-hold-types.mjs
 */
import { readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const TAG = '[check-balance-hold-types]';

const JAVA_FILE = join(
  root,
  'services',
  'trade-service',
  'src',
  'main',
  'java',
  'com',
  'aicabinet',
  'trade',
  'service',
  'BalanceLedgerService.java'
);
const MAPPER_FILE = join(
  root,
  'services',
  'trade-service',
  'src',
  'main',
  'java',
  'com',
  'aicabinet',
  'trade',
  'mapper',
  'PaymentOperationMapper.java'
);
const VUE_FILE = join(root, 'clients', 'consumer-mp', 'src', 'pages', 'balance', 'balance.vue');

const TYPE_LITERAL = /"([A-Z][A-Z0-9_]*)"/g;

/** 从 fromIndex 之后的第一个 '{' 起，按花括号配平截取整个方法体。 */
function braceBlock(source, fromIndex) {
  const open = source.indexOf('{', fromIndex);
  if (open < 0) return null;
  let depth = 0;
  for (let i = open; i < source.length; i += 1) {
    const ch = source[i];
    if (ch === '{') depth += 1;
    else if (ch === '}') {
      depth -= 1;
      if (depth === 0) return source.slice(open, i + 1);
    }
  }
  return null;
}

/** 从 fromIndex 之后的第一个 '(' 起，按圆括号配平截取（用于 List.of(...)）。 */
function parenBlock(source, fromIndex) {
  const open = source.indexOf('(', fromIndex);
  if (open < 0) return null;
  let depth = 0;
  for (let i = open; i < source.length; i += 1) {
    const ch = source[i];
    if (ch === '(') depth += 1;
    else if (ch === ')') {
      depth -= 1;
      if (depth === 0) return source.slice(open, i + 1);
    }
  }
  return null;
}

function fail(message, details = []) {
  console.error(`${TAG} FAIL: ${message}`);
  for (const line of details) console.error(`  ${line}`);
  process.exit(1);
}

function readSource(file, label) {
  try {
    return readFileSync(file, 'utf8');
  } catch (error) {
    fail(`无法读取 ${label}`, [`${file}`, String(error.message)]);
  }
}

function extractTypesFromBrace(file, anchor, pattern, label) {
  const source = readSource(file, label);
  const anchorIndex = source.indexOf(anchor);
  if (anchorIndex < 0) {
    fail(`${label} 中找不到锚点 ${JSON.stringify(anchor)}，门禁已失效，请同步更新本脚本`);
  }
  const body = braceBlock(source, anchorIndex);
  if (!body) {
    fail(`${label} 中无法解析 ${JSON.stringify(anchor)} 的方法体`);
  }
  const types = new Set([...body.matchAll(pattern)].map((match) => match[1]));
  if (types.size === 0) {
    fail(`${label} 的 ${JSON.stringify(anchor)} 未解析出任何业务类型，门禁已失效`);
  }
  return types;
}

function extractHoldOperationTypes(file, label) {
  const source = readSource(file, label);
  const anchor = 'HOLD_OPERATION_TYPES';
  const anchorIndex = source.indexOf(anchor);
  if (anchorIndex < 0) {
    fail(`${label} 中找不到锚点 ${JSON.stringify(anchor)}，门禁已失效，请同步更新本脚本`);
  }
  const listOf = source.indexOf('List.of', anchorIndex);
  if (listOf < 0 || listOf - anchorIndex > 200) {
    fail(`${label} 中 ${anchor} 附近找不到 List.of(...)，门禁已失效`);
  }
  const body = parenBlock(source, listOf);
  if (!body) {
    fail(`${label} 中无法解析 ${anchor} 的 List.of(...)`);
  }
  const types = new Set([...body.matchAll(TYPE_LITERAL)].map((match) => match[1]));
  if (types.size === 0) {
    fail(`${label} 的 ${anchor} 未解析出任何业务类型，门禁已失效`);
  }
  return types;
}

function sorted(set) {
  return [...set].sort();
}

function missing(from, against) {
  return [...from].filter((type) => !against.has(type));
}

const javaTypes = extractTypesFromBrace(
  JAVA_FILE,
  'holdSignedAmount(String operationType',
  TYPE_LITERAL,
  'BalanceLedgerService.java'
);

const vueTypes = extractTypesFromBrace(
  VUE_FILE,
  'function isHoldType',
  /type\s*===\s*'([A-Z][A-Z0-9_]*)'/g,
  'balance.vue'
);

const mapperTypes = extractHoldOperationTypes(MAPPER_FILE, 'PaymentOperationMapper.java');

const drift = [
  ['holdSignedAmount → isHoldType', missing(javaTypes, vueTypes), '前端缺少'],
  ['isHoldType → holdSignedAmount', missing(vueTypes, javaTypes), '后端 holdSignedAmount 缺少'],
  ['holdSignedAmount → HOLD_OPERATION_TYPES', missing(javaTypes, mapperTypes), 'SQL 清单缺少'],
  [
    'HOLD_OPERATION_TYPES → holdSignedAmount',
    missing(mapperTypes, javaTypes),
    'holdSignedAmount 缺少（SQL 多出）'
  ],
  ['isHoldType → HOLD_OPERATION_TYPES', missing(vueTypes, mapperTypes), 'SQL 清单缺少（相对前端）'],
  ['HOLD_OPERATION_TYPES → isHoldType', missing(mapperTypes, vueTypes), '前端缺少（相对 SQL）']
].filter(([, list]) => list.length > 0);

if (drift.length) {
  fail('三处「纯冻结/释放」类型集合不一致', [
    `后端 holdSignedAmount:              ${sorted(javaTypes).join(', ')}`,
    `前端 isHoldType:                    ${sorted(vueTypes).join(', ')}`,
    `SQL HOLD_OPERATION_TYPES:           ${sorted(mapperTypes).join(', ')}`,
    ...drift.flatMap(([, list, label]) => [`${label}: ${list.join(', ')}`])
  ]);
}

console.log(`${TAG} OK (${javaTypes.size} types: ${sorted(javaTypes).join(', ')})`);
