#!/usr/bin/env node
/** S8③ 配套夹具：F5 状态机条件写入判据。 */
import assert from 'node:assert/strict';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const ROOT = resolve(join(dirname(fileURLToPath(import.meta.url)), '..'));
const gate = await import(pathToFileURL(join(ROOT, 'scripts', 'check-status-cas.mjs')).href);
const P = 'services/trade-service/src/main/java/com/aicabinet/trade/service/';
const base = {
  [P + 'CompensationTaskScheduler.java']:
    'class C { public void processTask(T t){ claimIfPending(1L); finishIfProcessing(1L,"A","B",x); rescheduleIfProcessing(1L,x,"r"); } }',
  [P + 'RefundRetryScheduler.java']:
    'class R { private void touch(T t){ touchIfInitiated(id, now); } }',
  [P + 'OrderPaymentService.java']:
    'class O { public void markRefundFailed(T t, String c){ failIfInitiated(id, now); } }',
  [P + 'MerchantSettlementBillService.java']:
    'class B { private int doRebuildMonth(M m){ if ("CONFIRMED".equals(s)) continue; } private void deleteStaleRows(M m, L l){ if ("CONFIRMED".equals(s)) continue; } }',
  [P + 'SessionService.java']:
    'class S { private SessionDto doForceCancelForOperations(String id, String r){ if (Set.of("PAID","PENDING","DISPUTED").contains(s)) throw new CONFLICT(); } }'
};
const read =
  (over = {}) =>
  (rel) =>
    over[rel] ?? base[rel] ?? '';
assert.deepEqual(gate.evaluate(read()), [], '基线应绿');
for (const [file, sig, label] of [
  [P + 'CompensationTaskScheduler.java', 'claimIfPending', 'R1'],
  [P + 'RefundRetryScheduler.java', 'touchIfInitiated', 'R2'],
  [P + 'MerchantSettlementBillService.java', 'CONFIRMED', 'R3'],
  [P + 'SessionService.java', 'PAID', 'R4']
]) {
  const broken = base[file].replace(new RegExp(sig, 'g'), 'noop');
  assert.ok(
    gate.evaluate(read({ [file]: broken })).some((e) => e.includes(label)),
    `${label} 未判红`
  );
}

// 铁律 84①（2026-11-22 补）：注释脱敏的两个方向都要钉——
// ① 注释里的判据 token 不得**满足**判据（R3：CONFIRMED 只在注释 ⇒ 必须红）；
// ② 注释里的违禁 token 不得**误报**（R2：touch 方法体注释含 updateById ⇒ 必须仍绿，
//    旧无脱敏实现会把它当真实无条件写而假红）。
// （R4 的 PAID/PENDING 是字符串字面量不是注释，无「注释满足」形态，不在此例。）
{
  const billCommented = base[P + 'MerchantSettlementBillService.java'].replace(
    /private int doRebuildMonth\(M m\)\{/,
    (m) => m + ' // 历史说明：此处曾有 CONFIRMED 判据，注释不应充当判据'
  );
  const errsBill = gate.evaluate(
    read({ [P + 'MerchantSettlementBillService.java']: billCommented })
  );
  assert.ok(
    errsBill.some((e) => e.includes('R3')),
    '注释里的 CONFIRMED 不得满足 R3：' + JSON.stringify(errsBill)
  );

  // 注入真生效守卫（铁律 2：注入串不命中=断言空转=假绿）
  assert.ok(billCommented !== base[P + 'MerchantSettlementBillService.java'], 'R3 注入必须真生效');
  const refundCommented = base[P + 'RefundRetryScheduler.java'].replace(
    /private void touch\(T t\)\{/,
    (m) => m + ' /* 历史形态 updateById( 已改条件写入，注释里出现不得假红 */'
  );
  assert.ok(refundCommented !== base[P + 'RefundRetryScheduler.java'], 'R2 注入必须真生效');
  const errsRefund = gate.evaluate(read({ [P + 'RefundRetryScheduler.java']: refundCommented }));
  assert.ok(
    !errsRefund.some((e) => e.includes('R2')),
    'touch 注释含 updateById 不得误报 R2：' + JSON.stringify(errsRefund)
  );
}
console.log('[check-status-cas.test] OK：6 项断言全过（R1–R4 负向 + 注释脱敏双向）');
