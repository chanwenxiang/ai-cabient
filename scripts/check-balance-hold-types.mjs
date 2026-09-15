#!/usr/bin/env node
/**
 * 余额流水「纯冻结/释放」类型集合一致性门禁。
 *
 * 背景（W-4）：纯冻结/释放类流水只调整账户冻结额，可用余额前后不变，故后端不能用
 * 「余额差」算金额（否则恒为 ¥0.00），而是取操作金额 + 业务方向；前端对这类流水
 * 也不带正负号展示（否则「冻结 -¥50.00」与并列的「余额 ¥500.00」互相矛盾）。
 *
 * 这条语义由两端各自维护一份类型清单，跨越 Java / TypeScript 两种语言，
 * 一旦漂移就会出现下列回归：
 *   - 后端新增、前端漏加 → 前端不带符号，后端金额却为 0 → 又回到「¥0.00」
 *   - 前端新增、后端漏加 → 前端隐藏符号，后端金额本应带方向 → 方向丢失
 * 因此用门禁把两份清单钉死。
 *
 *   node scripts/check-balance-hold-types.mjs
 *
 * 两份真源：
 *   services/trade-service/.../BalanceLedgerService.java  → holdSignedAmount(...)
 *   clients/consumer-mp/src/pages/balance/balance.vue      → isHoldType(...)
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
const VUE_FILE = join(root, 'clients', 'consumer-mp', 'src', 'pages', 'balance', 'balance.vue');

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

function fail(message, details = []) {
  console.error(`${TAG} FAIL: ${message}`);
  for (const line of details) console.error(`  ${line}`);
  process.exit(1);
}

function extractTypes(file, anchor, pattern, label) {
  let source;
  try {
    source = readFileSync(file, 'utf8');
  } catch (error) {
    fail(`无法读取 ${label}`, [`${file}`, String(error.message)]);
  }
  const anchorIndex = source.indexOf(anchor);
  if (anchorIndex < 0) {
    // 锚点消失通常意味着方法被重命名/搬迁，门禁必须显式失败而不是静默放过
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

const javaTypes = extractTypes(
  JAVA_FILE,
  'holdSignedAmount(String operationType',
  /"([A-Z][A-Z0-9_]*)"/g,
  'BalanceLedgerService.java'
);

const vueTypes = extractTypes(
  VUE_FILE,
  'function isHoldType',
  /type\s*===\s*'([A-Z][A-Z0-9_]*)'/g,
  'balance.vue'
);

const missingInVue = [...javaTypes].filter((type) => !vueTypes.has(type));
const missingInJava = [...vueTypes].filter((type) => !javaTypes.has(type));

if (missingInVue.length || missingInJava.length) {
  fail(
    '两端「纯冻结/释放」类型集合不一致',
    [
      `后端 holdSignedAmount: ${[...javaTypes].sort().join(', ')}`,
      `前端 isHoldType:       ${[...vueTypes].sort().join(', ')}`,
      missingInVue.length ? `前端缺少: ${missingInVue.join(', ')}` : '',
      missingInJava.length ? `后端缺少: ${missingInJava.join(', ')}` : ''
    ].filter(Boolean)
  );
}

console.log(`${TAG} OK (${javaTypes.size} types: ${[...javaTypes].sort().join(', ')})`);
