import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { FILES, findMa449459Violations } from './check-ma449-459-contracts.mjs';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const readSources = () =>
  Object.fromEntries(
    Object.entries(FILES).map(([key, rel]) => [key, fs.readFileSync(path.join(root, rel), 'utf8')])
  );

const sources = readSources();
assert.deepEqual(findMa449459Violations(sources), [], '真实源码必须满足全部契约');

const negativeCases = [
  [
    'MA-449',
    'replenishment',
    (s) => s.replaceAll('STATUS_CANCELLED.equals(task.getStatus())', 'false')
  ],
  [
    'MA-450',
    'balanceRefund',
    (s) => s.replace('int resumeRound = done.size();', 'int resumeRound = 0;')
  ],
  [
    'MA-451',
    'procurement',
    (s) => s.replace('allocation.getWarehouseId()', 'order.getWarehouseId()')
  ],
  [
    'MA-452',
    'closeSheet',
    (s) => s.replaceAll('requireSheetForUpdate(closeId)', 'requireSheet(closeId)')
  ],
  [
    'MA-453',
    'orderPayment',
    (s) => s.replaceAll('"PROCESSING".equalsIgnoreCase(channelRefundStatus)', 'false')
  ],
  [
    'MA-454',
    'profitSharing',
    (s) => s.replace('VOIDED.equalsIgnoreCase(split.getStatus())', 'false')
  ],
  [
    'MA-455',
    'coupon',
    (s) =>
      s.replace('Math.max(0, uc.getDiscountCents())', 'Math.max(0, order.getCouponDiscountCents())')
  ],
  [
    'MA-456',
    'merchant',
    (s) =>
      s.replace(
        'public RevenueSplitDto refreshWeChatProfitSharing(',
        '@Transactional\n    public RevenueSplitDto refreshWeChatProfitSharing('
      )
  ],
  [
    'MA-457',
    'notification',
    (s) =>
      s.replace(
        'throw new IllegalStateException("notification dispatch failed", firstFailure);',
        ''
      )
  ],
  ['MA-458', 'modelPush', (s) => s.replace('commandTracker.register(', 'commandTracker.noop(')],
  [
    'MA-459',
    'closeDetail',
    (s) =>
      s.replace(
        'int[] shipShares = apportionByLineQuantity(totalShip, lines);',
        'int[] shipShares = new int[lines.size()];'
      )
  ],
  ['MA-460', 'payoutNotify', (s) => s.replace('notifyService.parseAndVerify(', 'verifyOrRelease(')],
  [
    'MA-461',
    'closeSheet',
    (s) => s.replaceAll('sheetMapper.lockWarehouseMonth(closeLockKey(wh, normalized));', '')
  ],
  [
    'MA-461',
    'migration510',
    (s) =>
      s.replace(
        'AND pol.sku_id = m.sku_id',
        'AND pol.sku_id = m.sku_id AND pol.batch_no = m.batch_no'
      )
  ],
  [
    'MA-462',
    'inventoryLot',
    (s) => s.replace('if (hasLotForSku(dev, sku))', 'if (deviceUsesLotLedger(dev))')
  ],
  ['MA-463', 'refundResume', (s) => s.replace('resumePendingCompletedRefunds(', 'noResume(')],
  [
    'MA-463',
    'rechargePayment',
    (s) =>
      s.replace(
        'SliceRefundProbe { ACCEPTED, PROCESSING, NOT_FOUND, UNKNOWN }',
        'SliceRefundProbe { ACCEPTED, NOT_FOUND, UNKNOWN }'
      )
  ],
  [
    'MA-461',
    'migration510',
    (s) => s.replace('batch_match_count = 0 AND sku_match_count = 1', 'sku_match_count = 1')
  ]
];

for (const [id, key, mutate] of negativeCases) {
  const changed = { ...sources, [key]: mutate(sources[key]) };
  const problems = findMa449459Violations(changed);
  assert.ok(
    problems.some((problem) => problem.startsWith(id)),
    `${id} 负向夹具必须报错`
  );
}

const commentDecoy = {
  ...sources,
  modelPush: sources.modelPush.replace('commandTracker.register(', '// commandTracker.register(')
};
assert.ok(findMa449459Violations(commentDecoy).length > 0, '注释不能冒充真实登记');
console.log('check-ma449-459-contracts.test: 1 正向 + 18 负向 + 1 注释反例通过');
