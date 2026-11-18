#!/usr/bin/env node
/** S8③ 配套夹具：F6 重试窗口 R1/R2/R3 负向验证。 */
import assert from 'node:assert/strict';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const ROOT = resolve(join(dirname(fileURLToPath(import.meta.url)), '..'));
const gate = await import(pathToFileURL(join(ROOT, 'scripts', 'check-retry-window.mjs')).href);
const base = {
  'services/trade-service/src/main/java/com/aicabinet/trade/service/RefundRetryScheduler.java':
    'class R { private int exhaustOverdue() { String s = queryInitiatedRefundStatus(t); if ("UNKNOWN".equals(s)) continue; markRefundFailed(t, "x"); } }',
  'services/trade-service/src/main/java/com/aicabinet/trade/service/SessionService.java':
    'class S { private void cancelPayScoreOrdersQuietly(ShoppingSession session) { findByOutOrderNo(session.getSessionId()); } }',
  'infra/xxl-job/seed_aicabinet_jobs.sql':
    "'profitSharingRetryJob', '', 'SERIAL_EXECUTION', 900, 0, 'BEAN'\n'merchantDailyIncomeNotifyJob', '', 'SERIAL_EXECUTION', 1800, 0, 'BEAN'"
};
const read =
  (over = {}) =>
  (rel) =>
    over[rel] ?? base[rel] ?? '';
assert.deepEqual(gate.evaluate(read()), [], '基线应绿');

const r1 = gate.evaluate(
  read({
    'services/trade-service/src/main/java/com/aicabinet/trade/service/RefundRetryScheduler.java':
      'class R { private int exhaustOverdue() { markRefundFailed(t, "x"); } }'
  })
);
assert.ok(
  r1.some((e) => e.includes('R1')),
  'R1 未判红'
);
const r2 = gate.evaluate(
  read({
    'services/trade-service/src/main/java/com/aicabinet/trade/service/SessionService.java':
      'class S { private void cancelPayScoreOrdersQuietly(ShoppingSession session) { findByOrderId(session.getOrderId()); } }'
  })
);
assert.ok(
  r2.some((e) => e.includes('R2')),
  'R2 未判红'
);
const r3 = gate.evaluate(
  read({
    'infra/xxl-job/seed_aicabinet_jobs.sql': base['infra/xxl-job/seed_aicabinet_jobs.sql'].replace(
      ", 0, 'BEAN'",
      ", 2, 'BEAN'"
    )
  })
);
assert.ok(
  r3.some((e) => e.includes('R3')),
  'R3 未判红'
);

console.log('[check-retry-window.test] OK：4 项断言全过（R1–R3 负向）');
