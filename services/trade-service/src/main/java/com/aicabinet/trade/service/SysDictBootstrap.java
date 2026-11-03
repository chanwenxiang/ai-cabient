package com.aicabinet.trade.service;
import com.aicabinet.common.constants.CabinetConstants;

import com.aicabinet.trade.domain.SysDictData;
import com.aicabinet.trade.domain.SysDictType;
import com.aicabinet.trade.mapper.SysDictDataMapper;
import com.aicabinet.trade.mapper.SysDictTypeMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Seeds sys_dict_* from the same baseline as packages/shared-dict when empty.
 */
@Component
public class SysDictBootstrap implements ApplicationRunner {
    private static final String DEVICE_OFFLINE = "DEVICE_OFFLINE";
    private static final String STATUS_IN_PROGRESS = "IN_PROGRESS";
    private static final String PROCESSING = "PROCESSING";
    private static final String STATUS_COMPLETED = "COMPLETED";
    private static final String STATUS_CANCELLED = "CANCELLED";
    private static final String LOW_STOCK = "LOW_STOCK";
    private static final String INACTIVE = "INACTIVE";
    private static final String RECEIVED = "RECEIVED";
    private static final String STATUS_REJECTED = "REJECTED";
    private static final String STATUS_PENDING = "PENDING";
    private static final String CREATED = "CREATED";
    private static final String DISPUTE = "DISPUTE";
    private static final String PAYMENT = "PAYMENT";
    private static final String STATUS_CLOSED = "CLOSED";
    private static final String STATUS_NORMAL = "NORMAL";
    private static final String OTHER = "OTHER";


    private static final Logger log = LoggerFactory.getLogger(SysDictBootstrap.class);

    private final SysDictTypeMapper typeRepository;
    private final SysDictDataMapper dataRepository;

    public SysDictBootstrap(SysDictTypeMapper typeRepository, SysDictDataMapper dataRepository) {
        this.typeRepository = typeRepository;
        this.dataRepository = dataRepository;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (typeRepository.count() == 0) {
            int sort = 1;
            for (Map.Entry<String, SeedType> entry : seedCatalog().entrySet()) {
                SysDictType type = new SysDictType();
                type.setDictType(entry.getKey());
                type.setDictName(entry.getValue().name());
                type.setStatus(CabinetConstants.PROMOTION_STATUS_ACTIVE);
                type.setSortOrder(sort++);
                typeRepository.save(type);
                int itemSort = 1;
                for (Map.Entry<String, String> item : entry.getValue().items().entrySet()) {
                    SysDictData data = new SysDictData();
                    data.setDictType(entry.getKey());
                    data.setDictValue(item.getKey());
                    data.setDictLabel(item.getValue());
                    data.setSortOrder(itemSort++);
                    data.setStatus(CabinetConstants.PROMOTION_STATUS_ACTIVE);
                    dataRepository.save(data);
                }
            }
            log.info("Seeded {} dict types", typeRepository.count());
        }
        // Incremental: new types/items after first seed (keep DB in sync with shared-dict baseline).
        for (String dictType : seedCatalog().keySet()) {
            ensureMissingDictType(dictType);
            ensureMissingDictItems(dictType);
        }
    }

    private void ensureMissingDictType(String dictType) {
        if (typeRepository.findByDictType(dictType).isPresent()) {
            return;
        }
        SeedType seed = seedCatalog().get(dictType);
        if (seed == null) {
            return;
        }
        int sort = (int) typeRepository.count() + 1;
        SysDictType type = new SysDictType();
        type.setDictType(dictType);
        type.setDictName(seed.name());
        type.setStatus(CabinetConstants.PROMOTION_STATUS_ACTIVE);
        type.setSortOrder(sort);
        typeRepository.save(type);
        int itemSort = 1;
        for (Map.Entry<String, String> item : seed.items().entrySet()) {
            SysDictData data = new SysDictData();
            data.setDictType(dictType);
            data.setDictValue(item.getKey());
            data.setDictLabel(item.getValue());
            data.setSortOrder(itemSort++);
            data.setStatus(CabinetConstants.PROMOTION_STATUS_ACTIVE);
            dataRepository.save(data);
        }
        log.info("Seeded missing dict type {}", dictType);
    }

    private void ensureMissingDictItems(String dictType) {
        SeedType seed = seedCatalog().get(dictType);
        if (seed == null) {
            return;
        }
        int added = 0;
        int sort = (int) dataRepository.countByDictType(dictType) + 1;
        for (Map.Entry<String, String> item : seed.items().entrySet()) {
            if (dataRepository.findByDictTypeAndDictValue(dictType, item.getKey()).isPresent()) {
                continue;
            }
            SysDictData data = new SysDictData();
            data.setDictType(dictType);
            data.setDictValue(item.getKey());
            data.setDictLabel(item.getValue());
            data.setSortOrder(sort++);
            data.setStatus(CabinetConstants.PROMOTION_STATUS_ACTIVE);
            dataRepository.save(data);
            added++;
        }
        if (added > 0) {
            log.info("Added {} missing dict items for {}", added, dictType);
        }
    }

    private record SeedType(String name, Map<String, String> items) {}

    /** MA-079：代码内置字典类型（OrgService 等以它为写入白名单），运营台不得硬删。 */
    public static boolean isBuiltinDictType(String dictType) {
        return seedCatalog().containsKey(dictType);
    }

    private static Map<String, SeedType> seedCatalog() {
        Map<String, SeedType> map = new LinkedHashMap<>();
        map.put("device_type", t("设备类型", m("AI_CABINET_V1", "AI智能柜 V1")));
        map.put("session_state", t("会话状态", m(
                CREATED, "已创建", "OPENING", "开门中", "SHOPPING", "购物中",
                "RECOGNIZING", "识别商品中", "WAITING_UPLOAD", "录像上传中", "SETTLING", "结算中",
                STATUS_COMPLETED, "已完成", "DISPUTED", "待审核", CabinetConstants.ORDER_STATUS_FAILED, "失败", STATUS_CANCELLED, "已取消")));
        map.put("upload_status", t("上传状态", m(
                "NONE", "无需上传", "LOCAL_QUEUED", "待上传", "UPLOADING", "上传中",
                "UPLOADED", "已上传", CabinetConstants.ORDER_STATUS_FAILED, "上传失败")));
        map.put("dispute_status", t("争议状态", m("OPEN", "待审核", "RESOLVED", "已结案", STATUS_CLOSED, "已关闭")));
        map.put("dispute_category", t("争议分类", m(
                "USER_APPEAL", "用户申诉", "RECOGNITION", "识别争议", "VIDEO_MISSING", "录像缺失",
                PAYMENT, "支付相关", "INVENTORY", "库存相关", "BILL", "账单争议", OTHER, "其他")));
        map.put("dispute_priority", t("争议优先级", m(
                "LOW", "低", STATUS_NORMAL, "普通", "HIGH", "高", "URGENT", "紧急")));
        map.put("pay_channel", t("支付渠道", m(
                "WECHAT", "微信", "ALIPAY", "支付宝", "PAYSCORE", "微信支付分", "MOCK", "其他", "BALANCE", "余额", "UNKNOWN", "未知")));
        map.put("split_status", t("分账状态", m(
                STATUS_PENDING, "待处理", "LEDGER_ONLY", "仅记账", "ACCRUED", "待分账",
                "WECHAT_SUBMITTED", "已提交", "WECHAT_FAILED", "失败",
                "SUCCESS", "成功", "SETTLED", "已完结", "VOIDED", "已冲正")));
        map.put("merchant_status", t("商户状态", m(CabinetConstants.PROMOTION_STATUS_ACTIVE, "正常", INACTIVE, "停用", STATUS_PENDING, "待审核")));
        map.put("online_status", t("在线状态", m("ONLINE", "在线", "OFFLINE", "离线", "UNKNOWN", "未知")));
        map.put("device_lifecycle", t("设备生命周期", m(
                "IDLE", "未投放", "INBOUND", "入库", "DEPLOYED", "投放", "RETURNING", "返厂中", "RETIRED", "退役")));
        map.put("device_coop_mode", t("设备合作方式", m(
                "SELF", "自营", "FRANCHISE", "加盟", "CONSIGN", "联营")));
        map.put("repair_ticket_status", t("维修工单状态", m(
                "OPEN", "待处理", STATUS_IN_PROGRESS, "处理中", "DONE", "已完成",
                STATUS_CANCELLED, "已取消", STATUS_CLOSED, "已关闭")));
        map.put("line_manager_status", t("线长状态", m(CabinetConstants.PROMOTION_STATUS_ACTIVE, "启用", CabinetConstants.SKU_STATUS_DISABLED, "停用")));
        map.put("announcement_status", t("公告状态", m(
                CabinetConstants.PROMOTION_STATUS_DRAFT, "草稿", "PUBLISHED", "已发布", "ARCHIVED", "已归档")));
        map.put("announcement_audience", t("公告受众", m(
                "ALL", "全部用户", "MERCHANT", "商户", "CONSUMER", "消费者")));
        map.put("promotion_type", t("营销活动类型", m(
                "FULL_REDUCE", "满减", "DISCOUNT", "折扣", "BUY_GIFT", "买赠", "SECOND_HALF", "第二件半价",
                "NEW_USER", "新客", "POINTS", "积分",
                // CB-082 v1：SKU 级活动（定价引擎可执行）
                "SKU_REDUCE", "商品立减", "SKU_DISCOUNT", "商品折扣", "SKU_FIXED_PRICE", "一口价")));
        map.put("promotion_placement_position", t("活动展示位", m(
                "BANNER", "轮播横幅", "POPUP_BEFORE_OPEN", "开门前弹窗", "POPUP_AFTER_CLOSE", "关门后弹窗")));
        map.put("coupon_type", t("优惠券类型", m(
                "AMOUNT_OFF", "满减券", "PERCENT_OFF", "折扣券",
                "FREE_SHIPPING", "免运费", "EXCHANGE", "兑换券")));
        map.put("enable_status", t("启用状态", m(
                CabinetConstants.PROMOTION_STATUS_ACTIVE, "启用", INACTIVE, "停用", "ENDED", "已结束")));
        // MA-018：货位状态独立成 bin_status（DISABLED 只在这里用），避免与 enable_status 的「停用」同名
        map.put("bin_status", t("货位状态", m(
                CabinetConstants.PROMOTION_STATUS_ACTIVE, "启用", CabinetConstants.SKU_STATUS_DISABLED, "停用")));
        map.put("sku_enrollment_status", t("商品识别入驻状态", m(
                CabinetConstants.PROMOTION_STATUS_DRAFT, "草稿", "MAPPING", "映射中", "TESTED", "已测试", "PRODUCTION", "生产")));
        map.put("fund_ledger_type", t("资金账务类型", m(
                "ORDER_PAYMENT", "订单支付", "PLATFORM_FEE", "平台抽成",
                "CHANNEL_FEE", "通道费", "MERCHANT_CREDIT", "商户入账")));
        map.put("fund_direction", t("资金收支方向", m("IN", "收入", "OUT", "支出")));
        map.put("scheduled_task_group", t("定时任务分组", m(
                "DEVICE", "设备", "TRADE", "交易", "OPS", "运维", "SYSTEM", "系统",
                "WAREHOUSE", "仓储", "MERCHANT", "商户", "FINANCE", "财务", "MARKETING", "营销")));
        map.put("wallet_entry_type", t("钱包流水类型", m(
                "ADJUST", "运营调整", "COMMISSION", "佣金入账", "COMMISSION_DAILY", "日结佣金",
                "BOUNTY", "地推赏金",
                "WITHDRAW_FREEZE", "提现冻结", "WITHDRAW_RELEASE", "提现解冻", "WITHDRAW_PAID", "提现打款",
                "SPLIT_CREDIT", "分账入账", "SPLIT_REVERSE", "分账冲正",
                "RECHARGE", "充值", "RECHARGE_REFUND", "充值退款", "REFUND", "退款",
                "SETTLE", "结算入账", "PAYOUT", "打款", "FEE", "手续费",
                // V436（MA-132/133）：后端真实写入但字典缺项，补种必须与迁移同步
                "COMMISSION_CLAWBACK", "佣金冲回", "WITHDRAW_FEE", "提现手续费",
                "WITHDRAW_PAYOUT", "提现出账")));
        // V436（MA-135）：notification_log.biz_type 此前根本不在自愈目录里
        // （V225 只种了 11 值，DAILY_INCOME/FEE_REMINDER 是后来调度器新增的下发值）
        // ⇒ ensureMissingDictItems 连「该类型存在」都不知道，永远补不出来。
        map.put("notification_biz_type", t("站内信业务类型", m(
                "ORDER", "订单", "RECHARGE", "充值", "REPLENISHMENT", "补货",
                "DISPUTE", "争议/售后", "COUPON", "优惠券", "POINTS", "积分",
                "RECALL", "用户召回", "MERCHANT", "商户通知", "SETTLEMENT", "结算",
                "SESSION", "购物会话", "OPS_MANUAL", "运营手工",
                "DAILY_INCOME", "日结收入", "FEE_REMINDER", "欠费提醒")));
        // V436（MA-131）：设备租赁账单状态此前无字典，admin 自造 map 把作废写成 VOIDED
        // 而后端常量是 VOID ⇒ 状态裸码 +「已作废」筛选恒空。词表对齐同族 site_rent_bill_status。
        map.put("device_rental_bill_status", t("设备租赁账单状态", m(
                "UNPAID", "待付", "PAID", "已付", "VOID", "已作废")));
        map.put("session_kind", t("会话类型", m(
                "CONSUMER", "消费", "RESTOCK", "补货", "OPS", "运维", "STOCKTAKE", "盘点")));
        map.put("restock_line_type", t("补货行类型", m(
                "RESTOCK", "上架", "PULL_OFF", "下架")));
        map.put("ops_alert_type", t("运维告警类型", m(
                DISPUTE, "账单争议", DEVICE_OFFLINE, "设备离线",
                "SALES_LOCKED", "柜机停售",
                "UPLOAD_STUCK", "录像滞留", "SESSION_STALE", "会话超时",
                LOW_STOCK, "库存不足", "REPLENISHMENT", "补货任务",
                "RECON_MISMATCH", "对账差异",
                "SPLIT_EXCEPTION", "分账异常", "PROFIT_SHARING_RETURN_FAILED", "分账回退失败",
                "PROFIT_SHARING_MANUAL_SUPPLEMENT", "分账需人工补账",
                // M06+M07：后端实际会发出但此前未 seed 的告警类型，补齐与 shared-dict 对齐
                "PROFIT_SHARING_RETURN_SUBMIT_FAILED", "分账回退提交失败",
                "XXL_JOB_WIRING_BROKEN", "XXL-JOB 执行器接线异常",
                "SCHEDULED_TASK_STALE", "定时任务停摆",
                "VISION_ANOMALY", "视觉异常告警",
                "IN_TRANSIT_OVERDUE", "未上柜超时")));
        map.put("ad_asset_type", t("广告素材类型", m(
                "IMAGE", "图片", "VIDEO", "视频", "H5", "H5")));
        map.put("ad_campaign_status", t("投放计划状态", m(
                CabinetConstants.PROMOTION_STATUS_DRAFT, "草稿", "RUNNING", "投放中", "STOPPED", "已停止")));
        map.put("consistency_check_type", t("一致性检查类型", m(
                "ORDER_AMOUNT", "订单金额", "PAYMENT_AMOUNT", "支付净额",
                "PAID_GROSS_MISMATCH", "冻结毛额入账",
                "INVENTORY_MISMATCH", "库存汇总", "INVENTORY_ORPHAN_LOT", "孤儿批次",
                "INVENTORY_NO_LOT_LEDGER", "无批次账本",
                "POINTS_BALANCE", "积分余额", "POINTS_IDENTITY", "积分恒等式",
                "COUPON_ISSUED", "发券数量", "COUPON_OVER_QUOTA", "发券超配额",
                "WALLET_BALANCE", "用户钱包", "MERCHANT_WALLET", "商户钱包", "LINE_WALLET", "线路钱包",
                "STAFF_WALLET", "补货员钱包",
                "MERCHANT_WALLET_LEDGER_SUM", "商户钱包流水合计",
                "LINE_WALLET_LEDGER_SUM", "线长钱包流水合计",
                "STAFF_WALLET_LEDGER_SUM", "补货员钱包流水合计",
                "WALLET_LEDGER_CHAIN", "钱包流水链断裂",
                "REFUND_AMOUNT", "退款金额",
                "ORDER_LINE_SUM", "订单行金额", "COUPON_USED_LINK", "券核销关联",
                "REVENUE_SPLIT_SUM", "分账金额", "REVENUE_SPLIT_MISSING", "分账缺失",
                "SLOT_SKU_MISMATCH", "货道SKU", "SLOT_CAPACITY", "货道容量",
                "SLOT_PHYSICAL", "货道盘点", "WAREHOUSE_NEGATIVE", "仓存负库存",
                "CROSS_LINK", "关联投影")));
        map.put("sku_perf_level", t("选品表现等级", m(
                "BEST_SELLER", "畅销", STATUS_NORMAL, "正常", "SLOW_MOVER", "慢销", "NO_SALES", "无销量")));
        map.put("sku_review_status", t("选品评审状态", m(
                STATUS_PENDING, "待评审", "RECOMMEND_DELIST", "建议下架",
                "DELISTED", "已下架", "KEPT", "已保留")));
        map.put("member_level", t("会员等级", m(
                STATUS_NORMAL, "普通", "SILVER", "白银", "GOLD", "黄金",
                "PLATINUM", "铂金", "DIAMOND", "钻石")));
        map.put("site_contract_status", t("点位合同状态", m(
                CabinetConstants.PROMOTION_STATUS_ACTIVE, "有效", "EXPIRING", "临期", "EXPIRED", "已到期")));
        map.put("site_rent_bill_status", t("场地租金账单状态", m(
                "UNPAID", "待付", "PAID", "已付", "VOID", "已作废")));
        map.put("device_data_fee_bill_status", t("柜机流量费账单状态", m(
                "UNPAID", "待付", "PAID", "已付", "VOID", "已作废")));
        map.put("ops_fee_type", t("运营周期费用类型", m(
                "SITE_RENT", "场地租金", "DATA_FEE", "流量费")));
        map.put("site_rent_party_type", t("场地租金分账角色", m(
                "LANDLORD", "场地主", "PLATFORM", "平台", "MERCHANT", "商户",
                "FRANCHISE", "加盟", "OTHER", "其他")));
        map.put("device_env_type", t("设备环境指标类型", m(
                "HUMIDITY", "湿度", "VOLTAGE", "电压", "POWER", "功耗")));
        map.put("device_lifecycle_action", t("设备生命周期操作", m(
                "BIND", "绑定商户", "UNBIND", "解绑", "DEPLOY", "投放",
                "UNDEPLOY", "撤回未投放", "RETURN", "返厂", "RETIRE", "退役", "INBOUND", "入库")));
        map.put("stock_health_dim", t("库存健康维度", m(
                "STOCKOUT", "断货", "LOW", "低库存", "NEAR_EXPIRY", "临期")));
        map.put("purchase_suggestion_reason", t("补货建议原因", m(
                "SALES_DRIVEN", "销量驱动", "TREND_FORECAST", "趋势预测", LOW_STOCK, "库存不足")));
        map.put("supplier_payable_status", t("供应商应付状态", m(
                "UNPAID", "未付", "PARTIAL", "部分付款", "PAID", "已付", STATUS_CLOSED, "已关闭")));
        map.put("stocktake_mode", t("盘点方式", m(
                "BLIND", "盲盘", "VISIBLE", "明盘")));
        map.put("stocktake_status", t("盘点单状态", m(
                CabinetConstants.PROMOTION_STATUS_DRAFT, "草稿", STATUS_IN_PROGRESS, "盘点中", STATUS_COMPLETED, "已完成",
                "ADJUSTED", "已调整", STATUS_CANCELLED, "已取消")));
        map.put("stocktake_line_status", t("盘点行状态", m(
                STATUS_PENDING, "未盘", "MATCHED", "相符", "DIFF", "有差异", "ADJUSTED", "已调整")));
        map.put("merchant_alert_type", t("商户告警类型", m(
                LOW_STOCK, "低库存", "EXPIRY", "临期", "REPLENISHMENT_REQUIRED", "需补货",
                "REPLENISHMENT", "补货任务",
                DEVICE_OFFLINE, "柜机离线", "DEVICE_FAULT", "柜机故障",
                "SALES_LOCKED", "柜机停售",
                DISPUTE, "消费争议", "SETTLEMENT_FAILED", "结算失败",
                // MA-031：待办四源（exceptions / actionItems / expiry / slot 差异）能产出的类型
                // 必须全在此表内，否则商户端待办行首渲染成「未知」。
                // 🔴 DOOR_OPEN_TOO_LONG 改「长时间未关门」把「开门超时」让给 OPEN_TIMEOUT
                //    （同一 dict_type 禁重名，由 DictLabelUniquenessIT 钉住）。
                "DOOR_OPEN_TOO_LONG", "长时间未关门", "OPEN_TIMEOUT", "开门超时",
                "RESTOCK_TASK_TIMEOUT", "补货任务超时",
                "SLOT_DISCREPANCY", "货道账实差异", "INVENTORY_MISMATCH", "库存差异")));
        map.put("device_ops_event", t("设备运维事件", m(
                "OFFLINE", "离线", "NO_SALES", "无销售", "UNLOCK", "开锁",
                "FAULT", "故障/锁机", "AISLE_AUDIT", "货道巡检", "MAINBOARD", "主板")));
        map.put("repair_fault_type", t("维修故障类型", m(
                "DOOR", "门锁", "COOLING", "制冷", "NETWORK", "网络",
                PAYMENT, "支付", "VISION", "识别", "POWER", "供电", OTHER, "其他")));
        map.put("device_fault_issue", t("报修问题类型", m(
                "DOOR_OPEN", "打不开门", "DOOR_CLOSE", "门关不上",
                "PRODUCT", "商品异常", PAYMENT, "扣款问题", OTHER, "其他")));
        map.put("line_withdraw_status", t("线长提现状态", m(
                "PENDING_REVIEW", "待审核", "APPROVED", "已通过", "PAYING", "打款中",
                "PAID", "已打款", STATUS_REJECTED, "已驳回", CabinetConstants.ORDER_STATUS_FAILED, "失败")));
        map.put("merchant_withdraw_status", t("商户提现状态", m(
                "PENDING_REVIEW", "待审核", "APPROVED", "已通过", "PAYING", "打款中",
                "PAID", "已打款", STATUS_REJECTED, "已驳回", CabinetConstants.ORDER_STATUS_FAILED, "失败")));
        map.put("supplier_status", t("供应商状态", m(CabinetConstants.PROMOTION_STATUS_ACTIVE, "启用", INACTIVE, "停用")));
        map.put("purchase_order_status", t("采购单状态", m(
                // V436（MA-134）：PENDING_APPROVAL/REJECTED 是 ProcurementService 在写的值，
                // 目录缺它们 ⇒ 自愈补不出来，admin「待审批」与 mp「待审核」两词并存
                CREATED, "待收货", "PARTIAL_RECEIVED", "部分收货", RECEIVED, "已收货",
                STATUS_CANCELLED, "已取消", "PENDING_APPROVAL", "待审批",
                STATUS_REJECTED, "已驳回")));
        map.put("warehouse_status", t("仓库状态", m(CabinetConstants.PROMOTION_STATUS_ACTIVE, "正常", INACTIVE, "停用")));
        map.put("warehouse_outbound_status", t("出库单状态", m(
                CabinetConstants.PROMOTION_STATUS_DRAFT, "待拣货", "PICKED", "已拣货", "SHIPPED", "已领出", STATUS_CANCELLED, "已取消")));
        // 铁律 80 / CB-074：分仓→柜是自提自送，口径统一成「领货 → 上柜」，不再讲「发运/在途/签收」。
        // 标签真值在 packages/shared-dict（运行时字典可覆盖），改这里要同步改 V415 的 UPDATE。
        map.put("handover_status", t("上柜交接状态", m(
                STATUS_PENDING, "未交接", "READY", "已拣待领", "IN_TRANSIT", "已领未上柜", "PARTIAL", "部分上柜", RECEIVED, "已上柜",
                STATUS_CANCELLED, "已取消")));
        map.put("in_transit_status", t("领货未结状态", m(
                "IN_TRANSIT", "未结清", RECEIVED, "已结清", STATUS_CANCELLED, "已取消", "LOST", "丢失", "DAMAGED", "破损")));
        map.put("warehouse_movement_type", t("库存变动类型", m(
                "PURCHASE_RECEIVE", "采购收货", "PURCHASE_RETURN", "采购退货",
                "MANUAL_INBOUND", "手工入库",
                "OUTBOUND", "出库", "OUTBOUND_SHIP", "领货出库", "RETURN", "退回", "ADJUSTMENT", "库存调整",
                // D28-B11：这三类流水一直在写（盘点 / 货位增减 / 撤销领货回库），字典缺 ⇒ 流水页显示「未知」。
                "STOCKTAKE", "盘点调整", "BIN_STOCK", "货位变动", "OUTBOUND_CANCEL", "领货撤销回库")));
        map.put("business_reference_type", t("业务关联类型", m(
                "PURCHASE_ORDER", "采购单", "PURCHASE_RETURN", "采购退货", "OUTBOUND_ORDER", "出库单",
                "WAREHOUSE_INBOUND", "仓库入库", "WAREHOUSE_OUTBOUND", "仓库出库",
                "REPLENISHMENT_TASK", "补货任务",
                "INVENTORY_ADJUSTMENT", "库存调整", "MANUAL", "人工操作",
                "DEMO_SEED", "演示播种期初", "OPENING_BACKFILL", "建账补记",
                "WRITE_OFF", "报损核销", "GUEST_WATER", "客情用水",
                // D28-B11：流水「关联业务」列的码——盘点单 / 货位 / 调拨进出（调拨行的 movement_type 是 BIN_STOCK，
                // 区分进出只看这里，缺了就是「未知」）。
                "STOCKTAKE", "盘点单", "BIN_INBOUND", "货位入库", "TRANSFER_OUT", "调拨出库", "TRANSFER_IN", "调拨入库")));
        map.put("replenishment_route_status", t("补货路线状态", m(
                "PLANNED", "待执行", STATUS_IN_PROGRESS, "执行中", STATUS_COMPLETED, "已完成", STATUS_CANCELLED, "已取消")));
        map.put("replenishment_task_status", t("补货任务状态", m(
                STATUS_PENDING, "待处理", STATUS_IN_PROGRESS, "进行中", STATUS_COMPLETED, "已完成", STATUS_CANCELLED, "已取消")));
        map.put("replenishment_request_status", t("补货申请状态", m(
                "SUBMITTED", "待审核", "ACCEPTED", "已接单", STATUS_REJECTED, "已驳回", STATUS_COMPLETED, "已完成")));
        map.put("inventory_lot_status", t("批次状态", m(
                "ON_SALE", "在售", "NEAR_EXPIRY", "临期", "BLOCKED", "已冻结", "DEPLETED", "已耗尽")));
        map.put("exception_severity", t("异常级别", m(
                "CRITICAL", "紧急", "HIGH", "高", "MEDIUM", "中", "LOW", "低")));
        map.put("exception_status", t("异常状态", m(
                "OPEN", "待处理", PROCESSING, "处理中", "RESOLVED", "已解决", STATUS_CLOSED, "已关闭")));
        map.put("feedback_type", t("反馈类型", m(
                "COMPLAINT", "投诉", "SUGGESTION", "建议", "BUG", "缺陷", "PRAISE", "表扬")));
        map.put("feedback_status", t("反馈状态", m(
                STATUS_PENDING, "待处理", "HANDLED", "已回复", STATUS_CLOSED, "已关闭")));
        map.put("exception_type", t("异常类型", m(
                DISPUTE, "消费争议", LOW_STOCK, "低库存", "EXPIRY", "临期商品",
                "REPLENISHMENT_REQUIRED", "待补货", DEVICE_OFFLINE, "设备离线", "DEVICE_FAULT", "设备故障",
                "DOOR_OPEN_TOO_LONG", "长时间未关门", "OPEN_TIMEOUT", "开门超时", "UPLOAD_STUCK", "录像上传滞留",
                "RECOGNITION_STUCK", "识别滞留", "RECOGNITION_TIMEOUT", "识别超时",
                "RECOGNITION_FAILED", "识别存疑需人工审核",
                "JIANGYI_CLASS_MAPPING_MISS", "将邑识别映射未命中",
                "RECOGNITION_UNAVAILABLE", "识别服务不可用", "BALANCE_INSUFFICIENT", "余额不足",
                "SETTLEMENT_FAILED", "结算失败", "SETTLEMENT_STUCK", "结算滞留",
                "INVENTORY_MISMATCH", "库存差异", "SLOT_DISCREPANCY", "货道账实差异",
                // 以下 14 类是代码真会写、但字典此前缺项的类型（G7 §7.27 FS-G7-5）：异常台按
                // exception_type 渲染标签，缺项 ⇒ 行落了后台显示不出类型。标签取各 report 站点的 title 原文。
                // 门禁 check:exception-type-dict 守住「report 字面量 ⊆ 本内置集」，新类型漏登记即红。
                "TEMP_ABNORMAL", "温度异常", "RESTOCK_SESSION_TIMEOUT", "补货会话超时",
                "RESTOCK_RECOGNITION_TIMEOUT", "补货识别超时", "RESTOCK_TASK_TIMEOUT", "补货任务超时",
                "FAILED_SESSION_HOLD_CLEAR", "会话预授权悬挂已清扫", "DISPUTE_ADJUST_MISMATCH", "争议补差账实漂移",
                "COUPON_MARKUSED_FAILED", "券核销失败", "CHARGE_PENDING_STALE", "渠道扣款回执超时",
                "UNPAID_CANCEL_NET_BLOCKED", "待支付净入账阻断取消", "SCHEDULED_TASK_STALE", "定时任务停摆",
                "SCHEDULING_POOL_CAPACITY_RISK", "调度并发超连接池上限", "XXL_JOB_WIRING_BROKEN", "XXL-JOB 执行器接线异常",
                "SPLIT_EXCEPTION", "分账待跟进", "VISION_ANOMALY", "视觉异常告警")));
        map.put("device_fault_code", t("设备故障码", m(
                "OFFLINE_TIMEOUT", "离线超时", "TEMP_ABNORMAL", "温度异常",
                "DOOR_STUCK", "门锁异常", "CAMERA_FAULT", "摄像头故障",
                "NETWORK_UNSTABLE", "网络不稳", "POWER_FAULT", "供电异常")));
        map.put("ops_exception_action", t("异常操作", m(
                "OPS_EXCEPTION_CLAIM", "领取异常", "OPS_EXCEPTION_TRANSFER", "转派异常",
                "OPS_EXCEPTION_NOTE", "添加备注", "OPS_EXCEPTION_RETRY", "重试识别/结算",
                "OPS_EXCEPTION_RETRY_SUCCESS", "重试成功", "OPS_EXCEPTION_CANCEL_SESSION", "取消会话并释放设备",
                "OPS_EXCEPTION_MANUAL_RESOLVE", "人工处置（确认商品/免单）", "OPS_EXCEPTION_RESOLVE", "标记已解决",
                "OPS_EXCEPTION_AUTO_RESOLVE", "系统自动解决", "MERCHANT_OPS_EXCEPTION_RESOLVE", "商家处理异常")));
        map.put("reconciliation_status", t("对账状态", m(
                "MATCHED", "已平账", "MISMATCH", "存在差异", STATUS_PENDING, "待处理", CabinetConstants.ORDER_STATUS_FAILED, "失败")));
        map.put("sku_status", t("商品状态", m(CabinetConstants.PROMOTION_STATUS_ACTIVE, "在售", INACTIVE, "停用", CabinetConstants.SKU_STATUS_DISABLED, "禁售")));
        map.put("order_status", t("订单状态", m(
                STATUS_PENDING, "待支付", PROCESSING, "处理中", "PAID", "已支付", STATUS_COMPLETED, "已完成",
                "DISPUTED", "争议中", "REFUNDED", "已退款", "PARTIAL_REFUNDED", "部分退款",
                CabinetConstants.ORDER_STATUS_FAILED, "处理失败", STATUS_CANCELLED, "已取消")));
        map.put("recharge_status", t("充值状态", m(
                CREATED, "已创建", STATUS_PENDING, "待支付", "PAID", "已支付", "SUCCESS", "成功",
                CabinetConstants.ORDER_STATUS_FAILED, "失败", "REFUNDED", "已退款", STATUS_CANCELLED, "已取消", STATUS_CLOSED, "已关闭")));
        map.put("risk_event_type", t("风控事件类型", m(
                "MULTI_DEVICE", "多设备异常", "HIGH_FREQUENCY", "高频开门", "DISPUTE_SPIKE", "争议激增",
                "PAYMENT_FAIL", "支付失败聚集", "BLACKLIST_HIT", "黑名单命中", "MALICIOUS_OPEN", "高频恶意开门",
                "DISPUTE_CREATED", "用户发起争议", "FREQUENT_DISPUTE", "频繁发起争议",
                "BLACKLIST_ADD", "人工加入黑名单", "BLACKLIST_AUTO", "自动加入黑名单",
                "FRAUD", "欺诈嫌疑", "ABNORMAL", "异常行为")));
        map.put("risk_severity", t("风控处置", m(
                "INFO", "提示", "WARN", "警告", "BLOCK", "已拦截", "HIGH", "高风险", "CRITICAL", "严重")));
        map.put("risk_disposition_status", t("风控处置状态", m(
                "OPEN", "待处置", "AUTO_CLEARED", "自动结清", "ACKED", "已确认")));
        map.put("settlement_batch_status", t("结算批次状态", m(
                STATUS_PENDING, "待结算", PROCESSING, "结算中", "SETTLED", "已结算", "PAID", "已支付",
                CabinetConstants.ORDER_STATUS_FAILED, "失败", "PARTIAL_FAILED", "部分失败", STATUS_COMPLETED, "已完成")));
        map.put("route_code", t("路线编码", m(
                "R01", "路线 R01", "R-DEMO-01", "演示路线 01", "R-DEMO-02", "演示路线 02", "R-DEMO-X", "演示路线 X")));
        // 组织类型（ops_org_node.node_type）：OrgService 以其为写入白名单，缺 seed 会降级放行
        map.put(SysDictService.ORG_NODE_TYPE, t("组织类型", m(
                "HQ", "总部", "REGION", "区域", "BRANCH", "分公司")));
        // 类目以运营字典为准；种子仅保证类型存在（项由运营后台维护）
        map.put("category_code", t("类目", m()));
        // MA-145：以下 10 个类型此前只在 shared-dict 基线（前端产物直接吃基线），
        // DB/Bootstrap 均缺席 → 字典管理不可见、runtime 常态性缺类型。词条与基线逐字对齐。
        map.put("balance_refund_status", t("余额退款状态", m(
                "PENDING_REVIEW", "待审核", "REFUNDED", "已退款",
                "REJECTED", "已驳回", "FAILED", "失败")));
        map.put("user_role", t("用户角色", m(
                "CONSUMER", "消费者", "OPERATOR", "运营账号", "MERCHANT", "商户", "OPS", "运营")));
        map.put("wallet_ref_type", t("钱包关联类型", m(
                "ORDER", "订单", "WITHDRAW", "提现", "OPS_ADJUST", "运营调账",
                "COMMISSION_DAILY", "日结佣金", "SPLIT", "分账", "SPLIT_PARTIAL", "分账增额",
                "SPLIT_PARTIAL_REV", "分账增额冲正", "SPLIT_REV", "分账冲正",
                "RECHARGE", "充值", "REFUND", "退款", "SEED", "演示初始")));
        map.put("wallet_ledger_type", t("钱包账务类型", m(
                "MERCHANT_CREDIT", "商户入账", "LINE_COMMISSION", "线长佣金",
                "WITHDRAW_FREEZE", "提现冻结", "WITHDRAW_RELEASE", "提现解冻",
                "WITHDRAW_PAID", "提现打款", "ADJUST", "调账", "REVERSE", "冲正",
                "SPLIT_CREDIT", "分账入账", "SPLIT_PARTIAL_CREDIT", "分账增额入账",
                "SPLIT_REVERSE", "分账冲正", "SPLIT_PARTIAL_REVERSE", "分账增额冲正")));
        map.put("consistency_diag_code", t("一致性诊断码", m(
                "SALE_OK", "应有 SALE 扣库流水", "MISSING_SALE", "缺少 SALE 扣库流水",
                "INVENTORY_FLAG", "未标记已扣库", "REFUND_OR_KEPT", "应有退款/留货库存流水",
                "MISSING", "缺少退款/留货库存流水", "LINKED", "应有关联订单")));
        map.put("merchant_settlement_bill_status", t("商户结算单状态", m(
                "PENDING", "待确认", "CONFIRMED", "已确认")));
        map.put("close_sheet_status", t("月结单状态", m(
                "DRAFT", "未审批", "APPROVED", "已审批")));
        map.put("close_sheet_opening_source", t("月结期初来源", m(
                "PERIOD_CLOSE", "上期实盘结转", "BOOK", "账面结转")));
        map.put("close_sheet_disposition", t("月结差异处置", m(
                "PENDING", "待认定", "CLAIM", "已索赔",
                "NORMAL_LOSS", "正常损耗", "SURPLUS", "盘盈待查")));
        map.put("expiry_urgency", t("临期紧急度", m(
                "EXPIRED", "已过期", "URGENT", "紧急（≤7天）",
                "SOON", "临近（≤30天）", "NORMAL", "正常")));
        return map;
    }

    private static SeedType t(String name, Map<String, String> items) {
        return new SeedType(name, items);
    }

    private static Map<String, String> m(String... kv) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            map.put(kv[i], kv[i + 1]);
        }
        return map;
    }
}
