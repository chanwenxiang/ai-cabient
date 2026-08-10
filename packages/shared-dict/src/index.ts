/**
 * Shared dict labels (compile-time baseline + optional runtime overrides).
 *
 * Contract:
 * - Dict = display metadata (labels, filter options, tags). Not capability switches.
 * - Payment / feature ability stays in Java constants (e.g. PayChannels) + env flags
 *   (ALIPAY_ENABLED, PAYSCORE_*). Disabling a dict item must not block checkout.
 * - New business enum codes: add in backend first, then seed here + SysDictBootstrap.
 * - Clients load GET /api/v2/dicts/runtime when logged in; on failure keep DICT defaults
 *   (runtimeLoaded stays false — do not wipe options).
 *
 * Options resolution (dictOptions):
 * - OPS_MANAGED types (e.g. route_code): after successful runtime load, ACTIVE items only;
 *   zero ACTIVE → empty list (ops is source of truth). Before load / on failure → DICT seed.
 * - System enum types: non-empty runtime map replaces options; otherwise DICT seed
 *   (cannot empty a status dropdown by clearing all items).
 *
 * Labels (dictLabel): always override → DICT → readable fallback (historical rows still show).
 */

/** Runtime overrides from ops dict admin (value -> label per type). */
let runtimeOverrides: Record<string, Record<string, string>> = {};
/** True only after a successful /dicts/runtime response in this session. */
let runtimeLoaded = false;

/**
 * 运营可配字典：下拉以运行时 ACTIVE 为准；拉成功且无项则空列表。
 * 系统状态枚举不要加入此集合。
 */
export const OPS_MANAGED_DICT_TYPES: ReadonlySet<string> = new Set(['route_code', 'category_code']);

export function isOpsManagedDict(type: string): boolean {
  return OPS_MANAGED_DICT_TYPES.has(type);
}

export function isRuntimeDictLoaded(): boolean {
  return runtimeLoaded;
}

export type SetDictOverridesOptions = {
  /** 是否标记本次为成功拉取的 runtime；默认 true（传入 map 时）。clear 请用 clearDictOverrides。 */
  loaded?: boolean;
};

export function setDictOverrides(
  map: Record<string, Record<string, string>> | null | undefined,
  options?: SetDictOverridesOptions
) {
  runtimeOverrides = map || {};
  runtimeLoaded = options?.loaded ?? true;
}

export function clearDictOverrides() {
  runtimeOverrides = {};
  runtimeLoaded = false;
}

/** /api/v2/dicts/runtime 响应体（仅取 ACTIVE 项）。 */
export type RuntimeDictPayload = {
  itemsByType?: Record<string, { dictValue?: string; dictLabel?: string; status?: string }[]>;
};

/** 将 runtime 字典转为 setDictOverrides 可用的 value→label 映射。 */
export function buildOverridesFromRuntime(
  payload: RuntimeDictPayload | null | undefined
): Record<string, Record<string, string>> {
  const map: Record<string, Record<string, string>> = {};
  for (const [type, rows] of Object.entries(payload?.itemsByType || {})) {
    map[type] = {};
    for (const row of rows || []) {
      if (row.status && row.status !== 'ACTIVE') continue;
      if (!row.dictValue) continue;
      map[type][row.dictValue] = row.dictLabel || row.dictValue;
    }
  }
  return map;
}

function entriesToOptions(
  map: Record<string, string> | undefined
): { value: string; label: string }[] {
  return Object.entries(map || {}).map(([value, label]) => ({ value, label }));
}

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
  dispute_status: { OPEN: '待审核', RESOLVED: '已结案', CLOSED: '已关闭' },
  dispute_category: {
    USER_APPEAL: '用户申诉',
    RECOGNITION: '识别争议',
    VIDEO_MISSING: '录像缺失',
    PAYMENT: '支付相关',
    INVENTORY: '库存相关',
    BILL: '账单争议',
    OTHER: '其他'
  },
  dispute_priority: {
    LOW: '低',
    NORMAL: '普通',
    HIGH: '高',
    URGENT: '紧急'
  },
  pay_channel: { WECHAT: '微信', ALIPAY: '支付宝', MOCK: '其他', BALANCE: '余额', UNKNOWN: '未知' },
  recharge_status: {
    CREATED: '已创建',
    PENDING: '待支付',
    PAID: '已支付',
    SUCCESS: '成功',
    FAILED: '失败',
    REFUNDED: '已退款',
    CANCELLED: '已取消',
    CLOSED: '已关闭'
  },
  risk_event_type: {
    MULTI_DEVICE: '多设备异常',
    HIGH_FREQUENCY: '高频开门',
    DISPUTE_SPIKE: '争议激增',
    PAYMENT_FAIL: '支付失败聚集',
    BLACKLIST_HIT: '黑名单命中',
    MALICIOUS_OPEN: '高频恶意开门',
    DISPUTE_CREATED: '用户发起争议',
    FREQUENT_DISPUTE: '频繁发起争议',
    BLACKLIST_ADD: '人工加入黑名单',
    BLACKLIST_AUTO: '自动加入黑名单',
    FRAUD: '欺诈嫌疑',
    ABNORMAL: '异常行为'
  },
  risk_severity: {
    INFO: '提示',
    WARN: '警告',
    BLOCK: '已拦截',
    HIGH: '高风险',
    CRITICAL: '严重'
  },
  feedback_type: {
    COMPLAINT: '投诉',
    SUGGESTION: '建议',
    BUG: '缺陷',
    PRAISE: '表扬'
  },
  feedback_status: {
    PENDING: '待处理',
    HANDLED: '已回复',
    REPLIED: '已回复',
    CLOSED: '已关闭'
  },
  split_status: {
    PENDING: '待处理',
    LEDGER_ONLY: '仅记账',
    ACCRUED: '待分账',
    WECHAT_SUBMITTED: '已提交',
    WECHAT_FAILED: '失败',
    SUBMITTED: '已提交',
    SUCCESS: '成功',
    FAILED: '失败',
    SETTLED: '已完结',
    VOIDED: '已冲正'
  },
  merchant_status: { ACTIVE: '正常', INACTIVE: '停用', PENDING: '待审核' },
  online_status: { ONLINE: '在线', OFFLINE: '离线', UNKNOWN: '未知' },
  device_lifecycle: {
    IDLE: '未投放',
    INBOUND: '入库',
    DEPLOYED: '投放',
    RETURNING: '返厂中',
    RETIRED: '退役'
  },
  device_coop_mode: {
    SELF: '自营',
    FRANCHISE: '加盟',
    CONSIGN: '联营'
  },
  repair_ticket_status: {
    OPEN: '待处理',
    IN_PROGRESS: '处理中',
    DONE: '已完成',
    CANCELLED: '已取消'
  },
  line_manager_status: {
    ACTIVE: '启用',
    DISABLED: '停用'
  },
  announcement_status: {
    DRAFT: '草稿',
    PUBLISHED: '已发布',
    ARCHIVED: '存档'
  },
  announcement_audience: {
    ALL: '全部用户',
    MERCHANT: '商户',
    CONSUMER: '消费者'
  },
  promotion_type: {
    FULL_REDUCE: '满减',
    DISCOUNT: '折扣',
    BUY_GIFT: '买赠',
    SECOND_HALF: '第二件半价'
  },
  coupon_type: {
    AMOUNT_OFF: '满减券',
    PERCENT_OFF: '折扣券',
    FREE_SHIPPING: '免运费',
    EXCHANGE: '兑换券'
  },
  /** 通用启用态（优惠券/活动等） */
  enable_status: {
    ACTIVE: '启用',
    INACTIVE: '停用',
    DISABLED: '停用',
    ENDED: '已结束'
  },
  sku_enrollment_status: {
    DRAFT: '草稿',
    MAPPING: '映射中',
    TESTED: '已测试',
    PRODUCTION: '生产'
  },
  fund_ledger_type: {
    ORDER_PAYMENT: '订单支付',
    PLATFORM_FEE: '平台抽成',
    CHANNEL_FEE: '通道费',
    MERCHANT_CREDIT: '商户入账'
  },
  /** 商户/线长钱包流水类型 */
  wallet_ledger_type: {
    MERCHANT_CREDIT: '商户入账',
    LINE_COMMISSION: '线长佣金',
    WITHDRAW_FREEZE: '提现冻结',
    WITHDRAW_RELEASE: '提现解冻',
    WITHDRAW_PAID: '提现打款',
    ADJUST: '调账',
    REVERSE: '冲正'
  },
  fund_direction: {
    IN: '收入',
    OUT: '支出'
  },
  device_ops_event: {
    OFFLINE: '离线',
    NO_SALES: '无销售',
    UNLOCK: '开锁',
    FAULT: '故障/锁机',
    AISLE_AUDIT: '货道巡检',
    MAINBOARD: '主板'
  },
  repair_fault_type: {
    DOOR: '门锁',
    COOLING: '制冷',
    NETWORK: '网络',
    PAYMENT: '支付',
    VISION: '识别',
    POWER: '供电',
    OTHER: '其他'
  },
  line_withdraw_status: {
    PENDING_REVIEW: '待审核',
    APPROVED: '已通过',
    PAYING: '打款中',
    PAID: '已打款',
    REJECTED: '已驳回',
    FAILED: '失败'
  },
  merchant_withdraw_status: {
    PENDING_REVIEW: '待审核',
    APPROVED: '已通过',
    PAYING: '打款中',
    PAID: '已打款',
    REJECTED: '已驳回',
    FAILED: '失败'
  },
  supplier_status: { ACTIVE: '启用', INACTIVE: '停用' },
  purchase_order_status: {
    CREATED: '待收货',
    PARTIAL_RECEIVED: '部分收货',
    RECEIVED: '已收货',
    CANCELLED: '已取消'
  },
  warehouse_status: { ACTIVE: '正常', INACTIVE: '停用' },
  warehouse_outbound_status: {
    DRAFT: '待拣货',
    PICKED: '已拣货',
    SHIPPED: '已发运',
    CANCELLED: '已取消'
  },
  handover_status: {
    PENDING: '待备货',
    READY: '待发运',
    IN_TRANSIT: '在途',
    PARTIAL: '部分签收',
    RECEIVED: '已签收'
  },
  in_transit_status: { IN_TRANSIT: '在途', RECEIVED: '已签收', LOST: '丢失', DAMAGED: '破损' },
  warehouse_movement_type: {
    PURCHASE_RECEIVE: '采购收货',
    PURCHASE_RETURN: '采购退货',
    MANUAL_INBOUND: '手工入库',
    INBOUND_MANUAL: '手工入库',
    OUTBOUND: '出库',
    OUTBOUND_SHIP: '发运',
    RETURN: '退回',
    ADJUSTMENT: '库存调整'
  },
  business_reference_type: {
    PURCHASE_ORDER: '采购单',
    PURCHASE_RETURN: '采购退货',
    OUTBOUND_ORDER: '出库单',
    WAREHOUSE_INBOUND: '仓库入库',
    WAREHOUSE_OUTBOUND: '仓库出库',
    REPLENISHMENT_TASK: '补货任务',
    INVENTORY_ADJUSTMENT: '库存调整',
    MANUAL: '人工操作'
  },
  replenishment_route_status: {
    PLANNED: '待执行',
    IN_PROGRESS: '执行中',
    COMPLETED: '已完成',
    CANCELLED: '已取消'
  },
  replenishment_task_status: {
    PENDING: '待处理',
    IN_PROGRESS: '进行中',
    COMPLETED: '已完成',
    CANCELLED: '已取消'
  },
  replenishment_request_status: {
    SUBMITTED: '待审核',
    ACCEPTED: '已接单',
    REJECTED: '已驳回',
    COMPLETED: '已完成'
  },
  inventory_lot_status: {
    ON_SALE: '在售',
    NEAR_EXPIRY: '临期',
    BLOCKED: '已冻结',
    DEPLETED: '已耗尽'
  },
  exception_severity: { CRITICAL: '紧急', HIGH: '高', MEDIUM: '中', LOW: '低' },
  exception_status: { OPEN: '待处理', PROCESSING: '处理中', RESOLVED: '已解决', CLOSED: '已关闭' },
  exception_type: {
    DISPUTE: '消费争议',
    LOW_STOCK: '低库存',
    EXPIRY: '临期商品',
    REPLENISHMENT_REQUIRED: '待补货',
    DEVICE_OFFLINE: '设备离线',
    DEVICE_FAULT: '设备故障',
    DOOR_OPEN_TOO_LONG: '长时间未关门',
    OPEN_TIMEOUT: '开门超时',
    UPLOAD_STUCK: '录像上传滞留',
    RECOGNITION_STUCK: '识别滞留',
    RECOGNITION_TIMEOUT: '识别超时',
    RECOGNITION_FAILED: '识别存疑需人工审核',
    RECOGNITION_UNAVAILABLE: '识别服务不可用',
    BALANCE_INSUFFICIENT: '余额不足',
    SETTLEMENT_FAILED: '结算失败',
    SETTLEMENT_STUCK: '结算滞留',
    INVENTORY_MISMATCH: '库存差异',
    SLOT_DISCREPANCY: '货道账实差异',
    VISION_ANOMALY: '视觉异常（端侧）'
  },
  ops_exception_action: {
    OPS_EXCEPTION_CLAIM: '领取异常',
    OPS_EXCEPTION_TRANSFER: '转派异常',
    OPS_EXCEPTION_NOTE: '添加备注',
    OPS_EXCEPTION_RETRY: '重试识别/结算',
    OPS_EXCEPTION_RETRY_SUCCESS: '重试成功',
    OPS_EXCEPTION_CANCEL_SESSION: '取消会话并释放设备',
    OPS_EXCEPTION_MANUAL_RESOLVE: '人工处置（确认商品/免单）',
    OPS_EXCEPTION_RESOLVE: '标记已解决',
    OPS_EXCEPTION_AUTO_RESOLVE: '系统自动解决',
    MERCHANT_OPS_EXCEPTION_RESOLVE: '商家处理异常'
  },
  reconciliation_status: {
    MATCHED: '已平账',
    MISMATCH: '存在差异',
    PENDING: '待处理',
    FAILED: '失败'
  },
  settlement_batch_status: {
    PENDING: '待结算',
    PROCESSING: '结算中',
    SETTLED: '已结算',
    PAID: '已支付',
    FAILED: '失败',
    PARTIAL_FAILED: '部分失败',
    COMPLETED: '已完成'
  },
  sku_status: { ACTIVE: '在售', INACTIVE: '停用', DISABLED: '禁售' },
  order_status: {
    PENDING: '待支付',
    PROCESSING: '处理中',
    PAID: '已支付',
    COMPLETED: '已完成',
    DISPUTED: '争议中',
    REFUNDED: '已退款',
    PARTIAL_REFUNDED: '部分退款',
    FAILED: '处理失败',
    CANCELLED: '已取消'
  },
  route_code: {
    R01: '路线 R01',
    'R-DEMO-01': '演示路线 01',
    'R-DEMO-02': '演示路线 02',
    'R-DEMO-X': '演示路线 X'
  },
  scheduled_task_group: {
    DEVICE: '设备',
    TRADE: '交易',
    OPS: '运维',
    SYSTEM: '系统',
    WAREHOUSE: '仓储',
    MERCHANT: '商户',
    FINANCE: '财务',
    MARKETING: '营销'
  },
  wallet_entry_type: {
    ADJUST: '运营调整',
    COMMISSION: '佣金入账',
    COMMISSION_DAILY: '日结佣金',
    WITHDRAW_FREEZE: '提现冻结',
    WITHDRAW_RELEASE: '提现解冻',
    WITHDRAW_PAID: '提现打款',
    SPLIT_CREDIT: '分账入账',
    SPLIT_REVERSE: '分账退回',
    RECHARGE: '充值',
    RECHARGE_REFUND: '充值退款',
    REFUND: '退款',
    SETTLE: '结算入账',
    PAYOUT: '打款',
    FEE: '手续费'
  },
  session_kind: { RESTOCK: '补货', OPS: '运维', SHOPPING: '消费' },
  restock_line_type: {
    RESTOCK: '上架',
    PULL_OFF: '下架',
    REMOVE: '下架',
    PULL: '下架'
  },
  ops_alert_type: {
    DISPUTE: '账单争议',
    DEVICE_OFFLINE: '设备离线',
    UPLOAD_STUCK: '录像滞留',
    SESSION_STALE: '会话超时',
    LOW_STOCK: '库存不足',
    REPLENISHMENT: '补货任务',
    RECON_MISMATCH: '对账差异',
    RECONCILIATION_MISMATCH: '对账差异',
    SPLIT_EXCEPTION: '分账异常',
    IN_TRANSIT_OVERDUE: '签收超时'
  },
  ad_asset_type: { IMAGE: '图片', VIDEO: '视频', H5: 'H5' },
  ad_campaign_status: { DRAFT: '草稿', RUNNING: '投放中', STOPPED: '已停止' },
  consistency_check_type: {
    ORDER_AMOUNT: '订单金额',
    PAYMENT_AMOUNT: '支付净额',
    INVENTORY_MISMATCH: '库存汇总'
  },
  sku_perf_level: {
    BEST_SELLER: '畅销',
    NORMAL: '正常',
    SLOW_MOVER: '慢销',
    NO_SALES: '无销量'
  },
  sku_review_status: {
    PENDING: '待评审',
    RECOMMEND_DELIST: '建议下架',
    DELISTED: '已下架',
    KEPT: '已保留'
  },
  member_level: {
    NORMAL: '普通',
    SILVER: '白银',
    GOLD: '黄金',
    PLATINUM: '铂金',
    DIAMOND: '钻石'
  },
  site_contract_status: { ACTIVE: '有效', EXPIRING: '临期', EXPIRED: '已到期' },
  device_env_type: { HUMIDITY: '湿度', VOLTAGE: '电压', POWER: '功耗' },
  device_lifecycle_action: {
    BIND: '绑定商户',
    UNBIND: '解绑',
    DEPLOY: '投放',
    UNDEPLOY: '撤回未投放',
    RETURN: '返厂',
    RETIRE: '退役',
    INBOUND: '入库'
  },
  stock_health_dim: { STOCKOUT: '断货', LOW: '低库存', NEAR_EXPIRY: '临期' },
  purchase_suggestion_reason: {
    SALES_DRIVEN: '销量驱动',
    TREND_FORECAST: '趋势预测',
    LOW_STOCK: '库存不足'
  },
  supplier_payable_status: { UNPAID: '未付', PARTIAL: '部分付款', PAID: '已付', CLOSED: '已关闭' },
  stocktake_mode: { BLIND: '盲盘', VISIBLE: '明盘' },
  stocktake_status: {
    DRAFT: '草稿',
    IN_PROGRESS: '盘点中',
    COMPLETED: '已完成',
    ADJUSTED: '已调整',
    CANCELLED: '已取消'
  },
  stocktake_line_status: { PENDING: '未盘', MATCHED: '相符', DIFF: '有差异', ADJUSTED: '已调整' },
  merchant_alert_type: {
    LOW_STOCK: '低库存',
    EXPIRY: '临期',
    REPLENISHMENT_REQUIRED: '需补货',
    REPLENISHMENT: '补货任务',
    DEVICE_OFFLINE: '柜机离线',
    DEVICE_FAULT: '柜机故障',
    DISPUTE: '消费争议',
    SETTLEMENT_FAILED: '结算失败'
  },
  /** 商品类目：运营在字典管理维护；runtime 为准 */
  category_code: {} as Record<string, string>
} as const;

export type DictType = keyof typeof DICT;

export type DictTagType = 'success' | 'warning' | 'danger' | 'info' | 'primary';

const STATUS_TAGS: Record<string, DictTagType> = {
  ACTIVE: 'success',
  ONLINE: 'success',
  COMPLETED: 'success',
  RECEIVED: 'success',
  SUCCESS: 'success',
  ON_SALE: 'success',
  MATCHED: 'success',
  RESOLVED: 'success',
  CLOSED: 'success',
  CREATED: 'info',
  DRAFT: 'info',
  PENDING: 'info',
  SUBMITTED: 'info',
  OPEN: 'warning',
  PROCESSING: 'warning',
  LOW: 'info',
  MEDIUM: 'warning',
  PARTIAL_RECEIVED: 'warning',
  PICKED: 'warning',
  IN_PROGRESS: 'warning',
  IN_TRANSIT: 'warning',
  NEAR_EXPIRY: 'warning',
  PARTIAL: 'warning',
  HIGH: 'warning',
  INACTIVE: 'danger',
  OFFLINE: 'danger',
  FAILED: 'danger',
  REJECTED: 'danger',
  CANCELLED: 'danger',
  BLOCKED: 'danger',
  LOST: 'danger',
  DAMAGED: 'danger',
  CRITICAL: 'danger',
  MISMATCH: 'danger'
};

export function dictLabel(type: DictType | string, code: string | null | undefined): string {
  const key = String(code || '').toUpperCase();
  const override = runtimeOverrides[type]?.[key] ?? runtimeOverrides[type]?.[String(code || '')];
  if (override) return override;
  const map = (DICT as Record<string, Record<string, string>>)[type];
  if (!map) {
    // 无字典类型时，避免把 SCREAMING_SNAKE 英文码直接露出给用户
    if (code && /^[A-Z][A-Z0-9_]*$/.test(String(code))) return '未知';
    return code || '-';
  }
  const hit = map[key] ?? map[code as string];
  if (hit) return hit;
  if (code && /^[A-Z][A-Z0-9_]*$/.test(String(code))) return '未知';
  return code ?? '-';
}

/**
 * 三端 UI 展示用：优先字典中文，绝不把英文枚举码当文案回退（避免 `|| status` 露出 OPEN/PAID）。
 */
export function displayLabel(
  type: DictType | string,
  code: string | null | undefined,
  empty = '-'
): string {
  if (code == null || String(code).trim() === '') return empty;
  const label = dictLabel(type, code);
  if (!label || label === '-') return empty;
  if (/^[A-Z][A-Z0-9_]*$/.test(label)) return empty === '-' ? '未知' : empty;
  return label;
}

/** 操作人展示：系统任务 / 无姓名时可读 */
export function actorDisplayName(input: {
  name?: string | null;
  phone?: string | null;
  userId?: number | null;
  operatorId?: number | null;
}): string {
  const name = input.name != null ? String(input.name).trim() : '';
  if (name) return name;
  const phone = input.phone != null ? String(input.phone).trim() : '';
  if (phone) return phone;
  const id = input.operatorId ?? input.userId;
  if (id == null || id <= 0) return '系统';
  return `账号 ${id}`;
}

export function dictOptions(type: DictType | string): { value: string; label: string }[] {
  const baseline = (DICT as Record<string, Record<string, string>>)[type];
  const override = runtimeOverrides[type];

  // 运营可配：runtime 拉成功后以 ACTIVE 为准（可为空列表）
  if (isOpsManagedDict(type)) {
    if (runtimeLoaded) {
      return entriesToOptions(override);
    }
    return entriesToOptions(baseline);
  }

  // 系统枚举：runtime 已成功且该类型有 ACTIVE 覆盖 → 只用覆盖（停用项立即从下拉消失）
  if (runtimeLoaded && override && Object.keys(override).length) {
    return entriesToOptions(override);
  }
  if (override && Object.keys(override).length) {
    return entriesToOptions(override);
  }
  return entriesToOptions(baseline);
}

export function dictTagType(code: string | null | undefined): DictTagType {
  return STATUS_TAGS[String(code || '').toUpperCase()] || 'info';
}

/** 审计动作 → 中文（运营后台审计日志筛选项/表格共用） */
export const AUDIT_ACTION_LABELS: Record<string, string> = {
  BALANCE_ADJUST: '余额调整',
  SESSION_CANCEL: '取消会话',
  USER_VERIFY: '用户实名通过',
  USER_UNVERIFY: '撤销用户实名',
  RECHARGE_REFUND: '充值退款',
  SKU_CREATE: '新建商品',
  SKU_UPDATE: '更新商品',
  DEVICE_CREATE: '新建设备',
  DEVICE_UPDATE: '更新设备',
  DEVICE_LOCK: '设备锁机',
  DEVICE_UNLOCK: '设备解锁',
  DEVICE_REBOOT: '设备重启',
  DEVICE_SET_TEMP: '设置目标温度',
  DEVICE_REMOTE_OPEN: '运维远程开门',
  DEVICE_LIFECYCLE: '设备生命周期变更',
  DEVICE_POLICY: '设备策略变更',
  DEVICE_AUTO_LOCK_OFFLINE: '离线超时自动锁机',
  DICT_TYPE_CREATE: '新建字典类型',
  DICT_TYPE_UPDATE: '更新字典类型',
  DICT_DATA_CREATE: '新建字典项',
  DICT_DATA_UPDATE: '更新字典项',
  DICT_DATA_DELETE: '删除字典项',
  SKU_VISION_ENROLL_CREATE: '识别建档',
  SKU_VISION_ENROLL_UPDATE: '更新识别建档',
  SKU_VISION_STATUS: '识别状态变更',
  SKU_VISION_ADVANCE: '推进识别入驻',
  VISION_YOLO_UPSERT: '更新 YOLO 映射',
  VISION_YOLO_DELETE: '删除 YOLO 映射',
  VISION_ALIYUN_UPSERT: '更新阿里云映射',
  VISION_ALIYUN_DELETE: '删除阿里云映射',
  ORDER_REMIND: '催收待支付订单',
  ORDER_CANCEL_UNPAID: '关闭待支付订单',
  ORDER_COLLECT_UNPAID: '代收待支付订单',
  ORDER_AUTO_CANCEL_UNPAID: '超时自动关单',
  ORDER_REFUND_OPS: '运营退款',
  ORDER_REFUND_CONSUMER: '用户退款',
  DISPUTE_CLOSE: '关闭争议',
  DISPUTE_REOPEN: '重开争议',
  DISPUTE_WAIVE: '争议免单',
  DISPUTE_KEEP_BILL: '争议维持原单',
  DISPUTE_RESOLVE: '争议结案',
  DISPUTE_SYNC_FROM_OPS_EXCEPTION: '异常同步争议',
  MERCHANT_DISPUTE_REPLY: '商户回复争议',
  MERCHANT_CREATE: '新建商户',
  MERCHANT_UPDATE: '更新商户',
  MERCHANT_PROFILE_UPDATE: '商户资料更新',
  MERCHANT_DEVICE_SETTINGS: '商户设备设置',
  MERCHANT_SKU_PRICE: '商户改价',
  MERCHANT_USER_CREATE: '新建商户账号',
  MERCHANT_USER_UPDATE: '更新商户账号',
  MERCHANT_USER_DISABLE: '停用商户账号',
  MERCHANT_USER_ENABLE: '启用商户账号',
  MERCHANT_USER_RESET_PASSWORD: '重置商户密码',
  MERCHANT_REPLENISHMENT_CHECK_IN: '补货签到',
  MERCHANT_REPLENISHMENT_CONFIRM_LINES: '确认补货明细',
  MERCHANT_REPLENISHMENT_COMPLETE: '完成补货任务',
  MERCHANT_REPLENISHMENT_OPEN_DOOR: '补货开门',
  MERCHANT_REPLEN_REQUEST: '提交补货申请',
  MERCHANT_REPLEN_ACCEPT: '接单补货申请',
  MERCHANT_REPLEN_REJECT: '驳回补货申请',
  PROFIT_SHARING_SUBMIT: '提交分账',
  PROFIT_SHARING_REFRESH: '刷新分账',
  SPLIT_LEDGER_CONFIRM: '确认分账入账',
  OPS_USER_DEVICE_SCOPE: '用户设备范围',
  OPS_EXCEPTION_CLAIM: '领取异常',
  OPS_EXCEPTION_TRANSFER: '转派异常',
  OPS_EXCEPTION_NOTE: '异常备注',
  OPS_EXCEPTION_RETRY: '重试识别/结算',
  OPS_EXCEPTION_RETRY_SUCCESS: '重试成功',
  OPS_EXCEPTION_CANCEL_SESSION: '取消会话并释放设备',
  OPS_EXCEPTION_MANUAL_RESOLVE: '人工处置异常',
  OPS_EXCEPTION_RESOLVE: '标记异常已解决',
  OPS_EXCEPTION_RESOLVE_WITH_REPAIR: '异常结案并建维修单',
  OPS_EXCEPTION_AUTO_RESOLVE: '系统自动解决异常',
  OPS_EXCEPTION_SYNC_FROM_DISPUTE: '争议同步异常',
  MERCHANT_OPS_EXCEPTION_RESOLVE: '商家处理异常'
};

/** 审计对象类型 → 中文 */
export const AUDIT_TARGET_LABELS: Record<string, string> = {
  USER: '用户',
  SESSION: '会话',
  ORDER: '订单',
  SKU: '商品',
  DEVICE: '设备',
  DICT_TYPE: '字典类型',
  DICT_DATA: '字典项',
  OPS_EXCEPTION: '运营异常',
  DISPUTE: '争议单',
  MERCHANT: '商户',
  SPLIT: '分账',
  RECHARGE: '充值单',
  VISION: '识别映射',
  SKU_PRICE: '商品定价',
  REPLENISHMENT_TASK: '补货任务',
  REPLEN_REQUEST: '补货申请'
};

const AUDIT_DETAIL_KEY_LABELS: Record<string, string> = {
  minutes: '分钟',
  hours: '小时',
  deviceid: '设备',
  device: '设备',
  sessionid: '会话',
  orderid: '订单',
  commandid: '指令编号',
  targettempc: '目标温度',
  notifyedge: '是否下发柜机',
  blacklist: '是否拉黑',
  assignee: '接收人',
  reason: '原因',
  result: '结果',
  name: '名称',
  idempotencykey: '幂等键',
  from: '原状态',
  to: '新状态'
};

export function auditActionLabel(action?: string | null): string {
  if (!action) return '-';
  const hit = AUDIT_ACTION_LABELS[action];
  if (hit) return hit;
  const ops = (DICT.ops_exception_action as Record<string, string>)[action];
  if (ops) return ops;
  if (/^[A-Z][A-Z0-9_]*$/.test(action)) return `其他操作（${action}）`;
  return action;
}

export function auditTargetLabel(type?: string | null): string {
  if (!type) return '-';
  const hit = AUDIT_TARGET_LABELS[type];
  if (hit) return hit;
  if (/^[A-Z][A-Z0-9_]*$/.test(type)) return `其他对象（${type}）`;
  return type;
}

/** 审计/异常操作 detail：英文键值对 → 中文说明 */
export function formatOpsActionDetail(detail: string | null | undefined): string {
  if (!detail) return '-';
  let text = detail.trim();
  if (!text) return '-';

  text = text.replace(/\bsales-lock\b/gi, '营业锁');
  text = text.replace(/\bops collect\b/gi, '运营代收');
  text = text.replace(/^idempotencyKey=([^;]+);\s*/i, '幂等键：$1；');
  text = text.replace(/\bassignee=(\d+)/gi, '接收人：用户 $1');
  text = text.replace(/\breason=/gi, '原因：');
  text = text.replace(/\bresult=/gi, '结果：');
  text = text.replace(
    /人工免单，退回余额 (\d+) 分/g,
    (_, cents) => `人工免单，退回余额 ¥${(+cents / 100).toFixed(2)}`
  );
  text = text.replace(
    /原金额=(\d+) 分，最终金额=(\d+) 分，差额=(-?\d+) 分/g,
    (_, orig, final, adj) =>
      `原金额 ¥${(+orig / 100).toFixed(2)}，最终金额 ¥${(+final / 100).toFixed(2)}，差额 ¥${(+adj / 100).toFixed(2)}`
  );

  // minutes=10 / deviceId=CAB-001,sessionId=... / key=value;key=value
  text = text.replace(
    /([A-Za-z][A-Za-z0-9_]*)\s*=\s*([^,;]+)/g,
    (full, key: string, raw: string) => {
      const k = String(key).toLowerCase();
      const value = String(raw).trim();
      const label = AUDIT_DETAIL_KEY_LABELS[k];
      if (!label) return full;
      if (k === 'minutes') return `离线超过 ${value} 分钟自动锁机`;
      if (k === 'hours') return `超时 ${value} 小时`;
      if (k === 'blacklist' || k === 'notifyedge') {
        const on = /^(true|1|yes)$/i.test(value);
        return `${label}：${on ? '是' : '否'}`;
      }
      if (k === 'targettempc') return `${label}：${value}℃`;
      return `${label}：${value}`;
    }
  );

  text = text.replace(/;\s*/g, '；').replace(/,\s*/g, '，');

  if (/^[A-Z_]+$/.test(text)) {
    return dictLabel('exception_type', text);
  }
  return text;
}

/** @deprecated 使用 formatOpsActionDetail；保留别名便于审计页语义 */
export const formatAuditDetail = formatOpsActionDetail;
