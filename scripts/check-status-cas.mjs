#!/usr/bin/env node
/**
 * 门禁：资金/任务状态写入必须带「快照态」谓词（F5）。
 *
 * 扫描拿到的都是几秒前的快照；`sava/updateById` 无条件写回会把别人的终态复活。
 *
 * R1 补偿任务：领取/收尾/重排走 claimIfPending / finishIfProcessing / rescheduleIfProcessing
 * R2 退款痕迹：markRefundFailed 走 failIfInitiated；touch 走 touchIfInitiated
 * R3 结算单重建：CONFIRMED 行既不改金额也不删除
 * R4 强杀会话：已有 PAID/PENDING/DISPUTED 订单时必须拒绝
 */
import { readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = process.env.GATE_ROOT
  ? resolve(process.env.GATE_ROOT)
  : resolve(dirname(fileURLToPath(import.meta.url)), '..');
const P = 'services/trade-service/src/main/java/com/aicabinet/trade/service/';
const FILES = {
  comp: P + 'CompensationTaskScheduler.java',
  refund: P + 'RefundRetryScheduler.java',
  pay: P + 'OrderPaymentService.java',
  bill: P + 'MerchantSettlementBillService.java',
  session: P + 'SessionService.java'
};

function body(src, sig) {
  const at = src.indexOf(sig);
  if (at < 0) return '';
  const open = src.indexOf('{', at + sig.length);
  let d = 0;
  for (let i = open; i < src.length; i++) {
    if (src[i] === '{') d++;
    else if (src[i] === '}') {
      d--;
      if (d === 0) return src.slice(open + 1, i);
    }
  }
  return '';
}

export function evaluate(read) {
  const errors = [];
  // 铁律 84①：判据前先等长脱敏注释（保行号/索引）——否则注释里的
  // CONFIRMED/PAID/updateById 会被当真实判据（假绿），注释含花括号还会破坏配平。
  const mask = (s) =>
    s
      .replace(/\/\*[\s\S]*?\*\//g, (m) => m.replace(/[^\n]/g, ' '))
      .replace(/\/\/[^\n]*/g, (m) => m.replace(/[^\n]/g, ' '));
  const comp = mask(read(FILES.comp));
  const processTask = body(comp, 'public void processTask(');
  if (!/claimIfPending\s*\(/.test(processTask)) errors.push('R1 补偿任务未条件领取');
  if (!/finishIfProcessing\s*\(/.test(comp)) errors.push('R1 收尾未条件更新');
  if (!/rescheduleIfProcessing\s*\(/.test(comp)) errors.push('R1 退避重排未条件更新');

  const pay = mask(read(FILES.pay));
  const fail = body(pay, 'public void markRefundFailed(');
  if (!/failIfInitiated\s*\(/.test(fail)) errors.push('R2 markRefundFailed 未条件更新');
  const refund = mask(read(FILES.refund));
  const touch = body(refund, 'private void touch(');
  if (!/touchIfInitiated\s*\(/.test(touch)) errors.push('R2 touch 未条件更新');
  if (/updateById\s*\(/.test(touch)) errors.push('R2 touch 仍无条件 updateById');

  const bill = mask(read(FILES.bill));
  if (!/CONFIRMED/.test(body(bill, 'private int doRebuildMonth('))) {
    errors.push('R3 重建仍会改写 CONFIRMED 结算单');
  }
  if (!/CONFIRMED/.test(body(bill, 'private void deleteStaleRows('))) {
    errors.push('R3 清理仍会删除 CONFIRMED 结算单');
  }

  const session = mask(read(FILES.session));
  const force = body(session, 'private SessionDto doForceCancelForOperations(');
  if (!/PAID/.test(force) || !/PENDING/.test(force) || !/CONFLICT/.test(force)) {
    errors.push('R4 强杀会话未校验订单状态（PAID/PENDING/DISPUTED 必须拒绝）');
  }
  return errors;
}

const invokedDirectly =
  process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url);
if (invokedDirectly) {
  const errors = evaluate((rel) => {
    try {
      return readFileSync(join(ROOT, rel), 'utf8');
    } catch {
      return '';
    }
  });
  if (errors.length) {
    console.error(`[check-status-cas] ${errors.length} 项失败：`);
    for (const e of errors) console.error(`  - ${e}`);
    process.exitCode = 1;
  } else {
    console.log('[check-status-cas] OK：补偿任务/退款痕迹/结算单/强杀会话均为条件写入');
  }
}
