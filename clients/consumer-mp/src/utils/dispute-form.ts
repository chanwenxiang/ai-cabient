/** 消费者账单申诉快捷选项（文案写入 reason，category 需落在后端白名单） */
export const DISPUTE_REASON_CHIPS = [
  {
    label: '没拿这个商品',
    text: '我没有拿这个商品，请核对识别结果',
    category: 'RECOGNITION',
    restoreInventory: true
  },
  {
    label: '数量不对',
    text: '商品数量识别有误，请核对',
    category: 'RECOGNITION',
    restoreInventory: true
  },
  {
    label: '重复扣款',
    text: '疑似重复扣款，请核查并退回多扣金额',
    category: 'PAYMENT',
    restoreInventory: true
  },
  {
    label: '价格有误',
    text: '商品价格与柜内标价不符',
    category: 'PAYMENT',
    restoreInventory: true
  },
  {
    label: '质量问题(已拿走)',
    text: '商品质量问题，货已拿走，申请仅退款不退货',
    category: 'USER_APPEAL',
    restoreInventory: false
  },
  {
    label: '申请退款',
    text: '申请退回本单已扣款项',
    category: 'USER_APPEAL',
    restoreInventory: false
  }
] as const;

export type DisputeReasonChip = (typeof DISPUTE_REASON_CHIPS)[number];

/**
 * 无显式 chip 时的申诉分类兜底，须与后端 DisputeReasonCategory 白名单一致。
 * 单源导出：seedDisputeForm 与 pickChip 的「取消选中」回落分支都引用它。
 */
export const DEFAULT_DISPUTE_CATEGORY = 'USER_APPEAL';

/**
 * 快捷选项点击后的申诉说明（2026-10-08 用户推翻旧「；追加」交互后定案）：
 * - 未选中 → 选中：说明**整体替换**为该条 chip 文案（旧版「；」追加会无限堆积，用户实测否决）；
 * - 已选中 → 取消：说明仍是该条 chip 原文（未被用户编辑过）则清空，已编辑则保留用户文本。
 * 快捷选项语义是「单选快填」，不是「多选累加」。
 */
export function applyChipToReason(
  current: string,
  chip: DisputeReasonChip,
  wasSelected: boolean
): string {
  if (!wasSelected) return chip.text;
  return current.trim() === chip.text ? '' : current;
}

/** 与后端 RefundInventoryPolicy 对齐：仅信显式 chip；自由文本交服务端（C-10） */
export function inferRestoreInventory(
  _reason: string,
  chip?: DisputeReasonChip | null
): boolean | undefined {
  if (chip && typeof chip.restoreInventory === 'boolean') {
    return chip.restoreInventory;
  }
  return undefined;
}
