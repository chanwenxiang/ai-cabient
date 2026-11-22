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

// 铁律 84①（2026-11-22 补）：注释里出现判据 token 不得满足 R3/R4（evaluate 先等长脱敏）
{
  const billCommented = base[P + 'MerchantSettlementBillService.java'].replace(
    /private int doRebuildMonth\(M m\)\{/,
    (m) => m + ' // 历史说明：此处曾有 CONFIRMED 判据，注释不应充当判据'
  );
  const sessionCommented = base[P + 'SessionService.java'].replace(
    /private SessionDto doForceCancelForOperations\(String id, String r\)\{/,
    (m) => m + ' /* PAID PENDING DISPUTED CONFLICT 注释里的词不算校验 */'
  );
  const errs = gate.evaluate(read({ [P + 'MerchantSettlementBillService.java']: billCommented, [P + 'SessionService.java']: sessionCommented }));
  assert.ok(
    errs.some((e) => e.includes('R3')) && errs.some((e) => e.includes('R4')),
    '注释里的判据 token 不得满足 R3/R4：' + JSON.stringify(errs)
  );
}
console.log('[check-status-cas.test] OK：6 项断言全过（R1–R4 负向 + 注释脱敏）');
