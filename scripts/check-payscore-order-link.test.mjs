#!/usr/bin/env node
/** S8③ 配套夹具：支付分 order_id 闭环门禁 R1/R2/R3 负向验证。 */
import assert from 'node:assert/strict';
import { mkdirSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const ROOT = resolve(join(dirname(fileURLToPath(import.meta.url)), '..'));
const BOX = join(ROOT, '.tmp', 'payscore-link-gate-fixture');
const MIG_DIR = join(
  BOX,
  'services',
  'trade-service',
  'src',
  'main',
  'resources',
  'db',
  'migration'
);
const JAVA_DIR = join(
  BOX,
  'services',
  'trade-service',
  'src',
  'main',
  'java',
  'com',
  'aicabinet',
  'trade',
  'service'
);
mkdirSync(MIG_DIR, { recursive: true });
mkdirSync(JAVA_DIR, { recursive: true });

const MIG =
  'services/trade-service/src/main/resources/db/migration/V496__payscore_order_opening_nullable.sql';
const PAY = 'services/trade-service/src/main/java/com/aicabinet/trade/service/PayScoreService.java';
const UNPAID =
  'services/trade-service/src/main/java/com/aicabinet/trade/service/UnpaidOrderService.java';
const mig = `-- COMPETITOR_REF: CB-064\nALTER TABLE payscore_order ALTER COLUMN order_id DROP NOT NULL;`;
const pay = `class PayScoreService {
  void completeWechatOrderForSession(String id, Order o) {
    PayScoreOrder po = find(id);
    po.setOrderId(o.getOrderId());
  }
}`;
const unpaid = `class UnpaidOrderService {
  int autoCancelExpired() {
    alertStaleUnlinkedPayscoreOrders();
    return 0;
  }
  public int alertStaleUnlinkedPayscoreOrders() {
    report("PAYSCORE_ORDER_UNLINKED", "HIGH");
    return 0;
  }
}`;
const write = (rel, body) => {
  const p = join(BOX, rel);
  mkdirSync(dirname(p), { recursive: true });
  writeFileSync(p, body);
};
write(MIG, mig);
write(PAY, pay);
write(UNPAID, unpaid);

const gate = await import(
  pathToFileURL(join(ROOT, 'scripts', 'check-payscore-order-link.mjs')).href
);
assert.deepEqual(gate.evaluate(BOX), [], '基线应绿');

write(MIG, mig.replace('DROP NOT NULL', ''));
assert.ok(
  gate.evaluate(BOX).some((e) => e.includes('R1')),
  'R1 未判红'
);
write(MIG, mig);
write(PAY, pay.replace('po.setOrderId(o.getOrderId());', ''));
assert.ok(
  gate.evaluate(BOX).some((e) => e.includes('R2')),
  'R2 未判红'
);
write(PAY, pay);
write(UNPAID, unpaid.replace('    alertStaleUnlinkedPayscoreOrders();', ''));
assert.ok(
  gate.evaluate(BOX).some((e) => e.includes('R3')),
  'R3 未判红'
);
write(UNPAID, unpaid.replace('"PAYSCORE_ORDER_UNLINKED"', '"SOMETHING_ELSE"'));
assert.ok(
  gate.evaluate(BOX).some((e) => e.includes('R3')),
  'R3 告警类型未判红'
);

console.log('[check-payscore-order-link.test] OK：5 项断言全过（R1/R2/R3 负向）');
