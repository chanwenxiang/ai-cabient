#!/usr/bin/env node
/**
 * 门禁：消费者争议文案不得泄漏「｜设备原文：」运维尾巴。
 *
 * 成因（2026-11-20 订单页真机截图）：后端 DisputeConsumerCopy.reviewCopy 直接把
 * ticket.reason 塞进 consumerReviewDetail，而 reason 含将邑上报的 classesVersion 等调试值；
 * C 端 dispute-copy.ts 只对 fallback 分支剥离，派生字段分支原样透传 ⇒ 顾客看到设备原文。
 *
 * 双端判据：后端派生前剥 + 前端派生字段再剥一道（传输层不可信），且完整 reason 仍保留在 DTO。
 * 用法：node scripts/check-dispute-vendor-tail.mjs
 */
import { readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
export const BACKEND_FILE = join(
  ROOT,
  'services/common/common-core/src/main/java/com/aicabinet/common/util/DisputeConsumerCopy.java'
);
export const FRONTEND_FILE = join(ROOT, 'clients/consumer-mp/src/utils/dispute-copy.ts');

/** 等长空格替换注释，保行号；本仓所有静态门禁统一口径。 */
export function maskComments(src) {
  let out = '';
  let i = 0;
  while (i < src.length) {
    if (src.startsWith('//', i)) {
      while (i < src.length && src[i] !== '\n') {
        out += ' ';
        i++;
      }
    } else if (src.startsWith('/*', i)) {
      out += '  ';
      i += 2;
      while (i < src.length && !src.startsWith('*/', i)) {
        out += src[i] === '\n' ? '\n' : ' ';
        i++;
      }
      if (i < src.length) {
        out += '  ';
        i += 2;
      }
    } else {
      out += src[i];
      i++;
    }
  }
  return out;
}

export function backendProblems(source) {
  const src = maskComments(source);
  const problems = [];
  if (!/String\s+raw\s*=\s*stripVendorDetail\s*\(/.test(src)) {
    problems.push('后端 reviewCopy 的 raw 未经 stripVendorDetail 直接派生消费者文案');
  }
  if (!/stripVendorDetail\s*\([^)]*\)\s*\{[\s\S]*?｜设备原文：/.test(src)) {
    problems.push('后端 stripVendorDetail 实现丢失「｜设备原文：」锚点');
  }
  if (!/resolvedDetail\s*\(\s*raw\s*,/.test(src)) {
    problems.push('后端结案详情未复用已剥离的 raw');
  }
  return problems;
}

export function frontendProblems(source) {
  const src = maskComments(source);
  const problems = [];
  if (!/function\s+stripVendorTail[\s\S]{0,240}?｜设备原文：/.test(src)) {
    problems.push('前端 stripVendorTail 实现丢失「｜设备原文：」锚点');
  }
  if (!/stripVendorTail\s*\(\s*ticket\.consumerReviewDetail\s*\)/.test(src)) {
    problems.push('前端派生字段 consumerReviewDetail 未过 stripVendorTail');
  }
  if (!/stripVendorTail\s*\(\s*ticket\.consumerReviewTitle\s*\)/.test(src)) {
    problems.push('前端派生字段 consumerReviewTitle 未过 stripVendorTail');
  }
  if (!/localizeConsumerDisputeReason[\s\S]{0,240}?stripVendorTail\s*\(/.test(src)) {
    problems.push('前端 localizeConsumerDisputeReason 未剥离设备原文');
  }
  if (
    !/resolvedConsumerDetail[\s\S]{0,600}?stripVendorTail\s*\(\s*localizeDisputeReason\s*\(/.test(
      src
    )
  ) {
    problems.push('前端结案详情 reason 未剥离设备原文');
  }
  return problems;
}

export function run() {
  const problems = [
    ...backendProblems(readFileSync(BACKEND_FILE, 'utf8')).map((p) => `backend: ${p}`),
    ...frontendProblems(readFileSync(FRONTEND_FILE, 'utf8')).map((p) => `frontend: ${p}`)
  ];
  if (problems.length) {
    console.error('[check-dispute-vendor-tail] FAIL:\n' + problems.join('\n'));
    process.exit(1);
  }
  console.log('[check-dispute-vendor-tail] OK：双端消费者文案均剥设备原文，完整 reason 仍留源数据');
}

if (process.argv[1] && resolve(process.argv[1]) === resolve(fileURLToPath(import.meta.url))) {
  run();
}
