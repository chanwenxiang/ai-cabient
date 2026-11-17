/**
 * check-charge-trace-after-commit 的 sibling 夹具（S8③：判据类改动必须同批带正向用例）。
 *
 * 为什么不用 `--self-test` 顶替：`check-gate-test-coupling` 只认 sibling `.test.mjs`
 * 或 `gate-fixture-registry.json` 的 fixtureMap 指向——内联自检不算（F1 踩过）。
 *
 * 这里钉三件事：
 * ①判据真的能红（旧形态 / 无事务守卫 / afterCompletion / 判据被删 / 出口未接线）；
 * ②判据不会「因为实参里带 `)` 就误判」——`self.finalizeChargePending(order.getOrderId(), null)`
 *   是无单号回落分支，必须放行（首版正是在这里假红）；
 * ③注释里的旧写法不算违规（maskComments 生效），否则把缺陷说明写进注释就会自锁。
 */
import assert from 'node:assert/strict';
import { evaluate, maskComments, methodBody } from './check-charge-trace-after-commit.mjs';

const GREEN_PAYMENT = `
    private boolean submitChannelCharge(CabinetOrder order) {
        settleChargeTraceAfterCommit(order.getOrderId(), "TN");
        // 无单号回落分支：允许就地收口
        self.finalizeChargePending(order.getOrderId(), null);
        return true;
    }
    private void settleChargeTraceAfterCommit(String orderId, String tradeNo) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) { return; }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { self.finalizeChargePending(orderId, tradeNo); }
        });
    }
    public boolean hasUnsettledCharge(String orderId) { return false; }
    public java.util.List<PaymentOperation> findSettledButUnbooked(int limit) { return null; }
`;
const GREEN_UNPAID = `
    public int autoCancelExpired() { alertChargeSettledButUnbookedOps(); return 0; }
    public int alertChargeSettledButUnbookedOps() { report("CHARGE_SETTLED_NOT_BOOKED"); return 0; }
`;

let n = 0;
const ok = (name, sources) => {
  const { errors } = evaluate(sources);
  assert.deepEqual(errors, [], `${name} 应为绿，实得：${errors.join(' | ')}`);
  n++;
};
const red = (name, sources, needle) => {
  const { errors } = evaluate(sources);
  assert.ok(errors.length > 0, `${name} 应为红`);
  assert.ok(
    errors.some((e) => e.includes(needle)),
    `${name} 应命中「${needle}」，实得：${errors.join(' | ')}`
  );
  n++;
};

// ── ① 基线绿（含「无单号就地收口」例外）──
ok('基线绿', { payment: GREEN_PAYMENT, unpaid: GREEN_UNPAID });

// ── ② 旧形态：带单号就地收口 → 红 ──
red(
  '带单号就地收口',
  {
    payment: GREEN_PAYMENT.replace(
      'settleChargeTraceAfterCommit(order.getOrderId(), "TN");',
      'self.finalizeChargePending(order.getOrderId(), "TN");'
    ),
    unpaid: GREEN_UNPAID
  },
  'R1'
);

// ── ③ 成功段根本不收口 → 红 ──
red(
  '成功段未收口',
  {
    payment: GREEN_PAYMENT.replace(
      'settleChargeTraceAfterCommit(order.getOrderId(), "TN");',
      '// 忘记收口'
    ),
    unpaid: GREEN_UNPAID
  },
  'settleChargeTraceAfterCommit'
);

// ── ④ 助手缺「无事务」守卫 → 红（会把补扣链重新弄坏）──
red(
  '缺 isSynchronizationActive',
  {
    payment: GREEN_PAYMENT.replace(
      'if (!TransactionSynchronizationManager.isSynchronizationActive()) { return; }',
      ''
    ),
    unpaid: GREEN_UNPAID
  },
  'isSynchronizationActive'
);

// ── ⑤ 用 afterCompletion 冒充 after-commit → 红（回滚时也会触发）──
red(
  'afterCompletion 冒充',
  {
    payment: GREEN_PAYMENT.replace('afterCommit()', 'afterCompletion(a, b)'),
    unpaid: GREEN_UNPAID
  },
  'afterCompletion'
);

// ── ⑥ 判据被删 → 红 ──
red(
  '判据被删',
  {
    payment: GREEN_PAYMENT.replace(
      'public boolean hasUnsettledCharge(String orderId) { return false; }',
      ''
    ),
    unpaid: GREEN_UNPAID
  },
  'hasUnsettledCharge'
);

// ── ⑦ 巡检只定义不接线 → 红（死代码等于没做）──
red(
  '巡检未接调度链',
  {
    payment: GREEN_PAYMENT,
    unpaid: GREEN_UNPAID.replace('alertChargeSettledButUnbookedOps();', '')
  },
  'autoCancelExpired'
);

// ── ⑧ 告警类型被改 → 红（看板口径断）──
red(
  '告警类型被改',
  { payment: GREEN_PAYMENT, unpaid: GREEN_UNPAID.replace('CHARGE_SETTLED_NOT_BOOKED', 'OTHER') },
  'CHARGE_SETTLED_NOT_BOOKED'
);

// ── ⑨ 注释里的旧写法不算违规（maskComments 生效）──
ok('注释里的旧写法放行', {
  payment: GREEN_PAYMENT.replace(
    '    private boolean submitChannelCharge',
    '    // 旧写法（已废弃）：self.finalizeChargePending(order.getOrderId(), charge.tradeNo());\n    private boolean submitChannelCharge'
  ),
  unpaid: GREEN_UNPAID
});

// ── ⑩ 工具函数本体 ──
assert.ok(
  !maskComments('a /* self.finalizeChargePending(x) */ b').includes('finalizeChargePending')
);
assert.ok(maskComments('a /* x */ b').length === 'a /* x */ b'.length, '抹注释必须等长（保行号）');
assert.ok(methodBody('private void foo() { bar(); }', 'foo').includes('bar()'));
assert.equal(methodBody('private void foo() { bar(); }', 'nope'), null);
n += 4;

console.log(`OK：${n} 项断言全过`);
