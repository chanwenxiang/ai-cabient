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

/**
 * 备货提交明细指纹（2026-11-10 审计 P0-3 残留）：skuId+qty 集合（与行序无关）= 同一意图。
 * 为什么需要：成功后键保留，后端按 key 回放**不核对明细**（MerchantReplenishmentService
 * 回放分支直接返回原任务）；用户改量后重提，旧任务会被当结果返回、改量被静默吞掉。
 * 页面级记录「上次成功提交的指纹」，重提指纹不同 ⇒ 显式拦截并指引去任务页改量，
 * 而不是换新键（传输未知场景换新键会重新引入重复建任务 + 重复扣分仓库存）。
 */
export function prepSubmitFingerprint(lines: { skuId: string; qty: number }[]): string {
  return lines
    .map((l) => `${l.skuId}:${l.qty}`)
    .sort()
    .join('|');
}

/**
 * 后端补货任务的**终态**（任务已结束，改量重提是新意图）。
 *
 * 只认明确终态——`null` / 未知值一律按「还可能活着」处理，见 `shouldBlockPrepResubmit`。
 * 与后端 `MerchantReplenishmentService.selfInitiate` 的回放闸对齐：CANCELLED 那里直接 409
 *（「上次提交的补货任务已取消」），COMPLETED 按 V401 契约回放原任务。
 */
export const PREP_TASK_ENDED_STATUSES = ['CANCELLED', 'COMPLETED', 'FAILED', 'REJECTED'] as const;

/** 改量重提的处置：照旧提交 / 拦下并指引改量 / 旧任务已结束⇒作废旧键后提交 */
export type PrepResubmitVerdict =
  /** 本页没成功提交过，或量没变（同一意图）⇒ 直接提交（复用旧键，后端回放原任务） */
  | 'proceed'
  /** 该柜上次提交成功、本次改了量、且那张任务还在进行中 ⇒ 拦下，让用户去任务页改量 */
  | 'block_changed_with_active_task'
  /** 同上，但那张任务已结束（已取消/已完成）⇒ 旧键作废、按新意图重新生成任务 */
  | 'renew_after_ended_task';

/**
 * 备货单「改量重提」判据（R2-B1 残留，2026-11-10）。
 *
 * 背景：成功后幂等键保留（否则部分失败重提会重复建任务），而后端回放**不核对明细** ⇒
 * 改量后重提会被回放成旧数量（静默吞掉改量）。故前端记着上次成功的明细指纹，指纹变了就拦。
 *
 * 但「拦」只在**那张任务还活着**时成立。任务被取消后再改量重提，去任务页根本没有可改的任务
 * ⇒ 提示把用户锁死（P0-3 修复引入的次生缺陷）。此时应作废旧键、按新意图真提一次。
 *
 * 🔴 `lastTaskStatus` 为 `null` 时**必须按「还在进行中」拦**，不能放行：
 * 常见路径是「提交成功 → 部分柜失败 → 留在本页改量重提」，而预览是**提交前**加载的
 * ⇒ 那时本柜还没有任务，`lastTaskStatus` 天然是 null。按 null 放行等于把 P0-3 的修复整个废掉
 * （重复建任务 + 重复扣分仓库存）。只有预览里明确读到终态才放行。
 */
export function shouldBlockPrepResubmit(input: {
  /** 本页上次成功提交的指纹；undefined=本页没成功提交过 */
  prevFingerprint: string | undefined;
  /** 本次要提交的明细指纹 */
  fingerprint: string;
  /** 预览里本柜最近一张任务的状态；null/undefined=预览时该柜没有任务（或未返回） */
  lastTaskStatus?: string | null;
}): PrepResubmitVerdict {
  if (input.prevFingerprint === undefined) return 'proceed';
  if (input.prevFingerprint === input.fingerprint) return 'proceed';
  const status = (input.lastTaskStatus ?? '').trim().toUpperCase();
  if ((PREP_TASK_ENDED_STATUSES as readonly string[]).includes(status)) {
    return 'renew_after_ended_task';
  }
  return 'block_changed_with_active_task';
}

/**
 * 「改量重提」提交时实时复查（2026-11-10，收口 R2-B1 的快照竞态残余）。
 *
 * `lastTaskStatus` 是**预览那一刻**的快照：若用户在「预览加载后、点提交前」才取消任务，
 * 快照仍说进行中 ⇒ 误拦一次。这里只在「快照判拦」的罕见分支上，用**现成预览接口**
 * （已带 lastTaskStatus，无需新 API）取新鲜状态重判：已结束 ⇒ 作废旧键重提；仍进行中 ⇒ 维持拦截。
 *
 * 🔴 只在 `prevFingerprint` 已存在（本页已成功提交过、任务确实建出来了）时才可能走到复查，
 * 此时新鲜状态是权威的；初始快照的「null ⇒ 拦」规则不受影响（保护部分失败重提主路径）。
 * 🔴 复查失败（网络）⇒ 维持原拦截（fail-closed），不静默放行。
 */
export async function decidePrepResubmitWithRefresh(
  input: {
    prevFingerprint: string | undefined;
    fingerprint: string;
    lastTaskStatus?: string | null;
  },
  deviceId: string,
  refreshStatus: (deviceId: string) => Promise<string | null>
): Promise<PrepResubmitVerdict> {
  const first = shouldBlockPrepResubmit(input);
  if (first !== 'block_changed_with_active_task') return first;
  try {
    const fresh = await refreshStatus(deviceId);
    return shouldBlockPrepResubmit({ ...input, lastTaskStatus: fresh });
  } catch {
    return first;
  }
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
