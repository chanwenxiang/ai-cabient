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

/** 备货单逐柜提交结果：成功 / 服务端明确业务失败 / 传输类错误（结果未知）。 */
export type PrepSubmitOutcome = 'success' | 'business_error' | 'transport_error';

/**
 * 备货单（一次多柜）逐柜幂等键的存续语义 —— 与自主补货**故意不同**。
 * 自主补货是单柜、成功后离页，所以成功后清键（下次是新意图）；
 * 备货单一柜失败不影响其余柜，页面会留在本页继续重试，且 `loadPreview()` 会把已成功的柜
 * 重新勾上（`checked: items.length > 0`）。成功后清键 ⇒ 重提时该柜换新 clientRequestNo，
 * 后端按 clientRequestNo 查重失效 ⇒ 重复建任务 + 重复扣分仓库存（2026-11-10 审计 P0-3）。
 * 故：成功 / 传输未知 ⇒ 保留同键（服务端重放原任务）；服务端明确业务失败 ⇒ 清空（新意图）。
 */
export function nextPrepRequestNo(current: string, outcome: PrepSubmitOutcome): string {
  return outcome === 'business_error' ? '' : current;
}

/**
 * 取/建某柜的幂等键。store 必须是**页面级**常量——
 * 建在 submit() 里会随调用返回即销毁，等于没有幂等（P0-3 的原始形态）。
 * 已有键直接复用：同一柜在本页的重复提交/重试都视为同一意图。
 */
export function takePrepRequestNo(
  store: Map<string, string>,
  deviceId: string,
  generate: () => string = () => newSelfReplenishRequestNo()
): string {
  const existing = store.get(deviceId);
  if (existing) return existing;
  const fresh = generate();
  store.set(deviceId, fresh);
  return fresh;
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
