/**
 * 仓库盘点页纯函数（V349）：差异原因方向集、行状态文案、录入体构造。
 * 与后端 StocktakeDiffReason（common-core）逐字对齐——方向填反服务端 400，这里先在 UI 限制可选项。
 */

export interface DiffReasonOption {
  value: string;
  label: string;
}

/** 盘亏类（账面 > 实盘，货少了）。 */
export const LOSS_REASONS: DiffReasonOption[] = [
  { value: 'NORMAL_SHRINKAGE', label: '正常损耗' },
  { value: 'MISENTRY', label: '错记' },
  { value: 'HANDLING_DAMAGE', label: '搬运破损' },
  { value: 'SUSPECTED_THEFT', label: '疑似丢失' }
];

/** 盘盈类（账面 < 实盘，货多了）。 */
export const GAIN_REASONS: DiffReasonOption[] = [
  { value: 'MISENTRY_GAIN', label: '错记（账面少记）' },
  { value: 'UNRECORDED_RETURN', label: '退货未登记' },
  { value: 'SKU_MIXUP', label: '串货' }
];

const OTHER_REASON: DiffReasonOption = { value: 'OTHER', label: '其他' };

const ALL_REASON_LABELS: Record<string, string> = Object.fromEntries(
  [...LOSS_REASONS, ...GAIN_REASONS, OTHER_REASON].map((o) => [o.value, o.label])
);

/**
 * 按差异方向给出可填的原因集：盘亏行只出现盘亏类，盘盈行只出现盘盈类，均附「其他」。
 * 不选 = 未分类（与后端语义一致：null 是要治理的问题，不强制）。
 */
export function diffReasonOptionsFor(diffQty: number): DiffReasonOption[] {
  return diffQty < 0 ? [...LOSS_REASONS, OTHER_REASON] : [...GAIN_REASONS, OTHER_REASON];
}

export function diffReasonLabel(reason: string | null | undefined): string {
  if (!reason) return '未分类';
  return ALL_REASON_LABELS[reason] ?? reason;
}

export function diffDirectionLabel(diffQty: number | null | undefined): string {
  if (diffQty == null || diffQty === 0) return '一致';
  return diffQty < 0 ? '盘亏' : '盘盈';
}

export const STOCKTAKE_STATUS_LABELS: Record<string, string> = {
  DRAFT: '待录入',
  IN_PROGRESS: '录入中',
  COMPLETED: '已完成',
  ADJUSTED: '已过账',
  CANCELLED: '已取消'
};

export function stocktakeStatusLabel(status: string | null | undefined): string {
  if (!status) return status ?? '';
  return STOCKTAKE_STATUS_LABELS[status] ?? status;
}

export function stocktakeModeLabel(mode: string | null | undefined): string {
  return mode === 'BLIND' ? '盲盘' : '明盘';
}

/** 行状态 → 展示色（mp 上用 class 后缀，不裸写业务色值）。 */
export function stocktakeLineStateClass(
  status: string | null | undefined
): 'matched' | 'diff' | 'adjusted' | 'pending' {
  switch (status) {
    case 'MATCHED':
      return 'matched';
    case 'DIFF':
      return 'diff';
    case 'ADJUSTED':
      return 'adjusted';
    default:
      return 'pending';
  }
}

export interface UpdateLineBody {
  countedQty: number;
  notes?: string;
  diffReason?: string;
}

/**
 * 构造行录入体。非法输入（负数/非整数/超长备注）返回错误文案，页面据此提示。
 * diffReason 为空串归一为 undefined（= 未分类，不传）。
 */
export function buildUpdateLineBody(
  countedQty: number | null | undefined,
  notes: string | undefined,
  diffReason: string | null | undefined
): { ok: true; body: UpdateLineBody } | { ok: false; error: string } {
  if (countedQty == null || !Number.isInteger(countedQty) || countedQty < 0) {
    return { ok: false, error: '实盘数必须是不小于 0 的整数' };
  }
  const trimmedNotes = (notes ?? '').trim();
  if (trimmedNotes.length > 80) {
    return { ok: false, error: '备注最多 80 字' };
  }
  const reason = (diffReason ?? '').trim();
  return {
    ok: true,
    body: {
      countedQty,
      notes: trimmedNotes || undefined,
      diffReason: reason || undefined
    }
  };
}

/** 列表状态筛选 tab（与后端 status 参数对齐；ALL 不传）。 */
export const STOCKTAKE_STATUS_TABS: { value: string; label: string }[] = [
  { value: 'ALL', label: '全部' },
  { value: 'DRAFT', label: '待录入' },
  { value: 'IN_PROGRESS', label: '录入中' },
  { value: 'COMPLETED', label: '已完成' },
  { value: 'ADJUSTED', label: '已过账' }
];

/** 行是否可编辑（仅 DRAFT/IN_PROGRESS 可录；COMPLETED 后只能走复盘调整）。 */
export function isLineEditable(
  stocktakeStatus: string | null | undefined,
  lineStatus: string | null | undefined
): boolean {
  if (stocktakeStatus !== 'DRAFT' && stocktakeStatus !== 'IN_PROGRESS') return false;
  return lineStatus !== 'ADJUSTED';
}
