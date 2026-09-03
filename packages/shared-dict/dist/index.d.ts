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
    /** 手机验证流水 · 验证渠道（与 pay_channel 不同，含 SMS 等） */
    readonly verify_channel: {
        readonly SMS: "短信验证码";
        readonly SMS_RESET: "短信重置密码";
        readonly WECHAT: "微信";
        readonly ALIPAY: "支付宝";
    };
    /** 站内信 / notification_log.biz_type */
    readonly notification_biz_type: {
        readonly ORDER: "订单";
        readonly RECHARGE: "充值";
        readonly REPLENISHMENT: "补货";
        readonly DISPUTE: "争议/售后";
        readonly COUPON: "优惠券";
        readonly POINTS: "积分";
        readonly RECALL: "用户召回";
        readonly MERCHANT: "商户通知";
        readonly SETTLEMENT: "结算";
        readonly SESSION: "购物会话";
        readonly OPS_MANUAL: "运营手工";
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
    /** 消费者故障报修 · 问题类型（运营可在字典管理调整文案/增删选项） */
    readonly device_fault_issue: {
        readonly DOOR_OPEN: "打不开门";
        readonly DOOR_WONT_OPEN: "打不开门";
        readonly DOOR_CLOSE: "门关不上";
        readonly DOOR_WONT_CLOSE: "门关不上";
        readonly PRODUCT: "商品异常";
        readonly PAYMENT: "扣款问题";
        readonly OTHER: "其他";
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
        readonly ARCHIVED: "已归档";
    };
    readonly announcement_audience: {
        readonly ALL: "全部用户";
        readonly MERCHANT: "商户";
        readonly CONSUMER: "消费者";
    };
    readonly user_role: {
        readonly CONSUMER: "消费者";
        readonly OPERATOR: "运营账号";
        readonly MERCHANT: "商户";
        readonly OPS: "运营";
    };
    readonly promotion_type: {
        readonly FULL_REDUCE: "满减";
        readonly DISCOUNT: "折扣";
        readonly BUY_GIFT: "买赠";
        readonly SECOND_HALF: "第二件半价";
        readonly NEW_USER: "新客";
        readonly POINTS: "积分";
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
        readonly DRAFT: "草稿";
        readonly STOPPED: "已停止";
    };
    /** 商户/线长钱包流水 · 关联业务类型 */
    readonly wallet_ref_type: {
        readonly ORDER: "订单";
        readonly WITHDRAW: "提现";
        readonly OPS_ADJUST: "运营调账";
        readonly COMMISSION_DAILY: "日结佣金";
        readonly SPLIT: "分账";
        readonly SPLIT_PARTIAL: "分账增额";
        readonly SPLIT_PARTIAL_REV: "分账冲正";
        readonly RECHARGE: "充值";
        readonly REFUND: "退款";
        readonly SEED: "演示初始";
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
    /** 商户/线长钱包流水类型 */
    readonly wallet_ledger_type: {
        readonly MERCHANT_CREDIT: "商户入账";
        readonly LINE_COMMISSION: "线长佣金";
        readonly WITHDRAW_FREEZE: "提现冻结";
        readonly WITHDRAW_RELEASE: "提现解冻";
        readonly WITHDRAW_PAID: "提现打款";
        readonly ADJUST: "调账";
        readonly REVERSE: "冲正";
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
        readonly PENDING_APPROVAL: "待审批";
        readonly CREATED: "待收货";
        readonly PARTIAL_RECEIVED: "部分收货";
        readonly RECEIVED: "已收货";
        readonly REJECTED: "已驳回";
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
    /** 审批待办 / approval_instance.biz_type（与 sys_dict approval_biz_type 对齐） */
    readonly approval_biz_type: {
        readonly MERCHANT_REPLEN_REQUEST: "商户要货";
        readonly PURCHASE_ORDER: "采购单";
        readonly MERCHANT_WITHDRAW: "商户提现";
        readonly LINE_WITHDRAW: "线长提现";
        readonly BALANCE_REFUND: "余额退款";
        readonly MERCHANT_WALLET_ADJUST: "商户调账";
        readonly MERCHANT_ONBOARD: "商户进件";
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
    readonly pull_off_reason: {
        readonly EXPIRED: "已过期";
        readonly NEAR_EXPIRY: "临期";
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
        readonly VISION_ANOMALY: "视觉异常（端侧）";
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
    /** 对账平台账单行 trade_type（mock 为 PAY/RECHARGE；渠道账单偶发写入渠道码） */
    readonly platform_bill_trade_type: {
        readonly PAY: "支付";
        readonly RECHARGE: "充值";
        readonly REFUND: "退款";
        readonly WECHAT: "微信支付";
        readonly ALIPAY: "支付宝支付";
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
    readonly scheduled_task_group: {
        readonly DEVICE: "设备";
        readonly TRADE: "交易";
        readonly OPS: "运维";
        readonly SYSTEM: "系统";
        readonly WAREHOUSE: "仓储";
        readonly MERCHANT: "商户";
        readonly FINANCE: "财务";
        readonly MARKETING: "营销";
    };
    readonly wallet_entry_type: {
        readonly ADJUST: "运营调整";
        readonly COMMISSION: "佣金入账";
        readonly COMMISSION_DAILY: "日结佣金";
        readonly BOUNTY: "地推赏金";
        readonly WITHDRAW_FREEZE: "提现冻结";
        readonly WITHDRAW_RELEASE: "提现解冻";
        readonly WITHDRAW_PAID: "提现打款";
        readonly SPLIT_CREDIT: "分账入账";
        readonly SPLIT_REVERSE: "分账退回";
        readonly RECHARGE: "充值";
        readonly RECHARGE_REFUND: "充值退款";
        readonly BALANCE_REFUND: "余额退款";
        readonly BALANCE_REFUND_FREEZE: "退款申请冻结";
        readonly BALANCE_REFUND_RELEASE: "退款申请解冻";
        readonly REFUND: "退款";
        readonly SETTLE: "结算入账";
        readonly PAYOUT: "打款";
        readonly FEE: "手续费";
    };
    readonly session_kind: {
        readonly CONSUMER: "消费";
        readonly RESTOCK: "补货";
        readonly OPS: "运维";
        /** 历史值，与 CONSUMER 同义 */
        readonly SHOPPING: "消费";
    };
    readonly restock_line_type: {
        readonly RESTOCK: "上架";
        readonly PULL_OFF: "下架";
        readonly REMOVE: "下架";
        readonly PULL: "下架";
    };
    readonly ops_alert_type: {
        readonly DISPUTE: "账单争议";
        readonly DEVICE_OFFLINE: "设备离线";
        readonly UPLOAD_STUCK: "录像滞留";
        readonly SESSION_STALE: "会话超时";
        readonly LOW_STOCK: "库存不足";
        readonly REPLENISHMENT: "补货任务";
        readonly RECON_MISMATCH: "对账差异";
        readonly RECONCILIATION_MISMATCH: "对账差异";
        readonly SPLIT_EXCEPTION: "分账异常";
        readonly IN_TRANSIT_OVERDUE: "签收超时";
    };
    readonly ad_asset_type: {
        readonly IMAGE: "图片";
        readonly VIDEO: "视频";
        readonly H5: "H5";
    };
    readonly ad_campaign_status: {
        readonly DRAFT: "草稿";
        readonly RUNNING: "投放中";
        readonly STOPPED: "已停止";
    };
    readonly consistency_check_type: {
        readonly ORDER_AMOUNT: "订单金额";
        readonly PAYMENT_AMOUNT: "支付净额";
        readonly INVENTORY_MISMATCH: "库存汇总";
        readonly POINTS_BALANCE: "积分余额";
        readonly COUPON_ISSUED: "发券数量";
        readonly WALLET_BALANCE: "钱包余额";
        readonly REFUND_AMOUNT: "退款金额";
        readonly ORDER_LINE_SUM: "订单行金额";
        readonly COUPON_USED_LINK: "券核销关联";
    };
    readonly sku_perf_level: {
        readonly BEST_SELLER: "畅销";
        readonly NORMAL: "正常";
        readonly SLOW_MOVER: "慢销";
        readonly NO_SALES: "无销量";
    };
    readonly sku_review_status: {
        readonly PENDING: "待评审";
        readonly RECOMMEND_DELIST: "建议下架";
        readonly DELISTED: "已下架";
        readonly KEPT: "已保留";
    };
    readonly member_level: {
        readonly NORMAL: "普通";
        readonly SILVER: "白银";
        readonly GOLD: "黄金";
        readonly PLATINUM: "铂金";
        readonly DIAMOND: "钻石";
    };
    readonly site_contract_status: {
        readonly ACTIVE: "有效";
        readonly EXPIRING: "临期";
        readonly EXPIRED: "已到期";
    };
    readonly device_env_type: {
        readonly HUMIDITY: "湿度";
        readonly VOLTAGE: "电压";
        readonly POWER: "功耗";
    };
    readonly device_lifecycle_action: {
        readonly BIND: "绑定商户";
        readonly UNBIND: "解绑";
        readonly DEPLOY: "投放";
        readonly UNDEPLOY: "撤回未投放";
        readonly RETURN: "返厂";
        readonly RETIRE: "退役";
        readonly INBOUND: "入库";
    };
    readonly stock_health_dim: {
        readonly STOCKOUT: "断货";
        readonly LOW: "低库存";
        readonly NEAR_EXPIRY: "临期";
    };
    readonly purchase_suggestion_reason: {
        readonly SALES_DRIVEN: "销量驱动";
        readonly TREND_FORECAST: "趋势预测";
        readonly LOW_STOCK: "库存不足";
    };
    readonly supplier_payable_status: {
        readonly UNPAID: "未付";
        readonly PARTIAL: "部分付款";
        readonly PAID: "已付";
        readonly CLOSED: "已关闭";
    };
    readonly stocktake_mode: {
        readonly BLIND: "盲盘";
        readonly VISIBLE: "明盘";
    };
    readonly stocktake_status: {
        readonly DRAFT: "草稿";
        readonly IN_PROGRESS: "盘点中";
        readonly COMPLETED: "已完成";
        readonly ADJUSTED: "已调整";
        readonly CANCELLED: "已取消";
    };
    readonly stocktake_line_status: {
        readonly PENDING: "未盘";
        readonly MATCHED: "相符";
        readonly DIFF: "有差异";
        readonly ADJUSTED: "已调整";
    };
    readonly merchant_alert_type: {
        readonly LOW_STOCK: "低库存";
        readonly EXPIRY: "临期";
        readonly REPLENISHMENT_REQUIRED: "需补货";
        readonly REPLENISHMENT: "补货任务";
        readonly DEVICE_OFFLINE: "柜机离线";
        readonly DEVICE_FAULT: "柜机故障";
        readonly DISPUTE: "消费争议";
        readonly SETTLEMENT_FAILED: "结算失败";
    };
    /** 商品类目：运营在字典管理维护；runtime 为准 */
    readonly category_code: Record<string, string>;
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
/** 运营异常 detail：兼容历史枚举码与英文键值，转为中文说明 */
export declare function formatExceptionDetail(detail: string | null | undefined): string;
/** 风控事件 detail：JSON 或键值对 → 中文说明 */
export declare function formatRiskEventDetail(detail: string | null | undefined): string;
/** 审计/异常操作 detail：英文键值对 → 中文说明 */
export declare function formatOpsActionDetail(detail: string | null | undefined): string;
/** @deprecated 使用 formatOpsActionDetail；保留别名便于审计页语义 */
export declare const formatAuditDetail: typeof formatOpsActionDetail;
