#!/usr/bin/env node
/** S8③ 配套夹具：F3b 结算拆段判据 R1–R5。 */
import assert from 'node:assert/strict';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const ROOT = resolve(join(dirname(fileURLToPath(import.meta.url)), '..'));
const gate = await import(pathToFileURL(join(ROOT, 'scripts', 'check-settlement-split.mjs')).href);
const P = 'services/trade-service/src/main/java/com/aicabinet/trade/service/';
const base = {
  [P + 'SettlementOrderFinalizeService.java']: `class F {
    OrderReadModel finalizeOrder(ShoppingSession session, List i) {
      var p = self.prepareSettlement(s, i);
      orderPaymentService.chargeOrder(p.order());
      return self.finalizePaidSettlement(s, p.order(), p.coupon());
    }
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    SettlementPrep prepareSettlement(ShoppingSession s, List i) { return null; }
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    OrderReadModel finalizePaidSettlement(ShoppingSession s, CabinetOrder o, Object c) { return null; }
  }`,
  [P + 'SettlementSettleOrchestrator.java']: `class O {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    RecognitionPlan decideRecognition(ShoppingSession s, RecognitionOutcome r, boolean a) { return null; }
    public OrderReadModel processRecognitionResultInTx(ShoppingSession s, RecognitionOutcome r, boolean a) { return null; }
  }`,
  [P + 'SettlementService.java']:
    'class S { public OrderReadModel settleManual(ShoppingSession s, List i) { return null; } }',
  [P + 'DataConsistencyService.java']: `class DC {
    private FixOutcome doFixInconsistencyDetailed(Long o, Long r) { return null; }
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public FixPlan applyFixInTx(Long r) { return null; }
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFixedInTx(Long r, Long o, String m) { }
    private FixOutcome doRefundOutsideTx(RefundIntent i) { refundOrder(o, i.overCents(), "x"); return null; }
  }`,
  [P + 'SettlementConfirmDisputeService.java']: `class D {
    public ConfirmDisputePrep prepareConfirmDispute(String id, List i) { return null; }
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ConfirmDisputePrep prepareAdjustInTx(String id, List i) { return null; }
  }`
};
const read =
  (over = {}) =>
  (rel) =>
    over[rel] ?? base[rel] ?? '';
assert.deepEqual(gate.evaluate(read()), [], '基线应绿');

const finTx = base[P + 'SettlementOrderFinalizeService.java'].replace(
  'OrderReadModel finalizeOrder(ShoppingSession session, List i) {',
  '@Transactional\n    OrderReadModel finalizeOrder(ShoppingSession session, List i) {'
);
assert.ok(
  gate
    .evaluate(read({ [P + 'SettlementOrderFinalizeService.java']: finTx }))
    .some((e) => e.includes('R1')),
  'R1 注解未判红'
);

const finOrder = base[P + 'SettlementOrderFinalizeService.java'].replace(
  'orderPaymentService.chargeOrder(p.order());\n      return self.finalizePaidSettlement(s, p.order(), p.coupon());',
  'var dto = self.finalizePaidSettlement(s, p.order(), p.coupon());\n      orderPaymentService.chargeOrder(p.order());\n      return dto;'
);
assert.ok(
  gate
    .evaluate(read({ [P + 'SettlementOrderFinalizeService.java']: finOrder }))
    .some((e) => e.includes('R1')),
  'R1 顺序未判红'
);

const noReq = base[P + 'SettlementOrderFinalizeService.java'].replace(
  '@Transactional(propagation = Propagation.REQUIRES_NEW)\n    SettlementPrep',
  'SettlementPrep'
);
assert.ok(
  gate
    .evaluate(read({ [P + 'SettlementOrderFinalizeService.java']: noReq }))
    .some((e) => e.includes('R2')),
  'R2 未判红'
);

const settleTx = base[P + 'SettlementService.java'].replace(
  'public OrderReadModel settleManual',
  '@Transactional\n    public OrderReadModel settleManual'
);
assert.ok(
  gate.evaluate(read({ [P + 'SettlementService.java']: settleTx })).some((e) => e.includes('R4')),
  'R4 未判红'
);

const disputeTx = base[P + 'SettlementConfirmDisputeService.java'].replace(
  'public ConfirmDisputePrep prepareConfirmDispute',
  '@Transactional\n    public ConfirmDisputePrep prepareConfirmDispute'
);
assert.ok(
  gate
    .evaluate(read({ [P + 'SettlementConfirmDisputeService.java']: disputeTx }))
    .some((e) => e.includes('R5')),
  'R5 未判红'
);

const dcTx = base[P + 'DataConsistencyService.java'].replace(
  'private FixOutcome doFixInconsistencyDetailed(',
  '@Transactional\n    private FixOutcome doFixInconsistencyDetailed('
);
assert.ok(
  gate.evaluate(read({ [P + 'DataConsistencyService.java']: dcTx })).some((e) => e.includes('R6')),
  'R6 未判红'
);

console.log('[check-settlement-split.test] OK：7 项断言全过（R1/R2/R4/R5/R6 负向）');
