#!/usr/bin/env node
/** S8③ 配套夹具：CHARGE 幂等键门禁的 R1–R4 负向验证。 */
import assert from 'node:assert/strict';
import { mkdirSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const ROOT = resolve(join(dirname(fileURLToPath(import.meta.url)), '..'));
const BOX = join(ROOT, '.tmp', 'charge-idem-gate-fixture');
const DIR = join(
  BOX,
  'services',
  'trade-service',
  'src',
  'main',
  'java',
  'com',
  'aicabinet',
  'trade'
);
mkdirSync(DIR, { recursive: true });
const PAY = 'service/OrderPaymentService.java';
const NOTIFY = 'service/PayScoreOrderNotifyService.java';

const pay = `class OrderPaymentService {
  static String chargeIdempotencyKey(CabinetOrder order, int amount) {
    return "CHARGE:S:" + order.getSessionId() + ":" + amount;
  }
  private static String legacyChargeIdempotencyKey(String orderId, int amount) {
    return "CHARGE:" + orderId + ":" + amount;
  }
  private Optional<PaymentOperation> findChargeOperation(CabinetOrder order, int amountCents) {
    var op = repo.findByIdempotencyKey(chargeIdempotencyKey(order, amountCents));
    if (op.isEmpty()) op = repo.findByIdempotencyKey(legacyChargeIdempotencyKey(order.getOrderId(), amountCents));
    return op;
  }
}`;
const notify = `class PayScoreOrderNotifyService {
  void handlePaid() {
    String idem = OrderPaymentService.chargeIdempotencyKey(order, amount);
    boolean old = repo.findByIdempotencyKey("CHARGE:" + order.getOrderId() + ":" + amount).isPresent();
  }
}`;
const write = (p, s) => {
  mkdirSync(join(DIR, dirname(p)), { recursive: true });
  writeFileSync(join(DIR, p), s);
};
write(PAY, pay);
write(NOTIFY, notify);

const gate = await import(
  pathToFileURL(join(ROOT, 'scripts', 'check-charge-idem-session-scoped.mjs')).href
);
assert.deepEqual(gate.evaluate(BOX), [], '基线应绿');

// R1 退回 orderId 造键
write(
  PAY,
  pay.replace('return "CHARGE:S:" + order.getSessionId()', 'return "CHARGE:" + order.getOrderId()')
);
assert.ok(
  gate.evaluate(BOX).some((e) => e.includes('R1')),
  'R1 未判红'
);

// R2 旧键写入
write(PAY, pay.replace('  }', '    op.setIdempotencyKey("CHARGE:" + order.getOrderId());\n  }'));
assert.ok(
  gate.evaluate(BOX).some((e) => e.includes('R2')),
  'R2 未判红'
);

// R3 去掉双读
write(
  PAY,
  pay.replace(
    'if (op.isEmpty()) op = repo.findByIdempotencyKey(legacyChargeIdempotencyKey(order.getOrderId(), amountCents));',
    ''
  )
);
assert.ok(
  gate.evaluate(BOX).some((e) => e.includes('R3')),
  'R3 未判红'
);

// R4 回调脱钩
write(PAY, pay);
write(
  NOTIFY,
  notify.replace(
    'OrderPaymentService.chargeIdempotencyKey(order, amount)',
    '"CHARGE:" + order.getOrderId()'
  )
);
assert.ok(
  gate.evaluate(BOX).some((e) => e.includes('R4')),
  'R4 未判红'
);

// 注释不造假红
write(NOTIFY, `// OrderPaymentService.chargeIdempotencyKey(x)\n${notify}`);
assert.deepEqual(gate.evaluate(BOX), [], '注释遮蔽失败');

console.log('[check-charge-idem-session-scoped.test] OK：6 项断言全过（R1–R4 负向）');
