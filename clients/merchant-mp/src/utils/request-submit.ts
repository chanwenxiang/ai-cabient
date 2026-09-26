/**
 * 要货申请提交 / 证据门闩（debt-tracker M10c）。
 * 纯函数：禁止依赖 uni / merchantApi；上传与 submit API 仍在 request.vue。
 */
import type { RequestDraftLine } from '@/utils/request-draft';

export const REQUEST_EVIDENCE_MAX = 5;

export type RequestEvidenceItem = { localPath: string; fileId?: number };

export function selectedRequestLines(
  draftLines: RequestDraftLine[]
): { skuId: string; requestedQty: number }[] {
  return draftLines
    .filter((l) => l.selected && l.qty > 0)
    .map((l) => ({ skuId: l.skuId, requestedQty: l.qty }));
}

export function extractEvidenceFileIds(items: RequestEvidenceItem[]): number[] {
  return items
    .map((item) => item.fileId)
    .filter((id): id is number => typeof id === 'number' && id > 0);
}

export function buildSubmitReplenishmentRequestBody(input: {
  deviceId: string;
  notes: string;
  lines: { skuId: string; requestedQty: number }[];
  evidenceItems: RequestEvidenceItem[];
}): {
  deviceId: string;
  notes?: string;
  lines: { skuId: string; requestedQty: number }[];
  evidenceFileIds?: number[];
} {
  const evidenceFileIds = extractEvidenceFileIds(input.evidenceItems);
  return {
    deviceId: input.deviceId,
    notes: input.notes.trim() || undefined,
    lines: input.lines,
    evidenceFileIds: evidenceFileIds.length ? evidenceFileIds : undefined
  };
}

export function canStartRequestSubmit(input: {
  canSubmit: boolean;
  submitting: boolean;
  lineCount: number;
}): 'ok' | 'blocked' | 'no_lines' {
  if (!input.canSubmit || input.submitting) return 'blocked';
  if (input.lineCount <= 0) return 'no_lines';
  return 'ok';
}

export function canAddRequestEvidence(input: {
  canRequest: boolean;
  currentCount: number;
  max?: number;
}): 'ok' | 'denied' | 'full' {
  if (!input.canRequest) return 'denied';
  const max = input.max ?? REQUEST_EVIDENCE_MAX;
  if (input.currentCount >= max) return 'full';
  return 'ok';
}

export function remainingEvidenceSlots(currentCount: number, max = REQUEST_EVIDENCE_MAX): number {
  return Math.max(0, max - currentCount);
}

/** 切换选中：选中且 qty≤0 时用 suggestQty 兜底；返回需 toast 的文案（无则 null）。 */
export function applyToggleDraftLine(line: RequestDraftLine): string | null {
  line.selected = !line.selected;
  if (line.selected && line.qty <= 0) {
    line.qty = line.suggestQty > 0 ? line.suggestQty : 0;
    if (line.qty <= 0) return '请填写要货数量';
  }
  return null;
}

export function applyAdjustDraftQty(line: RequestDraftLine, delta: number): void {
  const next = Math.max(0, (line.qty || 0) + delta);
  line.qty = next;
  line.selected = next > 0;
}

export function canGoReplenishFromRequest(req: {
  status?: string | null;
  replenishmentTaskId?: number | null;
}): boolean {
  return req.status === 'ACCEPTED' && !!req.replenishmentTaskId;
}

export function requestActionErrorMessage(e: unknown, fallback: string): string {
  return e instanceof Error ? e.message : fallback;
}

export function evidencePreviewUrls(items: RequestEvidenceItem[]): string[] {
  return items.map((i) => i.localPath).filter(Boolean);
}
