#!/usr/bin/env node
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { maskJavaComments } from './check-scheduled-task-watched.mjs';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const TRADE = 'services/trade-service/src/main/java/com/aicabinet/trade';
export const FILES = {
  replenishment: `${TRADE}/service/ReplenishmentService.java`,
  balanceRefund: `${TRADE}/service/BalanceRefundService.java`,
  procurement: `${TRADE}/service/ProcurementService.java`,
  closeSheet: `${TRADE}/service/WarehouseMonthlyCloseSheetService.java`,
  orderPayment: `${TRADE}/service/OrderPaymentService.java`,
  profitSharing: `${TRADE}/payment/WeChatProfitSharingService.java`,
  coupon: `${TRADE}/service/CouponService.java`,
  merchant: `${TRADE}/service/MerchantService.java`,
  notification: `${TRADE}/service/ExternalNotificationDispatcher.java`,
  closeDetail: `${TRADE}/service/WarehouseMonthlyCloseService.java`,
  modelPush:
    'services/jiangyi-gateway/src/main/java/com/aicabinet/jiangyi/api/ModelPushController.java',
  payoutNotify: `${TRADE}/api/WechatPayoutNotifyController.java`,
  payNotify: `${TRADE}/payment/WeChatPayNotifyService.java`,
  payScoreOrder: `${TRADE}/service/PayScoreOrderNotifyService.java`,
  inventoryLot: `${TRADE}/service/InventoryLotService.java`,
  withdraw: `${TRADE}/service/MerchantWithdrawService.java`,
  refundResume: `${TRADE}/service/RefundResumeService.java`,
  rechargeRefund: `${TRADE}/service/WeChatRefundReconciler.java`,
  rechargePayment: `${TRADE}/service/PaymentService.java`,
  migration510:
    'services/trade-service/src/main/resources/db/migration/V510__purchase_receive_allocation_backfill.sql'
};

export function methodBody(source, signature) {
  const match = new RegExp(signature).exec(source);
  if (!match) return '';
  const open = source.indexOf('{', match.index);
  if (open < 0) return '';
  let depth = 0;
  for (let i = open; i < source.length; i++) {
    if (source[i] === '{') depth++;
    if (source[i] === '}' && --depth === 0) return source.slice(open, i + 1);
  }
  return '';
}

function requireAll(problems, key, source, literals) {
  for (const literal of literals) {
    if (!source.includes(literal)) problems.push(`${key}: 缺少 ${literal}`);
  }
}

function requireOrder(problems, key, source, first, second) {
  const a = source.indexOf(first);
  const b = source.indexOf(second);
  if (a < 0 || b < 0 || a >= b) problems.push(`${key}: 必须按 ${first} → ${second} 排列`);
}

export function findMa449459Violations(input) {
  const sources = Object.fromEntries(
    Object.entries(input).map(([key, value]) => [key, maskJavaComments(value)])
  );
  const problems = [];
  for (const [key, rel] of Object.entries(FILES)) {
    if (!(key in sources)) problems.push(`${rel}: 未读取`);
  }

  const completeTask = methodBody(
    sources.replenishment || '',
    'private\\s+ReplenishmentTaskDto\\s+doCompleteTask\\s*\\('
  );
  requireAll(problems, 'MA-449/任务完成', completeTask, [
    'STATUS_CANCELLED.equals(task.getStatus())',
    'ResponseStatusException(HttpStatus.CONFLICT'
  ]);
  const selfPickup = methodBody(
    sources.replenishment || '',
    'private\\s+boolean\\s+shipForSelfPickup\\s*\\('
  );
  requireAll(problems, 'MA-449/作废出库', selfPickup, [
    'STATUS_CANCELLED.equals(status)',
    'ResponseStatusException(HttpStatus.CONFLICT'
  ]);

  requireAll(problems, 'MA-450', sources.balanceRefund || '', [
    'int resumeRound = done.size();',
    '"BALANCE_REFUND_RESUME:" + req.getRequestNo() + ":" + resumeRound',
    'int failureRound = allocationMapper.findByRequestId(requestId).size();',
    'String roundSuffix = failureRound == 0 ? "" : ":" + failureRound;',
    '"BALANCE_REFUND_REVERSAL:" + req.getRequestNo() + roundSuffix'
  ]);

  requireAll(problems, 'MA-451', sources.procurement || '', [
    'purchaseReceiveAllocationMapper.save(allocation)',
    'returnPurchaseStockByAllocation(',
    'findByPurchaseLineIdForUpdate(poLine.getLineId())',
    'allocation.getWarehouseId()',
    'allocation.getBatchNo()'
  ]);

  for (const method of ['approve', 'disposeLine', 'updateLine']) {
    const body = methodBody(sources.closeSheet || '', `public\\s+[^\\n]+\\s+${method}\\s*\\(`);
    requireAll(problems, `MA-452/${method}`, body, ['requireSheetForUpdate(closeId)']);
  }
  requireAll(problems, 'MA-452/行锁实现', sources.closeSheet || '', [
    'sheetMapper.findByIdForUpdate(closeId)'
  ]);

  const finalizeRefund = methodBody(
    sources.orderPayment || '',
    'public\\s+boolean\\s+finalizeLiveOrderRefund\\s*\\([^)]*channelRefundStatus\\)'
  );
  requireAll(problems, 'MA-453', finalizeRefund, [
    '"PROCESSING".equalsIgnoreCase(channelRefundStatus)',
    'recordProcessingRefund(locked, prep)',
    'return false;'
  ]);
  requireOrder(
    problems,
    'MA-453/先判中间态',
    finalizeRefund,
    '"PROCESSING".equalsIgnoreCase(channelRefundStatus)',
    'locked.setRefundedCents('
  );
  requireAll(problems, 'MA-453/待确认状态', sources.orderPayment || '', [
    '"REFUND_PROCESSING"',
    'confirmProcessingOrderRefund('
  ]);

  const refreshSplit = methodBody(
    sources.profitSharing || '',
    'public\\s+OrderRevenueSplit\\s+refreshSplitStatus\\s*\\('
  );
  requireAll(problems, 'MA-454', refreshSplit, [
    'VOIDED.equalsIgnoreCase(split.getStatus())',
    '"REVERSED".equalsIgnoreCase(split.getStatus())',
    'return split;'
  ]);
  requireOrder(
    problems,
    'MA-454/终态优先',
    refreshSplit,
    'VOIDED.equalsIgnoreCase(split.getStatus())',
    'isApiReady()'
  );

  requireAll(problems, 'MA-455', sources.coupon || '', [
    'uc != null && uc.getDiscountCents() != null',
    'Math.max(0, uc.getDiscountCents())',
    'releasePromotionBudgetIfAny(def, actualDiscountCents);'
  ]);
  const partialCoupon = methodBody(
    sources.coupon || '',
    'public\\s+int\\s+settleAfterPartialRefund\\s*\\('
  );
  if (/userCouponRepository\.save\(uc\)/.test(partialCoupon)) {
    problems.push('MA-455: 部分退不得改写 user_coupon 核销预算占用');
  }

  if (
    /@Transactional(?:\([^)]*\))?\s+public\s+RevenueSplitDto\s+refreshWeChatProfitSharing\s*\(/.test(
      sources.merchant || ''
    )
  ) {
    problems.push('MA-456: refreshWeChatProfitSharing 不得包外层事务');
  }
  requireAll(problems, 'MA-456/入口存在', sources.merchant || '', [
    'public RevenueSplitDto refreshWeChatProfitSharing('
  ]);

  requireAll(problems, 'MA-457', sources.notification || '', [
    'boolean failed = false;',
    'failed |= wechatSendFailed(msg);',
    'if (failed && channelSwitch.asyncEnabled())',
    'throw new IllegalStateException("notification dispatch failed"'
  ]);
  requireOrder(
    problems,
    'MA-457/双渠道都尝试',
    sources.notification || '',
    'failed |= wechatSendFailed(msg);',
    'sendSms(msg);'
  );

  requireOrder(
    problems,
    'MA-458/先登记',
    sources.modelPush || '',
    'commandTracker.register(',
    'deviceWebSocketHandler.sendModelUpdate('
  );
  requireAll(problems, 'MA-458/发送失败清理', sources.modelPush || '', [
    'if (!sent)',
    'commandTracker.evict('
  ]);

  requireAll(problems, 'MA-459', sources.closeDetail || '', [
    'Map<String, List<WarehouseOutboundLine>> lineByKey',
    'int[] shipShares = apportionByLineQuantity(totalShip, lines);',
    'int[] cancelShares = apportionByLineQuantity(totalCancel, lines);',
    'String deviceId = blankToNull(line.getDeviceId());'
  ]);
  requireAll(problems, 'MA-460/nonce解析', sources.payNotify || '', [
    'assertNonceOnce(nonce);',
    'releaseNonce(nonce);'
  ]);
  requireOrder(
    problems,
    'MA-460/nonce占位后失败归还',
    sources.payNotify || '',
    'assertNonceOnce(nonce);',
    'releaseNonce(nonce);'
  );
  if (/verifyOrRelease\(/.test(sources.payoutNotify || '')) {
    problems.push('MA-460: 转账回调不得在验签/REPLAY 外层统一归还 nonce');
  }
  requireAll(problems, 'MA-460/CAS失败不吞', sources.payScoreOrder || '', [
    'throw new IllegalStateException("wechat payscore order lost state CAS race 3 times:',
    'throw new IllegalStateException("wechat payscore order disappeared during state transition:'
  ]);

  requireAll(problems, 'MA-461/历史回填', sources.procurement || '', [
    'validateAllocationTotals(line, allocations);',
    '采购收货分配缺失或存在歧义'
  ]);
  requireAll(problems, 'MA-461/回填SQL', sources.migration510 || '', [
    'AND pol.sku_id = m.sku_id',
    'UPDATE purchase_receive_allocation a',
    'SET returned_qty = r.returned_qty'
  ]);
  if (/AND\s+pol\.batch_no\s*=\s*m\.batch_no/.test(sources.migration510 || '')) {
    problems.push('MA-461: 历史回填不得要求订单行当前批次等于历史收货批次');
  }
  requireAll(problems, 'MA-461/历史批次优先', sources.migration510 || '', [
    'batch_match_count',
    'match_priority = 1 AND batch_match_count = 1',
    'batch_match_count = 0 AND sku_match_count = 1'
  ]);
  requireAll(problems, 'MA-461/月结幻写锁', sources.closeSheet || '', [
    'sheetMapper.lockWarehouseMonth(closeLockKey(wh, normalized));',
    'findByWarehouseMonthForUpdate(warehouseId, computed.yearMonth())'
  ]);

  requireAll(problems, 'MA-462/SKU批次判据', sources.inventoryLot || '', [
    'if (hasLotForSku(dev, sku))',
    'return sellableQuantity(dev, sku);'
  ]);
  const finalizePayout = methodBody(
    sources.withdraw || '',
    'public\\s+MerchantWithdrawRequestDto\\s+finalizePayout\\s*\\('
  );
  requireAll(problems, 'MA-462/打款CAS', finalizePayout, [
    'markAwaitingUserConfirm(',
    'finalizeIfPaying(',
    'markPayoutFailed(',
    'if (finalized == 0)'
  ]);

  requireAll(problems, 'MA-463/退款上下文', sources.refundResume || '', [
    'resumePendingCompletedRefunds(',
    'resumeOrderRefund(',
    'finalizeWaiveRefund(',
    'resumeLocalPartialRefund(',
    'recordLegacyAbnormalTrace('
  ]);
  requireAll(problems, 'MA-463/充值退款状态', sources.rechargeRefund || '', [
    'confirmRechargeChannelRefund(',
    'confirmChannelSliceSuccess(',
    'failChannelSlice(',
    'markRechargeRefundContextDone('
  ]);
  requireAll(problems, 'MA-463/切片在途不冲回', sources.balanceRefund || '', [
    'if (probe == PaymentService.SliceRefundProbe.PROCESSING)',
    'if (probe == PaymentService.SliceRefundProbe.UNKNOWN)'
  ]);
  requireAll(problems, 'MA-463/充值幂等与在途', sources.rechargePayment || '', [
    'SliceRefundProbe { ACCEPTED, PROCESSING, NOT_FOUND, UNKNOWN }',
    'return SliceRefundProbe.PROCESSING;',
    'confirmRechargeChannelRefund(',
    'updateRefundContextJson(',
    '"CHANNEL_FINALIZED"',
    '"CHANNEL_FAILED"'
  ]);
  return problems;
}

function main() {
  const sources = {};
  for (const [key, rel] of Object.entries(FILES)) {
    const full = path.join(ROOT, rel);
    if (!fs.existsSync(full)) {
      console.error(`[check-ma449-459-contracts] FAIL: 缺少 ${rel}`);
      process.exit(1);
    }
    sources[key] = fs.readFileSync(full, 'utf8');
  }
  const problems = findMa449459Violations(sources);
  if (problems.length) {
    for (const problem of problems) console.error(`[check-ma449-459-contracts] FAIL: ${problem}`);
    process.exit(1);
  }
  console.log('[check-ma449-459-contracts] OK: MA-449–463 静态契约全部在位');
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) main();
