#!/usr/bin/env node
/**
 * check-system-config-fund-ceilings 的负向夹具（MA-292，2026-11-15）。
 *
 *   node scripts/check-system-config-fund-ceilings.test.mjs
 */
import assert from 'node:assert/strict';
import { fundCeilingProblems } from './check-system-config-fund-ceilings.mjs';

const TAG = '[check-system-config-fund-ceilings.test]';
let checks = 0;

function check(name, fn) {
  fn();
  checks += 1;
  console.log(`  ✓ ${name}`);
}

const REAL_SRC = `
    static final java.util.Set<String> FUND_GUARDED_KEYS = java.util.Set.of(
            REFUND_SELF_MAX_CENTS, RECHARGE_MAX_CENTS,
            CHECKOUT_PREAUTH_CENTS);

    static final java.util.Map<String, Long> FUND_KEY_CEILINGS = java.util.Map.of(
            MERCHANT_WITHDRAW_FEE_BPS, (long) CommissionParamBounds.WITHDRAW_FEE_BPS_MAX,
            LINE_WITHDRAW_FEE_BPS, (long) CommissionParamBounds.WITHDRAW_FEE_BPS_MAX,
            BALANCE_REFUND_MAX_CENTS, (long) CommissionParamBounds.REFUND_LIMIT_CENTS_CEILING,
            CHECKOUT_PREAUTH_CENTS, (long) CommissionParamBounds.PREAUTH_CENTS_CEILING,
            PROFIT_SHARING_RECEIVER_MAX_PERCENT, 100L);

    static final java.util.Map<String, Long> FUND_YUAN_KEYS = java.util.Map.of(
            PLATFORM_FUND_PLAN_BUFFER_YUAN, 10_000_000L,
            PLATFORM_FUND_RECON_TOLERANCE_YUAN, 1_000_000L);

    private static void validateFundKeyValue(String key, String value) {
        if (FUND_YUAN_KEYS.containsKey(key)) { return; }
        long parsed = 0;
    }
`;

check('真实形态零违规', () => {
  assert.deepEqual(fundCeilingProblems(REAL_SRC), []);
});

check('天花板换成 999999999L ⇒ 红（台账原缺陷形态）', () => {
  const bad = REAL_SRC.replace('CommissionParamBounds.PREAUTH_CENTS_CEILING,', '999999999L,');
  assert.ok(
    fundCeilingProblems(bad).some((p) => p.includes('CHECKOUT_PREAUTH_CENTS 天花板不再是')),
    fundCeilingProblems(bad).join('|')
  );
});

check('删元键条目 ⇒ 红', () => {
  const bad = REAL_SRC.replace(/^\s*PLATFORM_FUND_RECON_TOLERANCE_YUAN.*\n/m, '');
  // 删掉末条目后 Map.of 的 `);` 边界会被吞掉 ⇒ 判据至少要报「FUND_YUAN_KEYS 表缺失/缺键」之一
  const problems = fundCeilingProblems(bad);
  assert.ok(
    problems.some(
      (p) => p.includes('PLATFORM_FUND_RECON_TOLERANCE_YUAN') || p.includes('FUND_YUAN_KEYS')
    ),
    problems.join('|')
  );
});

check('元键混进整数守卫族 ⇒ 红', () => {
  const bad = REAL_SRC.replace(
    '            CHECKOUT_PREAUTH_CENTS);',
    '            CHECKOUT_PREAUTH_CENTS, PLATFORM_FUND_PLAN_BUFFER_YUAN);'
  );
  assert.ok(fundCeilingProblems(bad).some((p) => p.includes('混进了 FUND_GUARDED_KEYS')));
});

check('删 FUND_YUAN_KEYS 分支 ⇒ 红', () => {
  const bad = REAL_SRC.replace('        if (FUND_YUAN_KEYS.containsKey(key)) { return; }', '');
  assert.ok(fundCeilingProblems(bad).some((p) => p.includes('不再走 FUND_YUAN_KEYS 分支')));
});

check('分账接收方上限被放到 10000L ⇒ 红（MA-389）', () => {
  const bad = REAL_SRC.replace(
    'PROFIT_SHARING_RECEIVER_MAX_PERCENT, 100L);',
    'PROFIT_SHARING_RECEIVER_MAX_PERCENT, 10000L);'
  );
  assert.ok(
    fundCeilingProblems(bad).some((p) =>
      p.includes('PROFIT_SHARING_RECEIVER_MAX_PERCENT 天花板不再是')
    ),
    fundCeilingProblems(bad).join('|')
  );
});

console.log(`${TAG} OK：${checks} 项断言全过`);
