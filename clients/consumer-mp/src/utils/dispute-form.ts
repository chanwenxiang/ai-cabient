/** 消费者账单申诉快捷选项（文案写入 reason，category 需落在后端白名单） */
export const DISPUTE_REASON_CHIPS = [
  { label: '没拿这个商品', text: '我没有拿这个商品，请核对识别结果', category: 'RECOGNITION', restoreInventory: true },
  { label: '数量不对', text: '商品数量识别有误，请核对', category: 'RECOGNITION', restoreInventory: true },
  { label: '重复扣款', text: '疑似重复扣款，请核查并退回多扣金额', category: 'PAYMENT', restoreInventory: true },
  { label: '价格有误', text: '商品价格与柜内标价不符', category: 'PAYMENT', restoreInventory: true },
  {
    label: '质量问题(已拿走)',
    text: '商品质量问题，货已拿走，申请仅退款不退货',
    category: 'USER_APPEAL',
    restoreInventory: false
  },
  { label: '申请退款', text: '申请退回本单已扣款项', category: 'USER_APPEAL', restoreInventory: false }
] as const;

export type DisputeReasonChip = (typeof DISPUTE_REASON_CHIPS)[number];

export function appendChipToReason(current: string, chip: DisputeReasonChip): string {
  const base = current.trim();
  if (!base) return chip.text;
  if (base.includes(chip.text)) return base;
  return `${base}；${chip.text}`;
}

/** 与后端 RefundInventoryPolicy 对齐的前端推断（显式 chip 优先） */
export function inferRestoreInventory(reason: string, chip?: DisputeReasonChip | null): boolean {
  if (chip && typeof chip.restoreInventory === 'boolean') {
    return chip.restoreInventory;
  }
  const r = (reason || '').trim();
  if (/没拿|未拿|没有拿|误识别|识别有误|识别错误|多扣|重复扣|错扣|请核对识别/.test(r)) {
    return true;
  }
  if (/质量|变质|临期|过期|损坏|破损|已拿走|不退货|仅退款/.test(r)) {
    return false;
  }
  // 默认：货已离柜风险更高 → 仅退款不回库
  return false;
}
