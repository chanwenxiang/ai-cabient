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

export function appendChipToReason(current: string, chip: DisputeReasonChip): string {
  const base = current.trim();
  if (!base) return chip.text;
  if (base.includes(chip.text)) return base;
  return `${base}；${chip.text}`;
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
