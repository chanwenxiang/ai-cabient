/**
 * 自主补货提交门闩（直接生效，无审批）。
 * 纯函数：禁止依赖 uni / merchantApi；submit API 在 self-replenish 页面。
 */
import type { ReplenishDraftLine } from '@/utils/self-replenish-draft';

/** 选中行 → 提交明细（body lines 元素：{ skuId, qty }，对齐后端 MerchantSelfReplenishLine） */
export function selectedReplenishLines(
  draftLines: ReplenishDraftLine[]
): { skuId: string; qty: number }[] {
  return draftLines
    .filter((l) => l.selected && l.qty > 0)
    .map((l) => ({ skuId: l.skuId, qty: l.qty }));
}

/**
 * V401 clientRequestNo 幂等契约（2026-10-23 审计残留 H2 收口）：
 * 同一提交意图生成一次键；**传输类错误（超时）后复用同键重试**——服务端按键幂等返回原任务；
 * 业务失败 / 成功后清空 ⇒ 下次提交生成新键（新意图）。
 * 生成规则：`self-` + 时间戳 + 随机尾（后端上限 64 字符，此格式远小于上限）。
 */
export function newSelfReplenishRequestNo(
  now = Date.now(),
  rand: () => number = Math.random
): string {
  const tail = Math.floor(rand() * 36 ** 8)
    .toString(36)
    .padStart(8, '0');
  return `self-${now.toString(36)}-${tail}`;
}

/** 重试语义：传输类错误保留键（幂等重放）；其余（成功/业务失败）清空。 */
export function nextRequestNoAfterSubmit(
  current: string,
  outcome: 'success' | 'business_error' | 'transport_error'
): string {
  return outcome === 'transport_error' ? current : '';
}

export function buildSelfReplenishBody(input: {
  deviceId: string;
  notes: string;
  lines: { skuId: string; qty: number }[];
  clientRequestNo: string;
}): import('@aicabinet/shared-types').OpenApiMerchantSelfReplenishRequest & {
  clientRequestNo: string;
} {
  return {
    deviceId: input.deviceId,
    notes: input.notes.trim() || undefined,
    lines: input.lines,
    clientRequestNo: input.clientRequestNo
  };
}

export function canStartSelfReplenish(input: {
  canSubmit: boolean;
  submitting: boolean;
  lineCount: number;
}): 'ok' | 'blocked' | 'no_lines' {
  if (!input.canSubmit || input.submitting) return 'blocked';
  if (input.lineCount <= 0) return 'no_lines';
  return 'ok';
}

/** 切换选中：选中且 qty≤0 时用 suggestQty 兜底；返回需 toast 的文案（无则 null）。 */
export function applyToggleDraftLine(line: ReplenishDraftLine): string | null {
  line.selected = !line.selected;
  if (line.selected && line.qty <= 0) {
    line.qty = line.suggestQty > 0 ? line.suggestQty : 0;
    if (line.qty <= 0) return '请填写补货数量';
  }
  return null;
}

export function applyAdjustDraftQty(line: ReplenishDraftLine, delta: number): void {
  const next = Math.max(0, (line.qty || 0) + delta);
  line.qty = next;
  line.selected = next > 0;
}

export function requestActionErrorMessage(e: unknown, fallback: string): string {
  return e instanceof Error ? e.message : fallback;
}
