#!/usr/bin/env node
/**
 * 门禁：幂等/重试窗口 F6 剩余三项（BR4/BR6/BR7）。
 *
 * R1 REFUND_INITIATED 置 FAILED 前必须先查渠道（UNKNOWN 跳过，SUCCESS 走闭环）
 * R2 会话取消按 sessionId(out_order_no) 找支付分单（V496 后开门期单没有 orderId）
 * R3 不可回滚的调度任务不得由 XXL 失败重试重复执行（executor_fail_retry_count=0）
 */
import { readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = process.env.GATE_ROOT
  ? resolve(process.env.GATE_ROOT)
  : resolve(dirname(fileURLToPath(import.meta.url)), '..');
const SCHED =
  'services/trade-service/src/main/java/com/aicabinet/trade/service/RefundRetryScheduler.java';
const SESSION =
  'services/trade-service/src/main/java/com/aicabinet/trade/service/SessionService.java';
const SEED = 'infra/xxl-job/seed_aicabinet_jobs.sql';

function methodBody(src, sig) {
  const at = src.indexOf(sig);
  if (at < 0) return '';
  const open = src.indexOf('{', at + sig.length);
  let depth = 0;
  for (let i = open; i < src.length; i++) {
    if (src[i] === '{') depth++;
    else if (src[i] === '}') {
      depth--;
      if (depth === 0) return src.slice(open + 1, i);
    }
  }
  return '';
}

export function evaluate(read) {
  const errors = [];
  const sched = read(SCHED);
  const body = methodBody(sched, 'private int exhaustOverdue()');
  if (!body) errors.push('R0 找不到 exhaustOverdue——扫描面塌了');
  else {
    const q = body.indexOf('queryInitiatedRefundStatus(');
    const mark = body.indexOf('markRefundFailed(');
    if (q < 0) errors.push('R1 置失败前不查渠道——渠道已退款成功会被误标失败');
    else if (mark >= 0 && q > mark) errors.push('R1 查单排在 markRefundFailed 之后——等于没查');
    if (!/UNKNOWN/.test(body)) errors.push('R1 查单 UNKNOWN 时未跳过——瞬断会被当成失败');
  }
  const session = read(SESSION);
  const cancel = methodBody(session, 'private void cancelPayScoreOrdersQuietly(');
  if (!/findByOutOrderNo\s*\(/.test(cancel)) {
    errors.push('R2 会话取消仍按 orderId 找支付分单——开门期单（order_id 空）漏取消');
  }
  const seed = read(SEED);
  for (const job of ['profitSharingRetryJob', 'merchantDailyIncomeNotifyJob']) {
    const line = seed.split(/\r?\n/).find((l) => l.includes(`'${job}'`));
    if (!line) errors.push(`R3 种子缺任务 ${job}——扫描面塌了`);
    else if (!/,\s*0,\s*'BEAN'/.test(line)) {
      errors.push(`R3 ${job} 的失败重试次数不是 0——外部动作会被重复执行`);
    }
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
    console.error(`[check-retry-window] ${errors.length} 项失败：`);
    for (const e of errors) console.error(`  - ${e}`);
    process.exitCode = 1;
  } else {
    console.log('[check-retry-window] OK：查单后判失败 / 按 sessionId 取消 / 非幂等任务不重试');
  }
}
