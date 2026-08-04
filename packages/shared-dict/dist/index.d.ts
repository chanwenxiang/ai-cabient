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
/**
 * 运营可配字典：下拉以运行时 ACTIVE 为准；拉成功且无项则空列表。
 * 系统状态枚举不要加入此集合。
 */
export declare const OPS_MANAGED_DICT_TYPES: ReadonlySet<string>;
export declare function isOpsManagedDict(type: string): boolean;
export declare function isRuntimeDictLoaded(): boolean;
export type SetDictOverridesOptions = {
    /** 是否标记本次为成功拉取的 runtime；默认 true（传入 map 时）。clear 请用 clearDictOverrides。 */
    loaded?: boolean;
};
export declare function setDictOverrides(map: Record<string, Record<string, string>> | null | undefined, options?: SetDictOverridesOptions): void;
export declare function clearDictOverrides(): void;
/** /api/v2/dicts/runtime 响应体（仅取 ACTIVE 项）。 */
export type RuntimeDictPayload = {
    itemsByType?: Record<string, {
        dictValue?: string;
        dictLabel?: string;
        status?: string;
    }[]>;
};
/** 将 runtime 字典转为 setDictOverrides 可用的 value→label 映射。 */
export declare function buildOverridesFromRuntime(payload: RuntimeDictPayload | null | undefined): Record<string, Record<string, string>>;
export declare const DICT: {
    readonly device_type: {
        readonly AI_CABINET_V1: "AI智能柜 V1";
    };
    readonly session_state: {
        readonly CREATED: "已创建";
        readonly OPENING: "开门中";
        readonly SHOPPING: "购物中";
        readonly RECOGNIZING: "识别商品中";
        readonly WAITING_UPLOAD: "录像上传中";
        readonly SETTLING: "结算中";
        readonly COMPLETED: "已完成";
        readonly DISPUTED: "待审核";
        readonly FAILED: "失败";
        readonly CANCELLED: "已取消";
    };
    readonly upload_status: {
        readonly NONE: "无需上传";
        readonly LOCAL_QUEUED: "待上传";
        readonly UPLOADING: "上传中";
        readonly UPLOADED: "已上传";
        readonly FAILED: "上传失败";
    };
    readonly dispute_status: {
        readonly OPEN: "待审核";
        readonly RESOLVED: "已结案";
        readonly CLOSED: "已关闭";
    };
    readonly dispute_category: {
        readonly USER_APPEAL: "用户申诉";
        readonly RECOGNITION: "识别争议";
        readonly VIDEO_MISSING: "录像缺失";
        readonly PAYMENT: "支付相关";
        readonly INVENTORY: "库存相关";
        readonly BILL: "账单争议";
        readonly OTHER: "其他";
    };
    readonly dispute_priority: {
        readonly LOW: "低";
        readonly NORMAL: "普通";
        readonly HIGH: "高";
        readonly URGENT: "紧急";
    };
    readonly pay_channel: {
        readonly WECHAT: "微信";
        readonly ALIPAY: "支付宝";
        readonly MOCK: "其他";
        readonly BALANCE: "余额";
        readonly UNKNOWN: "未知";
    };
    readonly recharge_status: {
        readonly CREATED: "已创建";
        readonly PENDING: "待支付";
        readonly PAID: "已支付";
        readonly SUCCESS: "成功";
        readonly FAILED: "失败";
        readonly REFUNDED: "已退款";
        readonly CANCELLED: "已取消";
        readonly CLOSED: "已关闭";
    };
    readonly risk_event_type: {
        readonly MULTI_DEVICE: "多设备异常";
        readonly HIGH_FREQUENCY: "高频开门";
        readonly DISPUTE_SPIKE: "争议激增";
        readonly PAYMENT_FAIL: "支付失败聚集";
        readonly BLACKLIST_HIT: "黑名单命中";
        readonly MALICIOUS_OPEN: "高频恶意开门";
        readonly DISPUTE_CREATED: "用户发起争议";
        readonly FREQUENT_DISPUTE: "频繁发起争议";
        readonly BLACKLIST_ADD: "人工加入黑名单";
        readonly BLACKLIST_AUTO: "自动加入黑名单";
        readonly FRAUD: "欺诈嫌疑";
        readonly ABNORMAL: "异常行为";
    };
    readonly risk_severity: {
        readonly INFO: "提示";
        readonly WARN: "警告";
        readonly BLOCK: "已拦截";
        readonly HIGH: "高风险";
        readonly CRITICAL: "严重";
    };
    readonly feedback_type: {
        readonly COMPLAINT: "投诉";
        readonly SUGGESTION: "建议";
        readonly BUG: "缺陷";
        readonly PRAISE: "表扬";
    };
    readonly feedback_status: {
        readonly PENDING: "待处理";
        readonly HANDLED: "已回复";
        readonly REPLIED: "已回复";
        readonly CLOSED: "已关闭";
    };
    readonly split_status: {
        readonly PENDING: "待处理";
        readonly LEDGER_ONLY: "仅记账";
        readonly ACCRUED: "待分账";
        readonly WECHAT_SUBMITTED: "已提交";
        readonly WECHAT_FAILED: "失败";
        readonly SUBMITTED: "已提交";
        readonly SUCCESS: "成功";
        readonly FAILED: "失败";
        readonly SETTLED: "已完结";
        readonly VOIDED: "已冲正";
    };
    readonly merchant_status: {
        readonly ACTIVE: "正常";
        readonly INACTIVE: "停用";
        readonly PENDING: "待审核";
    };
    readonly online_status: {
        readonly ONLINE: "在线";
        readonly OFFLINE: "离线";
        readonly UNKNOWN: "未知";
    };
    readonly device_lifecycle: {
        readonly IDLE: "未投放";
        readonly INBOUND: "入库";
        readonly DEPLOYED: "投放";
        readonly RETURNING: "返厂中";
        readonly RETIRED: "退役";
    };
    readonly device_coop_mode: {
        readonly SELF: "自营";
        readonly FRANCHISE: "加盟";
        readonly CONSIGN: "联营";
    };
    readonly repair_ticket_status: {
        readonly OPEN: "待处理";
        readonly IN_PROGRESS: "处理中";
        readonly DONE: "已完成";
        readonly CANCELLED: "已取消";
    };
    readonly line_manager_status: {
        readonly ACTIVE: "启用";
        readonly DISABLED: "停用";
    };
    readonly announcement_status: {
        readonly DRAFT: "草稿";
        readonly PUBLISHED: "已发布";
        readonly ARCHIVED: "存档";
    };
    readonly announcement_audience: {
        readonly ALL: "全部用户";
        readonly MERCHANT: "商户";
        readonly CONSUMER: "消费者";
    };
    readonly promotion_type: {
        readonly FULL_REDUCE: "满减";
        readonly DISCOUNT: "折扣";
        readonly BUY_GIFT: "买赠";
        readonly SECOND_HALF: "第二件半价";
    };
    readonly coupon_type: {
        readonly AMOUNT_OFF: "满减券";
        readonly PERCENT_OFF: "折扣券";
        readonly FREE_SHIPPING: "免运费";
        readonly EXCHANGE: "兑换券";
    };
    /** 通用启用态（优惠券/活动等） */
    readonly enable_status: {
        readonly ACTIVE: "启用";
        readonly INACTIVE: "停用";
        readonly DISABLED: "停用";
        readonly ENDED: "已结束";
    };
    readonly sku_enrollment_status: {
        readonly DRAFT: "草稿";
        readonly MAPPING: "映射中";
        readonly TESTED: "已测试";
        readonly PRODUCTION: "生产";
    };
    readonly fund_ledger_type: {
        readonly ORDER_PAYMENT: "订单支付";
        readonly PLATFORM_FEE: "平台抽成";
        readonly CHANNEL_FEE: "通道费";
        readonly MERCHANT_CREDIT: "商户入账";
    };
    readonly fund_direction: {
        readonly IN: "收入";
        readonly OUT: "支出";
    };
    readonly device_ops_event: {
        readonly OFFLINE: "离线";
        readonly NO_SALES: "无销售";
        readonly UNLOCK: "开锁";
        readonly FAULT: "故障/锁机";
        readonly AISLE_AUDIT: "货道巡检";
        readonly MAINBOARD: "主板";
    };
    readonly repair_fault_type: {
        readonly DOOR: "门锁";
        readonly COOLING: "制冷";
        readonly NETWORK: "网络";
        readonly PAYMENT: "支付";
        readonly VISION: "识别";
        readonly POWER: "供电";
        readonly OTHER: "其他";
    };
    readonly line_withdraw_status: {
        readonly PENDING_REVIEW: "待审核";
        readonly APPROVED: "已通过";
        readonly PAYING: "打款中";
        readonly PAID: "已打款";
        readonly REJECTED: "已驳回";
        readonly FAILED: "失败";
    };
    readonly merchant_withdraw_status: {
        readonly PENDING_REVIEW: "待审核";
        readonly APPROVED: "已通过";
        readonly PAYING: "打款中";
        readonly PAID: "已打款";
        readonly REJECTED: "已驳回";
        readonly FAILED: "失败";
    };
    readonly supplier_status: {
        readonly ACTIVE: "启用";
        readonly INACTIVE: "停用";
    };
    readonly purchase_order_status: {
        readonly CREATED: "待收货";
        readonly PARTIAL_RECEIVED: "部分收货";
        readonly RECEIVED: "已收货";
        readonly CANCELLED: "已取消";
    };
    readonly warehouse_status: {
        readonly ACTIVE: "正常";
        readonly INACTIVE: "停用";
    };
    readonly warehouse_outbound_status: {
        readonly DRAFT: "待拣货";
        readonly PICKED: "已拣货";
        readonly SHIPPED: "已发运";
        readonly CANCELLED: "已取消";
    };
    readonly handover_status: {
        readonly PENDING: "待备货";
        readonly READY: "待发运";
        readonly IN_TRANSIT: "在途";
        readonly PARTIAL: "部分签收";
        readonly RECEIVED: "已签收";
    };
    readonly in_transit_status: {
        readonly IN_TRANSIT: "在途";
        readonly RECEIVED: "已签收";
        readonly LOST: "丢失";
        readonly DAMAGED: "破损";
    };
    readonly warehouse_movement_type: {
        readonly PURCHASE_RECEIVE: "采购收货";
        readonly PURCHASE_RETURN: "采购退货";
        readonly MANUAL_INBOUND: "手工入库";
        readonly INBOUND_MANUAL: "手工入库";
        readonly OUTBOUND: "出库";
        readonly OUTBOUND_SHIP: "发运";
        readonly RETURN: "退回";
        readonly ADJUSTMENT: "库存调整";
    };
    readonly business_reference_type: {
        readonly PURCHASE_ORDER: "采购单";
        readonly PURCHASE_RETURN: "采购退货";
        readonly OUTBOUND_ORDER: "出库单";
        readonly WAREHOUSE_INBOUND: "仓库入库";
        readonly WAREHOUSE_OUTBOUND: "仓库出库";
        readonly REPLENISHMENT_TASK: "补货任务";
        readonly INVENTORY_ADJUSTMENT: "库存调整";
        readonly MANUAL: "人工操作";
    };
    readonly replenishment_route_status: {
        readonly PLANNED: "待执行";
        readonly IN_PROGRESS: "执行中";
        readonly COMPLETED: "已完成";
        readonly CANCELLED: "已取消";
    };
    readonly replenishment_task_status: {
        readonly PENDING: "待处理";
        readonly IN_PROGRESS: "进行中";
        readonly COMPLETED: "已完成";
        readonly CANCELLED: "已取消";
    };
    readonly replenishment_request_status: {
        readonly SUBMITTED: "待审核";
        readonly ACCEPTED: "已接单";
        readonly REJECTED: "已驳回";
        readonly COMPLETED: "已完成";
    };
    readonly inventory_lot_status: {
        readonly ON_SALE: "在售";
        readonly NEAR_EXPIRY: "临期";
        readonly BLOCKED: "已冻结";
        readonly DEPLETED: "已耗尽";
    };
    readonly exception_severity: {
        readonly CRITICAL: "紧急";
        readonly HIGH: "高";
        readonly MEDIUM: "中";
        readonly LOW: "低";
    };
    readonly exception_status: {
        readonly OPEN: "待处理";
        readonly PROCESSING: "处理中";
        readonly RESOLVED: "已解决";
        readonly CLOSED: "已关闭";
    };
    readonly exception_type: {
        readonly DISPUTE: "消费争议";
        readonly LOW_STOCK: "低库存";
        readonly EXPIRY: "临期商品";
        readonly REPLENISHMENT_REQUIRED: "待补货";
        readonly DEVICE_OFFLINE: "设备离线";
        readonly DEVICE_FAULT: "设备故障";
        readonly DOOR_OPEN_TOO_LONG: "长时间未关门";
        readonly OPEN_TIMEOUT: "开门超时";
        readonly UPLOAD_STUCK: "录像上传滞留";
        readonly RECOGNITION_STUCK: "识别滞留";
        readonly RECOGNITION_TIMEOUT: "识别超时";
        readonly RECOGNITION_FAILED: "识别存疑需人工审核";
        readonly RECOGNITION_UNAVAILABLE: "识别服务不可用";
        readonly BALANCE_INSUFFICIENT: "余额不足";
        readonly SETTLEMENT_FAILED: "结算失败";
        readonly SETTLEMENT_STUCK: "结算滞留";
        readonly INVENTORY_MISMATCH: "库存差异";
        readonly SLOT_DISCREPANCY: "货道账实差异";
    };
    readonly ops_exception_action: {
        readonly OPS_EXCEPTION_CLAIM: "领取异常";
        readonly OPS_EXCEPTION_TRANSFER: "转派异常";
        readonly OPS_EXCEPTION_NOTE: "添加备注";
        readonly OPS_EXCEPTION_RETRY: "重试识别/结算";
        readonly OPS_EXCEPTION_RETRY_SUCCESS: "重试成功";
        readonly OPS_EXCEPTION_CANCEL_SESSION: "取消会话并释放设备";
        readonly OPS_EXCEPTION_MANUAL_RESOLVE: "人工处置（确认商品/免单）";
        readonly OPS_EXCEPTION_RESOLVE: "标记已解决";
        readonly OPS_EXCEPTION_AUTO_RESOLVE: "系统自动解决";
        readonly MERCHANT_OPS_EXCEPTION_RESOLVE: "商家处理异常";
    };
    readonly reconciliation_status: {
        readonly MATCHED: "已平账";
        readonly MISMATCH: "存在差异";
        readonly PENDING: "待处理";
        readonly FAILED: "失败";
    };
    readonly settlement_batch_status: {
        readonly PENDING: "待结算";
        readonly PROCESSING: "结算中";
        readonly SETTLED: "已结算";
        readonly PAID: "已支付";
        readonly FAILED: "失败";
        readonly PARTIAL_FAILED: "部分失败";
        readonly COMPLETED: "已完成";
    };
    readonly sku_status: {
        readonly ACTIVE: "在售";
        readonly INACTIVE: "停用";
        readonly DISABLED: "禁售";
    };
    readonly order_status: {
        readonly PENDING: "待支付";
        readonly PROCESSING: "处理中";
        readonly PAID: "已支付";
        readonly COMPLETED: "已完成";
        readonly DISPUTED: "争议中";
        readonly REFUNDED: "已退款";
        readonly PARTIAL_REFUNDED: "部分退款";
        readonly FAILED: "处理失败";
        readonly CANCELLED: "已取消";
    };
    readonly route_code: {
        readonly R01: "路线 R01";
        readonly 'R-DEMO-01': "演示路线 01";
        readonly 'R-DEMO-02': "演示路线 02";
        readonly 'R-DEMO-X': "演示路线 X";
    };
};
export type DictType = keyof typeof DICT;
export type DictTagType = 'success' | 'warning' | 'danger' | 'info' | 'primary';
export declare function dictLabel(type: DictType | string, code: string | null | undefined): string;
/**
 * 三端 UI 展示用：优先字典中文，绝不把英文枚举码当文案回退（避免 `|| status` 露出 OPEN/PAID）。
 */
export declare function displayLabel(type: DictType | string, code: string | null | undefined, empty?: string): string;
/** 操作人展示：系统任务 / 无姓名时可读 */
export declare function actorDisplayName(input: {
    name?: string | null;
    phone?: string | null;
    userId?: number | null;
    operatorId?: number | null;
}): string;
export declare function dictOptions(type: DictType | string): {
    value: string;
    label: string;
}[];
export declare function dictTagType(code: string | null | undefined): DictTagType;
/** 审计动作 → 中文（运营后台审计日志筛选项/表格共用） */
export declare const AUDIT_ACTION_LABELS: Record<string, string>;
/** 审计对象类型 → 中文 */
export declare const AUDIT_TARGET_LABELS: Record<string, string>;
export declare function auditActionLabel(action?: string | null): string;
export declare function auditTargetLabel(type?: string | null): string;
/** 审计/异常操作 detail：英文键值对 → 中文说明 */
export declare function formatOpsActionDetail(detail: string | null | undefined): string;
/** @deprecated 使用 formatOpsActionDetail；保留别名便于审计页语义 */
export declare const formatAuditDetail: typeof formatOpsActionDetail;
