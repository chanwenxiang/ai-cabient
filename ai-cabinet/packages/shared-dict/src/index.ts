export type DictType = keyof typeof DICT;

export const DICT = {
  device_type: { AI_CABINET_V1: 'AI智能柜 V1' },
  session_state: {
    CREATED: '已创建',
    OPENING: '开门中',
    SHOPPING: '购物中',
    RECOGNIZING: '识别商品中',
    WAITING_UPLOAD: '录像上传中',
    SETTLING: '结算中',
    COMPLETED: '已完成',
    DISPUTED: '待审核',
    FAILED: '失败',
    CANCELLED: '已取消'
  },
  upload_status: {
    NONE: '无需上传',
    LOCAL_QUEUED: '待上传',
    UPLOADING: '上传中',
    UPLOADED: '已上传',
    FAILED: '上传失败'
  },
  dispute_status: { OPEN: '待审核', RESOLVED: '已结案', CLOSED: '已结案' },
  pay_channel: { WECHAT: '微信', ALIPAY: '支付宝', MOCK: '其他', BALANCE: '余额', UNKNOWN: '未知' },
  split_status: {
    PENDING: '待处理',
    LEDGER_ONLY: '仅记账',
    ACCRUED: '待分账',
    WECHAT_SUBMITTED: '已提交',
    WECHAT_FAILED: '失败',
    SUBMITTED: '已提交',
    SUCCESS: '成功',
    FAILED: '失败'
  },
  merchant_status: { ACTIVE: '正常', INACTIVE: '停用', PENDING: '待审核' },
  online_status: { ONLINE: '在线', OFFLINE: '离线', UNKNOWN: '未知' }
} as const;

export function dictLabel(type: DictType, code: string | null | undefined): string {
  const map = DICT[type] as Record<string, string>;
  if (!map) return code || '-';
  const key = String(code || '').toUpperCase();
  return map[key] ?? map[code as string] ?? code ?? '-';
}

export function dictOptions(type: DictType): { value: string; label: string }[] {
  const map = DICT[type] as Record<string, string>;
  return Object.entries(map || {}).map(([value, label]) => ({ value, label }));
}
