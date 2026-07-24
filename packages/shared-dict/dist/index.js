/**
 * Shared dict labels (compile-time baseline + optional runtime overrides).
 *
 * Contract:
 * - Dict = display metadata (labels, filter options, tags). Not capability switches.
 * - Payment / feature ability stays in Java constants (e.g. PayChannels) + env flags
 *   (ALIPAY_ENABLED, PAYSCORE_*). Disabling a dict item must not block checkout.
 * - New business enum codes: add in backend first, then seed here + SysDictBootstrap.
 * - Clients load GET /api/v2/dicts/runtime when logged in; on failure keep DICT defaults.
 */
/** Runtime overrides from ops dict admin (value -> label per type). */
let runtimeOverrides = {};
export function setDictOverrides(map) {
    runtimeOverrides = map || {};
}
export function clearDictOverrides() {
    runtimeOverrides = {};
}
/** 将 runtime 字典转为 setDictOverrides 可用的 value→label 映射。 */
export function buildOverridesFromRuntime(payload) {
    const map = {};
    for (const [type, rows] of Object.entries(payload?.itemsByType || {})) {
        map[type] = {};
        for (const row of rows || []) {
            if (row.status && row.status !== 'ACTIVE')
                continue;
            if (!row.dictValue)
                continue;
            map[type][row.dictValue] = row.dictLabel || row.dictValue;
        }
    }
    return map;
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
        FAILED: '失败'
    },
    merchant_status: { ACTIVE: '正常', INACTIVE: '停用', PENDING: '待审核' },
    online_status: { ONLINE: '在线', OFFLINE: '离线', UNKNOWN: '未知' },
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
    replenishment_route_status: { PLANNED: '待执行', IN_PROGRESS: '执行中', COMPLETED: '已完成', CANCELLED: '已取消' },
    replenishment_task_status: { PENDING: '待处理', IN_PROGRESS: '进行中', COMPLETED: '已完成', CANCELLED: '已取消' },
    replenishment_request_status: { SUBMITTED: '待审核', ACCEPTED: '已接单', REJECTED: '已驳回', COMPLETED: '已完成' },
    inventory_lot_status: { ON_SALE: '在售', NEAR_EXPIRY: '临期', BLOCKED: '已冻结', DEPLETED: '已耗尽' },
    exception_severity: { CRITICAL: '紧急', HIGH: '高', MEDIUM: '中', LOW: '低' },
    exception_status: { OPEN: '待处理', PROCESSING: '处理中', RESOLVED: '已解决', CLOSED: '已关闭' },
    exception_type: {
        DISPUTE: '消费争议', LOW_STOCK: '低库存', EXPIRY: '临期商品', REPLENISHMENT_REQUIRED: '待补货',
        DEVICE_OFFLINE: '设备离线', DEVICE_FAULT: '设备故障', DOOR_OPEN_TOO_LONG: '长时间未关门',
        OPEN_TIMEOUT: '开门超时', UPLOAD_STUCK: '录像上传滞留', RECOGNITION_STUCK: '识别滞留',
        RECOGNITION_TIMEOUT: '识别超时', RECOGNITION_FAILED: '识别存疑需人工审核',
        RECOGNITION_UNAVAILABLE: '识别服务不可用',
        BALANCE_INSUFFICIENT: '余额不足', SETTLEMENT_FAILED: '结算失败', SETTLEMENT_STUCK: '结算滞留',
        INVENTORY_MISMATCH: '库存差异', SLOT_DISCREPANCY: '货道账实差异'
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
    reconciliation_status: { MATCHED: '已平账', MISMATCH: '存在差异', PENDING: '待处理', FAILED: '失败' },
    sku_status: { ACTIVE: '在售', INACTIVE: '停用', DISABLED: '禁售' },
    order_status: {
        PENDING: '待支付', PROCESSING: '处理中', PAID: '已支付', COMPLETED: '已完成',
        DISPUTED: '争议中', REFUNDED: '已退款', FAILED: '处理失败', CANCELLED: '已取消'
    }
};
const STATUS_TAGS = {
    ACTIVE: 'success', ONLINE: 'success', COMPLETED: 'success', RECEIVED: 'success', SUCCESS: 'success', ON_SALE: 'success',
    MATCHED: 'success', RESOLVED: 'success', CLOSED: 'success',
    CREATED: 'info', DRAFT: 'info', PENDING: 'info', SUBMITTED: 'info', OPEN: 'warning',
    PROCESSING: 'warning',
    LOW: 'info', MEDIUM: 'warning',
    PARTIAL_RECEIVED: 'warning', PICKED: 'warning', IN_PROGRESS: 'warning', IN_TRANSIT: 'warning', NEAR_EXPIRY: 'warning', PARTIAL: 'warning', HIGH: 'warning',
    INACTIVE: 'danger', OFFLINE: 'danger', FAILED: 'danger', REJECTED: 'danger', CANCELLED: 'danger', BLOCKED: 'danger', LOST: 'danger', DAMAGED: 'danger', CRITICAL: 'danger', MISMATCH: 'danger'
};
export function dictLabel(type, code) {
    const key = String(code || '').toUpperCase();
    const override = runtimeOverrides[type]?.[key] ?? runtimeOverrides[type]?.[String(code || '')];
    if (override)
        return override;
    const map = DICT[type];
    if (!map)
        return code || '-';
    return map[key] ?? map[code] ?? code ?? '-';
}
export function dictOptions(type) {
    const override = runtimeOverrides[type];
    if (override && Object.keys(override).length) {
        return Object.entries(override).map(([value, label]) => ({ value, label }));
    }
    const map = DICT[type];
    return Object.entries(map || {}).map(([value, label]) => ({ value, label }));
}
export function dictTagType(code) {
    return STATUS_TAGS[String(code || '').toUpperCase()] || 'info';
}
/** 将异常操作审计 detail 中的英文键值转为可读中文说明 */
export function formatOpsActionDetail(detail) {
    if (!detail)
        return '-';
    let text = detail.trim();
    text = text.replace(/^idempotencyKey=([^;]+);\s*/i, '幂等键：$1\n');
    text = text.replace(/\bassignee=(\d+)/gi, '接收人：用户 $1');
    text = text.replace(/\breason=/gi, '原因：');
    text = text.replace(/\bresult=/gi, '结果：');
    text = text.replace(/人工免单，退回余额 (\d+) 分/g, (_, cents) => `人工免单，退回余额 ¥${(+cents / 100).toFixed(2)}`);
    text = text.replace(/原金额=(\d+) 分，最终金额=(\d+) 分，差额=(-?\d+) 分/g, (_, orig, final, adj) => `原金额 ¥${(+orig / 100).toFixed(2)}，最终金额 ¥${(+final / 100).toFixed(2)}，差额 ¥${(+adj / 100).toFixed(2)}`);
    if (/^[A-Z_]+$/.test(text)) {
        return dictLabel('exception_type', text);
    }
    return text;
}
