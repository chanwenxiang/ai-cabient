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
console.log('[check-status-cas.test] OK：5 项断言全过（R1–R4 负向）');
