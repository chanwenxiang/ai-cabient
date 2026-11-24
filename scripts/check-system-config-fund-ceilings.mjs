#!/usr/bin/env node
/**
 * MA-292：SystemConfigService 资金键天花板与元单位键的静态判据。
 *
 * FUND_KEY_CEILINGS / FUND_YUAN_KEYS 这两张表此前不在任何门禁判据内——把天花板改成
 * 999999999L 门禁照样绿（台账 MA-292 原文）。这里把「键 → 天花板常量」逐条钉死：
 * 换值、删条目、把元键塞进整数守卫族（Long.parseLong 会 400 掉合法小数）都判红。
 *
 *   node scripts/check-system-config-fund-ceilings.mjs
 */
import { readFileSync } from 'node:fs';
import { resolve as resolvePath, join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { maskForScan } from './check-preauth-zero-semantics.mjs';

const TAG = '[check-system-config-fund-ceilings]';
const ROOT = process.env.GATE_ROOT
  ? resolvePath(process.env.GATE_ROOT)
  : join(dirname(fileURLToPath(import.meta.url)), '..');
const SVC =
  'services/trade-service/src/main/java/com/aicabinet/trade/service/SystemConfigService.java';

/** 键 → 天花板来源（常量名，保持与源码逐字一致）。 */
const FEN_CEILING_ENTRIES = [
  ['MERCHANT_WITHDRAW_FEE_BPS', 'CommissionParamBounds.WITHDRAW_FEE_BPS_MAX'],
  ['LINE_WITHDRAW_FEE_BPS', 'CommissionParamBounds.WITHDRAW_FEE_BPS_MAX'],
  ['BALANCE_REFUND_MAX_CENTS', 'CommissionParamBounds.REFUND_LIMIT_CENTS_CEILING'],
  ['BALANCE_REFUND_MIN_CENTS', 'CommissionParamBounds.REFUND_LIMIT_CENTS_CEILING'],
  ['CHECKOUT_PREAUTH_CENTS', 'CommissionParamBounds.PREAUTH_CENTS_CEILING'],
  // MA-389：这张表不是「只有分」——本键单位是百分数，天花板 100 是字面量。
  // 它决定「这笔分成能不能发分账请求」，属资金路由闸，必须与写/读两侧同口径。
  ['PROFIT_SHARING_RECEIVER_MAX_PERCENT', '100L']
];
/** MA-179 元单位键：键 → 天花板（分，字面量逐字一致）。 */
const YUAN_CEILING_ENTRIES = [
  ['PLATFORM_FUND_PLAN_BUFFER_YUAN', '10_000_000L'],
  ['PLATFORM_FUND_RECON_TOLERANCE_YUAN', '1_000_000L']
];

/** 抓取 `anchor` 起、到第一个 `endToken` 止的代码块（等长掩码后的源码）。 */
function blockOf(src, anchor, endToken) {
  const i = src.indexOf(anchor);
  if (i < 0) return null;
  const j = src.indexOf(endToken, i);
  if (j < 0) return null;
  return src.slice(i, j + endToken.length);
}

/** 纯判据：喂掩码后的源码，返回违规列表（供 sibling 夹具直接驱动）。 */
export function fundCeilingProblems(src) {
  const problems = [];
  const fenBlock = blockOf(src, 'FUND_KEY_CEILINGS = java.util.Map.of(', ');');
  if (fenBlock === null) {
    problems.push('找不到 FUND_KEY_CEILINGS 表——天花板判据失效，不得判绿');
  } else {
    for (const [key, ceiling] of FEN_CEILING_ENTRIES) {
      if (!fenBlock.includes(key))
        problems.push(`FUND_KEY_CEILINGS 缺少键 ${key}（删条目 = 天花板失效）`);
      if (!fenBlock.includes(ceiling))
        problems.push(`FUND_KEY_CEILINGS 的 ${key} 天花板不再是 ${ceiling}（换值须过评审）`);
    }
    // 表内出现判据外的键 ⇒ 有人塞了未评审条目（Map.of 的行形态：`KEY, (long) X,`）
    for (const m of fenBlock.matchAll(/^\s*([A-Z][A-Z0-9_]+),/gm)) {
      if (!FEN_CEILING_ENTRIES.some(([k]) => k === m[1])) {
        problems.push(`FUND_KEY_CEILINGS 出现判据外的键 ${m[1]}——请先补进本门禁再改值`);
      }
    }
  }
  const yuanBlock = blockOf(src, 'FUND_YUAN_KEYS = java.util.Map.of(', ');');
  if (yuanBlock === null) {
    problems.push('找不到 FUND_YUAN_KEYS 表（MA-179 元单位键守卫被删）');
  } else {
    for (const [key, ceiling] of YUAN_CEILING_ENTRIES) {
      if (!yuanBlock.includes(key)) problems.push(`FUND_YUAN_KEYS 缺少元键 ${key}`);
      if (!yuanBlock.includes(ceiling))
        problems.push(`FUND_YUAN_KEYS 的 ${key} 天花板不再是 ${ceiling}`);
    }
  }
  if (!/FUND_YUAN_KEYS\.containsKey\(key\)/.test(src)) {
    problems.push('validateFundKeyValue 不再走 FUND_YUAN_KEYS 分支（元键会被整数校验 400）');
  }
  // 元键不得混进整数守卫族（Long.parseLong 会拒掉合法的 ≤2 位小数）
  const guardedBlock = blockOf(src, 'FUND_GUARDED_KEYS = java.util.Set.of(', ');');
  if (guardedBlock !== null) {
    for (const [key] of YUAN_CEILING_ENTRIES) {
      if (guardedBlock.includes(key)) {
        problems.push(`元键 ${key} 混进了 FUND_GUARDED_KEYS（整数校验会 400 掉合法小数元值）`);
      }
    }
  }
  return problems;
}

function main() {
  const src = maskForScan(readFileSync(join(ROOT, SVC), 'utf8'));
  const problems = fundCeilingProblems(src);
  if (problems.length) {
    console.error(`${TAG} FAIL：\n  - ` + problems.join('\n  - '));
    process.exit(1);
  }
  console.log(
    `${TAG} OK：FEN 天花板 ${FEN_CEILING_ENTRIES.length} 条 + 元键 ${YUAN_CEILING_ENTRIES.length} 条 + 元键校验分支，逐条对上`
  );
}

if (
  process.argv[1] &&
  resolvePath(process.argv[1]) === resolvePath(fileURLToPath(import.meta.url))
) {
  main();
}
