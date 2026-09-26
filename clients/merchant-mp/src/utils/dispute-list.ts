/**
 * 争议列表分页 / SLA / 结案确认文案（debt-tracker M7 → M7b）。
 * 纯函数：禁止依赖 uni / merchantApi。
 */
const PLAYABLE_MEDIA_RE = /^(https?:|blob:|data:|file:)/i;

export function playablePlaybackUrl(videoPreviewUrl?: string | null): string {
  const url = String(videoPreviewUrl || '').trim();
  return PLAYABLE_MEDIA_RE.test(url) ? url : '';
}

/** 争议列表首屏/翻页大小；禁止 ≥100（对齐 orders=50、splits≤20 lessons）。 */
export const DISPUTES_PAGE_SIZE = 50;

/** 深链按 sessionId 扫描时最多翻页数（防一次拉全量）。 */
export const DISPUTES_FOCUS_SCAN_MAX_PAGES = 5;

export type DisputeListItem = { ticketId?: string; sessionId?: string };

export type DisputesPageResponse<T extends DisputeListItem> = T[] | { items?: T[]; total?: number };

export function normalizeDisputesPageItems<T extends DisputeListItem>(
  res: DisputesPageResponse<T>
): T[] {
  return Array.isArray(res) ? res : res?.items || [];
}

export function normalizeDisputesTotal<T extends DisputeListItem>(
  res: DisputesPageResponse<T>,
  itemCount: number
): number {
  if (Array.isArray(res)) return itemCount;
  return Number(res?.total ?? itemCount);
}

/** 首屏重置列表（pageIndex=0）。 */
export function applyDisputesFirstPage<T extends DisputeListItem>(
  res: DisputesPageResponse<T>
): { list: T[]; total: number; pageIndex: number; hasMore: boolean } {
  const list = normalizeDisputesPageItems(res);
  const total = normalizeDisputesTotal(res, list.length);
  return {
    list,
    total,
    pageIndex: 0,
    hasMore: list.length < total
  };
}

/** 追加一页：去重 ticketId，更新 hasMore。空页不改写 total。 */
export function appendDisputePageItems<T extends DisputeListItem>(input: {
  list: T[];
  pageIndex: number;
  res: DisputesPageResponse<T>;
  pageSize: number;
  previousTotal: number;
}): { list: T[]; total: number; pageIndex: number; hasMore: boolean; appended: number } {
  const items = normalizeDisputesPageItems(input.res);
  if (!items.length) {
    return {
      list: input.list,
      total: input.previousTotal,
      pageIndex: input.pageIndex,
      hasMore: false,
      appended: 0
    };
  }
  const seen = new Set(input.list.map((t) => t.ticketId).filter(Boolean));
  const appendedRows = items.filter((t) => t.ticketId && !seen.has(t.ticketId));
  const list = input.list.concat(appendedRows);
  const total = normalizeDisputesTotal(input.res, list.length);
  return {
    list,
    total,
    pageIndex: input.pageIndex,
    hasMore: list.length < total && items.length >= input.pageSize,
    appended: appendedRows.length
  };
}

/**
 * 列表卡 SLA 文案。
 * @param resolvedLabel 已结案时展示（通常 displayLabel('dispute_status','RESOLVED')）
 * @param processingLabel 无剩余小时时（通常 displayLabel('order_status','PROCESSING')）
 */
export function disputeSlaListLabel(
  item: { slaOverdue?: boolean | null; slaHoursRemaining?: number | null },
  isTerminal: boolean,
  resolvedLabel: string,
  processingLabel: string
): string {
  if (isTerminal) return resolvedLabel;
  if (item.slaOverdue) return '已超时';
  if (item.slaHoursRemaining == null) return processingLabel;
  return `剩余 ${item.slaHoursRemaining} 小时`;
}

/** 详情行 SLA 文案（无结案态）。 */
export function disputeSlaDetailLabel(item: {
  slaOverdue?: boolean | null;
  slaHoursRemaining?: number | null;
}): string {
  if (item.slaOverdue) return '已超时';
  if (item.slaHoursRemaining != null) return `剩余 ${item.slaHoursRemaining} 小时`;
  return '暂无';
}

/** 结案确认框正文（写路径仍走 money-ui-contracts body）。 */
export function merchantDisputeResolveConfirmContent(
  type: 'KEEP' | 'WAIVE' | 'CONFIRM',
  typeLabel: string
): string {
  if (type === 'WAIVE') {
    return '确认免单并原路退款？货已离柜请选「仅退款」逻辑由系统按默认处理。';
  }
  return `确认${typeLabel}？`;
}
