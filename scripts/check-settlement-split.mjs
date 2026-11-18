#!/usr/bin/env node
/**
 * 门禁：结算链必须「prepare 短事务 → 渠道 HTTP 事务外 → finalize 短事务」（F3b）。
 *
 * 旧形态把整段落单+渠道扣款包在一个 @Transactional 里：HTTP 期间一直持会话/订单行锁，
 * 弱网时锁被钉住；重试换 orderId 还会再扣一次（F6 已从键上堵住，但不能靠它兜底）。
 *
 * R1 finalizeOrder 自身不带 @Transactional，且按 prepare → charge → finalize 顺序调用
 * R2 prepareSettlement / finalizePaidSettlement 必须是 REQUIRES_NEW
 * R3 编排器决策方法 REQUIRES_NEW，processRecognitionResultInTx 不再带 @Transactional
 * R4 settleManual 不再带 @Transactional
 * R5 争议首扣腿 prepareConfirmDispute 不带 @Transactional，改单准备 prepareAdjustInTx REQUIRES_NEW
 * R6 一致性修复：fixInconsistencyDetailed 不带事务，DB 修复/标记 FIXED 各自 REQUIRES_NEW，退款在事务外
 */
import { readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = process.env.GATE_ROOT
  ? resolve(process.env.GATE_ROOT)
  : resolve(dirname(fileURLToPath(import.meta.url)), '..');
const P = 'services/trade-service/src/main/java/com/aicabinet/trade/service/';
const FILES = {
  fin: P + 'SettlementOrderFinalizeService.java',
  orch: P + 'SettlementSettleOrchestrator.java',
  settle: P + 'SettlementService.java',
  dispute: P + 'SettlementConfirmDisputeService.java',
  dc: P + 'DataConsistencyService.java'
};

function slice(src, sig, len = 2600) {
  const at = src.indexOf(sig);
  return at < 0 ? '' : src.slice(Math.max(0, at - 300), at + len);
}
const hasReqNew = (chunk) => /REQUIRES_NEW/.test(chunk);
/** 注释里的 `@Transactional` 只是说明文字，必须遮蔽后再判注解（等长空格保行号）。 */
function maskComments(src) {
  return src
    .replace(/\/\*[\s\S]*?\*\//g, (m) => m.replace(/[^\n]/g, ' '))
    .replace(/\/\/[^\n]*/g, (m) => ' '.repeat(m.length));
}
/**
 * 取紧贴方法声明的 @Transactional 注解文本；方法体已结束（出现 `}`）说明那是上一个方法的注解。
 * 注解参数里的 `{...}`（noRollbackFor 数组）先抹掉再判断，避免把参数花括号误认成方法体。
 */
const annotationFor = (src, sig) => {
  const at = src.indexOf(sig);
  if (at < 0) return null;
  const window = src.slice(Math.max(0, at - 500), at);
  const idx = window.lastIndexOf('@Transactional');
  if (idx < 0) return null;
  const tail = window.slice(idx);
  // 出现 `名字(...) {` 说明夹着上一个方法声明（注解参数里的 {...} 在 `)` 之后，不会命中）。
  return /\w+\s*\([^)]*\)\s*\{/.test(tail) ? null : tail;
};
const annotated = (src, sig) => annotationFor(src, sig) !== null;
const reqNew = (src, sig) => {
  const tail = annotationFor(src, sig);
  return tail !== null && tail.includes('REQUIRES_NEW');
};

export function evaluate(rawRead) {
  const errors = [];
  const read = (rel) => maskComments(rawRead(rel));
  const fin = read(FILES.fin);
  const finalize = slice(fin, 'OrderReadModel finalizeOrder(ShoppingSession session,');
  if (!finalize) errors.push('R0 找不到 finalizeOrder');
  else {
    if (annotated(fin, 'OrderReadModel finalizeOrder(ShoppingSession session,'))
      errors.push('R1 finalizeOrder 仍带 @Transactional');
    const p = finalize.indexOf('prepareSettlement(');
    const c = finalize.indexOf('chargeOrder(');
    const f = finalize.indexOf('finalizePaidSettlement(');
    if (p < 0 || c < 0 || f < 0 || !(p < c && c < f)) {
      errors.push('R1 finalizeOrder 不是 prepare → charge → finalize 顺序');
    }
  }
  if (!reqNew(fin, 'SettlementPrep prepareSettlement(')) {
    errors.push('R2 prepareSettlement 不是 REQUIRES_NEW');
  }
  if (!reqNew(fin, 'OrderReadModel finalizePaidSettlement(')) {
    errors.push('R2 finalizePaidSettlement 不是 REQUIRES_NEW');
  }

  const orch = read(FILES.orch);
  if (!reqNew(orch, 'RecognitionPlan decideRecognition(')) {
    errors.push('R3 decideRecognition 不是 REQUIRES_NEW');
  }
  if (annotated(orch, 'public OrderReadModel processRecognitionResultInTx(')) {
    errors.push('R3 processRecognitionResultInTx 仍带 @Transactional');
  }

  const settle = read(FILES.settle);
  if (annotated(settle, 'public OrderReadModel settleManual(')) {
    errors.push('R4 settleManual 仍带 @Transactional');
  }

  const dispute = read(FILES.dispute);
  if (annotated(dispute, 'public ConfirmDisputePrep prepareConfirmDispute(')) {
    errors.push('R5 prepareConfirmDispute 仍带 @Transactional');
  }
  if (!reqNew(dispute, 'public ConfirmDisputePrep prepareAdjustInTx(')) {
    errors.push('R5 prepareAdjustInTx 不是 REQUIRES_NEW');
  }

  const dc = read(FILES.dc);
  if (annotated(dc, 'private FixOutcome doFixInconsistencyDetailed(')) {
    errors.push('R6 doFixInconsistencyDetailed 仍带 @Transactional');
  }
  if (!reqNew(dc, 'public FixPlan applyFixInTx(')) errors.push('R6 applyFixInTx 不是 REQUIRES_NEW');
  if (!reqNew(dc, 'public void markFixedInTx(')) errors.push('R6 markFixedInTx 不是 REQUIRES_NEW');
  const refund = dc.slice(dc.indexOf('private FixOutcome doRefundOutsideTx('));
  if (!/refundOrder\s*\(/.test(refund)) errors.push('R6 退款没有在 doRefundOutsideTx 里执行');
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
    console.error(`[check-settlement-split] ${errors.length} 项失败：`);
    for (const e of errors) console.error(`  - ${e}`);
    process.exitCode = 1;
  } else {
    console.log('[check-settlement-split] OK：结算/争议均为 prepare → charge(事务外) → finalize');
  }
}
