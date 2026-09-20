package com.aicabinet.trade.service;

import com.aicabinet.common.dto.OpsBrandDto;
import com.aicabinet.common.dto.SystemConfigDto;
import com.aicabinet.common.dto.UpsertSystemConfigRequest;
import com.aicabinet.trade.config.AlipayProperties;
import com.aicabinet.trade.config.PayScoreProperties;
import com.aicabinet.trade.config.QrProperties;
import com.aicabinet.trade.config.SecurityProperties;
import com.aicabinet.trade.config.WeChatPayProperties;
import com.aicabinet.trade.config.WeChatWebProperties;
import com.aicabinet.trade.config.WeChatMiniAppProperties;
import com.aicabinet.trade.domain.SystemConfig;
import com.aicabinet.trade.domain.SystemConfigHistory;
import com.aicabinet.trade.mapper.SystemConfigHistoryMapper;
import com.aicabinet.trade.mapper.SystemConfigMapper;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class SystemConfigService {
    private static final String FALSE = "false";


    public static final String CONSUMER_SERVICE_PHONE = "consumer.service_phone";
    public static final String OPS_SUPPORT_EMAIL = "ops.support_email";
    public static final String SETTLEMENT_MIN_CONFIDENCE = "settlement.min_confidence";
    public static final String DISPUTE_AUTO_OPEN = "dispute.auto_open";
    public static final String REFUND_DEFAULT_POLICY = "refund.default_policy";
    public static final String REFUND_SELF_MAX_HOURS = "refund.self.max_hours";
    public static final String REFUND_SELF_MAX_CENTS = "refund.self.max_cents";
    public static final String REFUND_SELF_MAX_DAILY = "refund.self.max_daily";
    /** 单次充值上限（分）；默认 ¥5000；0=不限制。 */
    public static final String RECHARGE_MAX_CENTS = "recharge.max_cents";
    /**
     * 充值赠送比例（百分比，充 100 送 10 即填 10）；默认 0 = 关闭（不赠送）。
     *
     * <p>大于 0 时，充值到账按 {@code 金额 × 比例 / 100} 向下取整赠送等额余额，
     * 落一条 {@code RECHARGE_BONUS} 流水（幂等键 {@code recharge-bonus:&lt;orderId&gt;}，
     * 故与主充值一样可安全重试，不会重复赠送）。
     */
    public static final String RECHARGE_BONUS_PERCENT = "recharge.bonus.percent";
    /** 单次余额退款申请上限（分）；默认 ¥5000；0=不限制。 */
    public static final String BALANCE_REFUND_MAX_CENTS = "balance.refund.max_cents";
    /**
     * 余额退款自动审批上限（分）；默认 0 = 关闭（全部转人工审核）。
     * 大于 0 时，申请金额不超过该阈值的退款申请提交后立即由系统账号审批并执行原路退款，
     * 不进入人工审批流（也不创建审批实例）。
     */
    public static final String REFUND_AUTO_APPROVE_MAX_CENTS = "refund.auto_approve.max_cents";
    public static final String REFUND_SELF_PARTIAL_ENABLED = "refund.self.partial_enabled";
    /** 待支付订单超时自动关单小时数, 0=关闭自动关单. */
    public static final String UNPAID_AUTO_CANCEL_HOURS = "order.unpaid.auto_cancel_hours";
    /** 超时关单时是否自动拉黑用户. */
    public static final String UNPAID_AUTO_BLACKLIST = "order.unpaid.auto_blacklist";
    /** 待支付充值单超时自动取消分钟数, 0=关闭. */
    public static final String RECHARGE_AUTO_CANCEL_MINUTES = "recharge.pending.auto_cancel_minutes";
    /** 设备离线超过该分钟数后自动锁机停售, 0=不自动锁机. */
    public static final String DEVICE_OFFLINE_AUTO_LOCK_MINUTES = "device.offline.auto_sales_lock_minutes";
    /** 人工/策略解锁后，离线自动锁机宽限分钟数, 0=无宽限. */
    public static final String DEVICE_OFFLINE_MANUAL_UNLOCK_GRACE_MINUTES =
            "device.offline.manual_unlock_grace_minutes";
    public static final String DEVICE_STABLE_ONLINE_AUTO_UNLOCK_ENABLED = "device.offline.auto_unlock_enabled";
    public static final String DEVICE_STABLE_ONLINE_AUTO_UNLOCK_MINUTES = "device.offline.auto_unlock_stable_minutes";
    /**
     * OTA 升级进度上报开关（O2）；默认 false = 关闭。
     *
     * <p>关闭时设备侧的进度上报**不落库**（返回空），
     * 即 {@code ota_device_report} 保持与接入前一致的「零写入」状态（fail-closed）。
     */
    public static final String OTA_PROGRESS_ENABLED = "ota.progress.enabled";

    // ── F1 动态定价 · 时段折扣 ───────────────────────────────────────────────
    //    这四个键由 MerchantSkuPricingService.loadPromoPolicy() 一次读成快照，
    //    调用方（商品目录 / 结算）在循环外读一次、循环内复用，避免 N×DB。
    /**
     * 时段折扣总开关；默认 false = 关闭 ⇒ 价格与接入前**逐字节一致**（fail-closed）。
     *
     * <p>开启后，位于 {@code start_hour}（含）至 {@code end_hour}（不含）之间的成交按
     * {@code discount_percent} 打折；跨零点窗口（start &gt; end）按「或」判定。
     * 折扣**不叠加于临期价**：命中临期批次时仍按人工设定的临期价成交。
     */
    public static final String PRICING_TIME_WINDOW_ENABLED = "pricing.time_window.enabled";
    /** 时段折扣起始小时（0-23，含）。start == end 视为空窗口 ⇒ 不生效。 */
    public static final String PRICING_TIME_WINDOW_START_HOUR = "pricing.time_window.start_hour";
    /** 时段折扣结束小时（0-23，不含）。 */
    public static final String PRICING_TIME_WINDOW_END_HOUR = "pricing.time_window.end_hour";
    /**
     * 时段折扣比例（1-99）。0 与 ≥100 一律视为「不生效」：100% 折扣不是合法折扣，
     * 宁可原价也不要白送（fail-closed）。
     */
    public static final String PRICING_TIME_WINDOW_DISCOUNT_PERCENT =
            "pricing.time_window.discount_percent";

    // ── F1 动态定价 · 库存清仓折扣 ───────────────────────────────────────────
    //    与时段折扣共用同一份快照（PricingPromoPolicy），两个维度各自独立归一化：
    //    「时段配错」不会连坐「清仓」，反之亦然。
    /**
     * 库存清仓折扣总开关；默认 false = 关闭 ⇒ 价格与接入前**逐字节一致**（fail-closed）。
     *
     * <p>语义（可验证）：某设备上某 SKU 的**可售批次中最早入库的那一批**，
     * 其入库（{@code device_sku_lot.created_at}）至今的天数 ≥ {@code stock_age_days}
     * 时，判定为**滞销库存**，该 SKU 按 {@code discount_percent} 清仓。
     * 即「清仓 = 清滞销」：与「临期」（按 {@code expiry_date}）是两个不同维度，
     * 临期批次本身不会被算成清仓。
     *
     * <p>叠加规则：清仓**不叠加于临期价**（临期价是人工绝对促销价，优先，同 C 端可预期性）；
     * 清仓与时段折扣**取更深者**，不做折上折 —— 否则商户无法预知最终售价。
     */
    public static final String PRICING_CLEARANCE_ENABLED = "pricing.clearance.enabled";
    /** 滞销判定天数阈值（&gt;0 生效）；最早可售批次入库满该天数即视为滞销。0 或负数 = 不生效。 */
    public static final String PRICING_CLEARANCE_STOCK_AGE_DAYS = "pricing.clearance.stock_age_days";
    /**
     * 清仓折扣比例（1-99）。0 与 ≥100 一律视为「不生效」（fail-closed，绝不白送）。
     */
    public static final String PRICING_CLEARANCE_DISCOUNT_PERCENT =
            "pricing.clearance.discount_percent";

    /**
     * {@link #DEVICE_STABLE_ONLINE_AUTO_UNLOCK_MINUTES} 的**兜底默认值**（分钟）。
     *
     * <p>只在配置行缺失时生效；运行时以运营台「系统配置」里的值为准（可改）。
     * 2026-09-17 产品定稿：15 → 5 分钟。已有配置行的值由 Flyway `V276` **一次性**迁移，
     * 刻意不写成启动期「值等于 15 就覆盖」——否则运营台把它改回 15 会被反复刷掉，
     * 配置项会退化成「假可配置」。</p>
     */
    public static final int DEFAULT_DEVICE_STABLE_ONLINE_AUTO_UNLOCK_MINUTES = 5;
    /**
     * 柜内温度高于该值（℃）时上报 TEMP_ABNORMAL；0 或负数关闭。
     * 对应告警规则页「温度告警上限」，对齐联调文档「温度&gt;8℃」。
     */
    public static final String DEVICE_TEMP_ALERT_MAX_C = "device.temp.alert_max_c";
    /** 柜机离线/停售即时通知冷却分钟数（同柜同类型），0=不冷却. */
    public static final String MERCHANT_INCIDENT_NOTIFY_COOLDOWN_MINUTES =
            "merchant.notify.incident_cooldown_minutes";
    public static final String DISPUTE_SLA_HOURS = "dispute.sla.hours";
    public static final String DISPUTE_SLA_REMINDER_HOURS = "dispute.sla.reminder_hours";
    public static final String DISPUTE_SLA_WEBHOOK = "dispute.sla.webhook";
    public static final String OPS_ALERT_FEISHU_WEBHOOK = "ops.alert.feishu_webhook";
    public static final String OPS_ALERT_FEISHU_SIGN_SECRET = "ops.alert.feishu_sign_secret";
    public static final String OPS_ALERT_DINGTALK_WEBHOOK = "ops.alert.dingtalk_webhook";
    public static final String OPS_ALERT_WECOM_WEBHOOK = "ops.alert.wecom_webhook";
    public static final String OPS_ALERT_WEBHOOK = "ops.alert.webhook";
    /**
     * P0 告警升级链（O3）：聊天渠道没送达时，改用短信/电话叫值班人。总开关默认关（零行为变化）。
     * ⚠️ 键名必须保持 `ops.alert.` 前缀 —— `check-ops-alert-channels` 的 R5/R6 按该前缀
     * 自动要求「seed + 运营台可见」，换前缀就会绕过那两道守卫。
     */
    public static final String OPS_ALERT_ESCALATION_ENABLED = "ops.alert.escalation_enabled";
    /** 需要升级的告警类型白名单（逗号分隔），空 = 全类型都升级。 */
    public static final String OPS_ALERT_ESCALATION_TYPES = "ops.alert.escalation_types";
    /** 值班表 JSON（见 {@link OnCallRoster}）；留空 ⇒ 无值班人 ⇒ 不升级（fail-closed）。 */
    public static final String OPS_ALERT_ONCALL_ROSTER = "ops.alert.oncall_roster";
    /** 升级链一级：短信网关 Webhook（留空则跳过该级）。 */
    public static final String OPS_ALERT_ESCALATION_SMS_WEBHOOK = "ops.alert.escalation_sms_webhook";
    /** 升级链二级：电话外呼网关 Webhook（留空则跳过该级）。 */
    public static final String OPS_ALERT_ESCALATION_PHONE_WEBHOOK = "ops.alert.escalation_phone_webhook";
    public static final String OPS_SCAN_DOOR_OPEN_MINUTES = "ops.scan.door_open_minutes";
    public static final String OPS_SCAN_UPLOAD_STUCK_MINUTES = "ops.scan.upload_stuck_minutes";
    public static final String OPS_SCAN_RECOGNITION_STUCK_MINUTES = "ops.scan.recognition_stuck_minutes";
    public static final String OPS_SCAN_SETTLEMENT_STUCK_MINUTES = "ops.scan.settlement_stuck_minutes";
    /** 运营后台品牌标题（登录页主标题 / 浏览器标题前缀）。 */
    public static final String OPS_BRAND_TITLE = "ops.brand.title";
    /** 运营后台副标题（登录页副文案）。 */
    public static final String OPS_BRAND_SUBTITLE = "ops.brand.subtitle";
    /** 侧栏展开时品牌文案。 */
    public static final String OPS_BRAND_SIDEBAR_TITLE = "ops.brand.sidebar_title";
    /** 品牌 Logo URL（空则用标题首字占位）。 */
    public static final String OPS_BRAND_LOGO_URL = "ops.brand.logo_url";
    /** 消费者开门预授权冻结金额(分), 优先于配置文件, 柜机押金可覆盖. */
    public static final String CHECKOUT_PREAUTH_CENTS = "checkout.preauth_cents";
    /** 纯视觉柜（会话无重力字段）空车是否自动零结；默认 false 进争议。 */
    public static final String SETTLEMENT_EMPTY_AUTO_NO_GRAVITY =
            "settlement.empty_auto_complete_no_gravity";
    /** 结算识别方式: VISION=纯视觉；VISION_GRAVITY=视觉+重力融合。 */
    public static final String SETTLEMENT_RECOGNITION_MODE = "settlement.recognition_mode";
    public static final String RECOGNITION_MODE_VISION = "VISION";
    public static final String RECOGNITION_MODE_VISION_GRAVITY = "VISION_GRAVITY";
    /**
     * 商户端完成补货任务是否必须上传现场凭证照片。
     * true=至少 1 张；false=可跳过（仍允许上传，便于抽检）。
     */
    public static final String REPLENISHMENT_COMPLETE_REQUIRE_EVIDENCE =
            "replenishment.complete.require_evidence";
    /**
     * 柜机已配置坐标时，签到是否必须带定位。
     * true=缺定位拒签；false=允许空定位（仍可带坐标并受距离校验）。
     */
    public static final String REPLENISHMENT_CHECK_IN_REQUIRE_LOCATION =
            "replenishment.check_in.require_location";
    /**
     * 签到距柜机最大允许距离（米）。≤0 表示关闭距离校验。
     */
    public static final String REPLENISHMENT_CHECK_IN_MAX_DISTANCE_M =
            "replenishment.check_in.max_distance_m";
    /**
     * 商户端完成补货是否必须先补货开门。
     * true=须有补货开门会话；false=可跳过（运营后台代完成本就不拦）。
     */
    public static final String REPLENISHMENT_COMPLETE_REQUIRE_DOOR =
            "replenishment.complete.require_door";

    // ── 可观测性（P0-6）：链路追踪的**运行期**开关 ──────────────────────────
    /**
     * 链路追踪总开关，**运行期生效**（运营台改完即生效，不用重启）。
     *
     * <p>它决定本服务**要不要把 span 发出去**。而 Loki / Tempo **容器在不在跑**由
     * {@code infra/observability.ps1} 管 —— 系统配置存在 PG 里，它拉不起容器，两件事别混。</p>
     *
     * <p>三态语义（seed 为<b>空串</b>＝未表态）：</p>
     * <ul>
     *   <li>{@code true} ⇒ 强制开（需要端点，见 {@link #OPS_OBSERVABILITY_OTLP_ENDPOINT}）</li>
     *   <li>{@code false} ⇒ 强制关，**压过** Spring 属性 {@code tracing.otlp.endpoint}</li>
     *   <li>空 ⇒ 跟随属性：属性有端点就开、没有就关（保证「纯 env 部署」行为不变）</li>
     * </ul>
     */
    public static final String OPS_OBSERVABILITY_TRACING_ENABLED = "ops.observability.tracing_enabled";
    /**
     * OTLP HTTP 导出端点，如 {@code http://tempo:4318/v1/traces}（容器间走服务名）。
     * 留空则回退 Spring 属性 {@code tracing.otlp.endpoint}。
     */
    public static final String OPS_OBSERVABILITY_OTLP_ENDPOINT = "ops.observability.otlp_endpoint";
    /** 运营台是否显示「演示数据」横幅。⚠️ 原先只有代码里读、没 seed ⇒ 运营台看不到（本次补齐）。 */
    public static final String OPS_DEMO_DATA_BANNER = "ops.demo_data_banner";
    /** 风控自动处置：信息类工单多少小时后自动清除。⚠️ 原先只有代码里读、没 seed（本次补齐）。 */
    public static final String RISK_AUTO_CLEAR_INFO_HOURS = "risk.auto_clear_info_hours";
    /** 风控自动处置：待确认工单超多少小时告警。⚠️ 原先只有代码里读、没 seed（本次补齐）。 */
    public static final String RISK_AUTO_ACK_WARN_HOURS = "risk.auto_ack_warn_hours";

    // ── 扩展功能域（路线图功能的总闸，2026-09-20 第二十七轮）────────────────────
    // 🔴 语义：每个开关都控制一个**已接入的真实行为**（入口可见性），默认 false = 维持现状（零行为变化）。
    //    ⚠️ 门禁 R2 会把「注册了却没有 Java 消费者的键」判成**死开关**，故这些键一律在
    //    consumerPublicConfig() / merchantPublicConfig() 里被读取并下发给客户端；
    //    新增同族开关时**必须同时接入一个真实读取点**，否则门禁红——这是刻意的。
    /** 消费端：订单关键字搜索（订单列表顶部搜索框）。 */
    public static final String CONSUMER_ORDER_SEARCH_ENABLED = "consumer.order_search.enabled";
    /** 消费端：券包入口前置（首页显示券包入口；关闭时仅「我的」页有）。 */
    public static final String CONSUMER_COUPON_ENTRY_ENABLED = "consumer.coupon_entry.enabled";
    /** 商户端：经营分析图表（趋势/构成图；关闭时维持纯数字与列表）。 */
    public static final String MERCHANT_CHARTS_ENABLED = "merchant.charts.enabled";
    /** 消费端：本柜商品详情（商品卡片可点开详情弹层；关闭时仅展示卡片摘要，与接入前一致）。 */
    public static final String CONSUMER_PRODUCT_DETAIL_ENABLED = "consumer.product_detail.enabled";
    /**
     * 消费端：首页广告位（S1，见 {@code docs/AD_MONETIZATION_DESIGN.md}）。
     *
     * <p><b>关闭（默认）＝ 首页不渲染广告位</b>，与接入前逐字节一致（fail-closed）。
     * 开启后按下列优先级渲染：
     * <ol>
     *   <li>该柜有生效中的投放计划 ⇒ 渲染真实素材（曝光/完播/点击照常上报）；</li>
     *   <li>没有投放内容 ⇒ 渲染**占位图**（「广告位招租」），且**不上报任何事件**
     *       —— 占位不是广告，不该产生计量数据，更不该进计费。</li>
     * </ol>
     *
     * <p>⚠️ 本开关只负责「位置可见性」。广告主 / 订单 / CPM 计费与结算属 F4 后续切片，
     * 不在本开关语义内（现在的默认关＝只站位，不产生任何计费行为）。
     */
    public static final String CONSUMER_AD_BANNER_ENABLED = "consumer.ad_banner.enabled";

    // ── F1 动态定价 · 策略版本与审计 ─────────────────────────────────────────
    /**
     * 系统配置变更审计开关；默认 false = 关闭 ⇒ 写路径**零留痕**，行为与接入前
     * 逐字节一致（fail-closed）。
     *
     * <p>开启后，每次配置写操作（upsert / delete）留**两份**记录：
     * <ol>
     *   <li>{@code system_config_history} —— 结构化版本（old_value / new_value / operator / 时间），
     *       某键的全部历史行按时间倒序即版本序列，可回滚到任一条的 oldValue；</li>
     *   <li>{@code admin_audit_log}（{@code action=CONFIG_UPSERT|CONFIG_DELETE}）—— 统一审计视图。</li>
     * </ol>
     *
     * <p>刻意<b>只读侧不受开关影响</b>：关掉开关只是「不再新增版本」，不该让已记录的历史不可见
     * ——否则运营为了查历史就得先开写权限。seed 初始化（{@code upsertIfAbsent}）不经
     * {@code doUpsert}，天然不产生历史（初始化不是「变更」）。
     */
    public static final String OPS_CONFIG_AUDIT_ENABLED = "ops.config.audit.enabled";

    /** 配置审计的 {@code admin_audit_log.target_type} 取值（运营台审计页据此过滤）。 */
    public static final String CONFIG_AUDIT_TARGET_TYPE = "system_config";
    public static final String CONFIG_ACTION_UPSERT = "CONFIG_UPSERT";
    public static final String CONFIG_ACTION_DELETE = "CONFIG_DELETE";
    /**
     * 无鉴权上下文（定时任务 / 内部调用）的操作人。
     * 与 V221 预置的「系统」账号一致，也是 {@code BalanceRefundService.AUTO_REVIEWER_ID} 的取值。
     */
    public static final long SYSTEM_OPERATOR_ID = 0L;

    private final SystemConfigMapper repository;
    private final SystemConfigHistoryMapper historyRepository;
    private final AdminAuditService auditService;
    private final SecurityProperties securityProperties;
    private final AlipayProperties alipayProperties;
    private final WeChatPayProperties weChatPayProperties;
    private final PayScoreProperties payScoreProperties;
    private final WeChatWebProperties weChatWebProperties;
    private final WeChatMiniAppProperties weChatMiniAppProperties;
    private final QrProperties qrProperties;
    private final DistributedLockService distributedLockService;
    private final SystemConfigService self;

    public SystemConfigService(SystemConfigMapper repository,
                               SystemConfigHistoryMapper historyRepository,
                               AdminAuditService auditService,
                               SecurityProperties securityProperties,
                               AlipayProperties alipayProperties,
                               WeChatPayProperties weChatPayProperties,
                               PayScoreProperties payScoreProperties,
                               WeChatWebProperties weChatWebProperties,
                               WeChatMiniAppProperties weChatMiniAppProperties,
                               QrProperties qrProperties,
                               DistributedLockService distributedLockService,
                               @Lazy SystemConfigService self) {
        this.repository = repository;
        this.historyRepository = historyRepository;
        this.auditService = auditService;
        this.securityProperties = securityProperties;
        this.alipayProperties = alipayProperties;
        this.weChatPayProperties = weChatPayProperties;
        this.payScoreProperties = payScoreProperties;
        this.weChatWebProperties = weChatWebProperties;
        this.weChatMiniAppProperties = weChatMiniAppProperties;
        this.qrProperties = qrProperties;
        this.distributedLockService = distributedLockService;
        this.self = self;
    }

    @Transactional(readOnly = true)
    public String getValue(String key, String defaultValue) {
        return repository.findById(key)
                .map(SystemConfig::getConfigValue)
                .filter(v -> !v.isBlank())
                .orElse(defaultValue);
    }

    /** 是否在结算中融合重力（仅 VISION_GRAVITY）。默认 VISION=否。 */
    @Transactional(readOnly = true)
    public boolean usesGravityFusion() {
        String mode = self.getValue(SETTLEMENT_RECOGNITION_MODE, RECOGNITION_MODE_VISION);
        return RECOGNITION_MODE_VISION_GRAVITY.equalsIgnoreCase(mode.trim());
    }

    @Transactional(readOnly = true)
    public int getInt(String key, int defaultValue) {
        String raw = self.getValue(key, null);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    @Transactional(readOnly = true)
    public boolean getBoolean(String key, boolean defaultValue) {
        String raw = self.getValue(key, null);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        return "true".equalsIgnoreCase(raw.trim()) || "1".equals(raw.trim());
    }

    @Transactional(readOnly = true)
    public double getDouble(String key, double defaultValue) {
        String raw = self.getValue(key, null);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    @Transactional(readOnly = true)
    public Map<String, String> consumerPublicConfig() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("servicePhone", self.getValue(CONSUMER_SERVICE_PHONE, "400-888-0018"));
        map.put("supportEmail", self.getValue(OPS_SUPPORT_EMAIL, "ops@aicabinet.local"));
        boolean wechatSubscribeOk = weChatMiniAppProperties.isConfigured()
                && weChatMiniAppProperties.resolveConsumerTemplateId() != null
                && !weChatMiniAppProperties.resolveConsumerTemplateId().isBlank();
        map.put("wechatSubscribeEnabled", String.valueOf(wechatSubscribeOk));
        map.put("wechatSubscribeTemplateId",
                wechatSubscribeOk ? weChatMiniAppProperties.resolveConsumerTemplateId() : "");
        map.put("mockEnabled", String.valueOf(securityProperties.mockEnabled()));
        map.put("rechargeMaxCents",
                String.valueOf(self.getInt(RECHARGE_MAX_CENTS, 500_000)));
        map.put("balanceRefundMaxCents",
                String.valueOf(self.getInt(BALANCE_REFUND_MAX_CENTS, 500_000)));
        // 沙箱: 已配置支付宝密钥, 或 mock 模式下允许走 mock 支付宝预下单
        boolean alipayOk = alipayProperties.isConfigured()
                || (securityProperties.mockEnabled() && alipayProperties.enabled());
        map.put("alipayRechargeEnabled", String.valueOf(alipayOk));
        // 微信: 已完整配置走 live; 未配置但开启 mock 时允许微信模拟预下单+确认
        boolean wechatOk = weChatPayProperties.isConfigured() || securityProperties.mockEnabled();
        map.put("wechatRechargeEnabled", String.valueOf(wechatOk));
        map.put("wechatPayLive", String.valueOf(weChatPayProperties.isConfigured()));
        map.put("alipayPayLive", String.valueOf(alipayProperties.isConfigured()));
        // 支付分开门: 显式开启或 mock 时前端展示一键开通
        map.put("payScoreSignEnabled",
                String.valueOf(payScoreProperties.enabled() || securityProperties.mockEnabled()));
        // refund.default_policy 是设备级退款策略（RefundPolicyService 后端自用），不对 C 端下发
        map.put("paymentModeHint", securityProperties.mockEnabled()
                ? "模拟支付(无真实进件), 充值可一键到账, 订单退款退回余额"
                : "真实/沙箱支付");
        // H5 微信网页授权：已配置公众号密钥时下发真实 OAuth 跳转 URL；dev mock 下前端直连 wx-h5-login
        boolean webOauthConfigured = weChatWebProperties.isConfigured();
        boolean webOauthOk = webOauthConfigured
                || (securityProperties.mockEnabled() && weChatWebProperties.enabled());
        map.put("wechatH5OauthEnabled", String.valueOf(webOauthOk));
        if (webOauthConfigured) {
            map.put("wechatH5OauthUrl", buildWechatH5OauthUrl());
        }
        map.put("preauthCents", self.getValue(CHECKOUT_PREAUTH_CENTS,
                String.valueOf(com.aicabinet.common.constants.CabinetConstants.MIN_BALANCE_CENTS)));
        // 扩展功能域开关：关闭时客户端不显示对应入口（默认关 = 与接入前完全一致）
        map.put("orderSearchEnabled",
                String.valueOf(self.getBoolean(CONSUMER_ORDER_SEARCH_ENABLED, false)));
        map.put("couponEntryEnabled",
                String.valueOf(self.getBoolean(CONSUMER_COUPON_ENTRY_ENABLED, false)));
        map.put("productDetailEnabled",
                String.valueOf(self.getBoolean(CONSUMER_PRODUCT_DETAIL_ENABLED, false)));
        // 首页广告位（S1）：默认关 ⇒ 渲染分支与接入前一致
        map.put("adBannerEnabled",
                String.valueOf(self.getBoolean(CONSUMER_AD_BANNER_ENABLED, false)));
        return map;
    }

    /**
     * 商户端配置下发（对称于 {@link #consumerPublicConfig()}）。
     * <p>刻意只下发**非敏感的 UI 开关**（无金额、无凭据）；商户端请求本身带鉴权，
     * 但该端点与 C 端配置同样放在匿名 {@code /api/v2/public/**} 下以便首屏取用。</p>
     */
    @Transactional(readOnly = true)
    public Map<String, String> merchantPublicConfig() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("chartsEnabled",
                String.valueOf(self.getBoolean(MERCHANT_CHARTS_ENABLED, false)));
        return map;
    }

    /** 运营后台品牌（登录页无需鉴权）。 */
    @Transactional(readOnly = true)
    public OpsBrandDto opsBrandPublic() {
        String title = self.getValue(OPS_BRAND_TITLE, "AI开门柜");
        String subtitle = self.getValue(OPS_BRAND_SUBTITLE, "运营管理系统");
        String sidebar = self.getValue(OPS_BRAND_SIDEBAR_TITLE, title + "运营");
        String logoUrl = self.getValue(OPS_BRAND_LOGO_URL, "");
        return new OpsBrandDto(title, subtitle, sidebar, logoUrl == null ? "" : logoUrl);
    }

    private String buildWechatH5OauthUrl() {
        String redirect = qrProperties.normalizedConsumerH5Base();
        try {
            return "https://open.weixin.qq.com/connect/oauth2/authorize"
                    + "?appid=" + java.net.URLEncoder.encode(weChatWebProperties.appId(),
                            java.nio.charset.StandardCharsets.UTF_8)
                    + "&redirect_uri=" + java.net.URLEncoder.encode(redirect,
                            java.nio.charset.StandardCharsets.UTF_8)
                    + "&response_type=code&scope=snsapi_base&state=wechat#wechat_redirect";
        } catch (Exception e) {
            return "";
        }
    }

    @Transactional
    public List<SystemConfigDto> listAll() {
        ensureDefaults();
        List<SystemConfigDto> out = new ArrayList<>();
        for (SystemConfig config : repository.findAll()) {
            out.add(toDto(config));
        }
        out.sort(Comparator.comparing(SystemConfigDto::configKey));
        return out;
    }

    @Transactional
    public SystemConfigDto upsert(UpsertSystemConfigRequest request) {
        return self.upsert(request.configKey(), request.configValue(), request.description(), null);
    }

    /** 带操作人的重载：运营台写入走这条，审计/版本记录里才有「谁改的」。 */
    @Transactional
    public SystemConfigDto upsert(UpsertSystemConfigRequest request, Long operatorId) {
        return self.upsert(request.configKey(), request.configValue(), request.description(), operatorId);
    }

    @Transactional
    public SystemConfigDto upsert(String key, String value, String description) {
        return self.upsert(key, value, description, null);
    }

    @Transactional
    public SystemConfigDto upsert(String key, String value, String description, Long operatorId) {
        return runWithConfigLock(key, () -> doUpsert(key, value, description, operatorId));
    }

    private SystemConfigDto doUpsert(String key, String value, String description, Long operatorId) {
        java.util.Optional<SystemConfig> existing = repository.findByIdForUpdate(key);
        // 覆盖前先留旧值：这才是「版本」的全部信息量（写入后旧值即不可追）。
        String oldValue = existing.map(SystemConfig::getConfigValue).orElse(null);
        boolean created = existing.isEmpty();
        SystemConfig config = existing.orElseGet(SystemConfig::new);
        config.setConfigKey(key);
        config.setConfigValue(value == null ? "" : value);
        if (description != null && !description.isBlank()) {
            config.setDescription(description);
        } else if (config.getDescription() == null) {
            config.setDescription("");
        }
        config.setUpdatedAt(Instant.now());
        SystemConfigDto saved = toDto(repository.save(config));
        recordChangeIfEnabled(key, created ? null : oldValue, saved.configValue(),
                operatorId, CONFIG_ACTION_UPSERT);
        return saved;
    }

    @Transactional
    public void delete(String configKey) {
        delete(configKey, null);
    }

    /** 带操作人的删除重载；删除同样留一条历史（newValue 为 null）。 */
    @Transactional
    public void delete(String configKey, Long operatorId) {
        if (configKey == null || configKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "配置键不能为空");
        }
        runWithConfigLock(configKey, () -> {
            SystemConfig existing = repository.findByIdForUpdate(configKey)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "参数不存在"));
            repository.deleteById(configKey);
            recordChangeIfEnabled(configKey, existing.getConfigValue(), null,
                    operatorId, CONFIG_ACTION_DELETE);
            return null;
        });
    }

    /**
     * 配置写操作的留痕（F1 策略版本与审计）：受 {@link #OPS_CONFIG_AUDIT_ENABLED} 控制。
     *
     * <p><b>关时零写入</b>（默认）——不查表、不落行、不写审计，与接入前完全一致。
     * 每次变更落两处：结构化的 {@code system_config_history}（版本）与
     * {@code admin_audit_log}（统一审计视图，运营台审计页按 target_type 可过滤）。
     *
     * <p>在分布式锁内执行（调用方持锁），故「读开关 → 写历史」与真正的配置写入同锁同事务，
     * 不会出现「值改了但历史没记」的中间态。
     */
    private void recordChangeIfEnabled(String key, String oldValue, String newValue,
                                      Long operatorId, String action) {
        if (!self.getBoolean(OPS_CONFIG_AUDIT_ENABLED, false)) {
            return;
        }
        long op = operatorId == null ? SYSTEM_OPERATOR_ID : operatorId;
        SystemConfigHistory history = new SystemConfigHistory();
        history.setConfigKey(key);
        history.setOldValue(oldValue);
        history.setNewValue(newValue);
        history.setOperatorId(op);
        history.setCreatedAt(Instant.now());
        historyRepository.save(history);
        auditService.appendLog(op, action, CONFIG_AUDIT_TARGET_TYPE, key,
                describeChange(oldValue, newValue));
    }

    /** 变更摘要（仅用于审计 detail，会被 appendLog 截到 512 字符）。 */
    static String describeChange(String oldValue, String newValue) {
        String before = oldValue == null ? "（未配置）" : oldValue;
        String after = newValue == null ? "（已删除）" : newValue;
        return before + " → " + after;
    }

    static String systemConfigLockKey(String configKey) {
        return "sys:config:" + configKey.trim();
    }

    private <T> T runWithConfigLock(String configKey, java.util.function.Supplier<T> action) {
        String key = systemConfigLockKey(configKey);
        if (!distributedLockService.tryLock(key, 60, 5)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "系统配置处理中，请稍后重试");
        }
        try {
            return action.get();
        } finally {
            distributedLockService.unlock(key);
        }
    }

    private void ensureDefaults() {
        upsertIfAbsent(CONSUMER_SERVICE_PHONE, "400-888-0018", "C端客服电话");
        upsertIfAbsent(OPS_SUPPORT_EMAIL, "ops@aicabinet.local",
                "运营支持邮箱（下发 C 端公开配置 supportEmail）");
        upsertIfAbsent(SETTLEMENT_MIN_CONFIDENCE, "0.72",
                "自动结算最低整体识别置信度（低于则触发人工审；单品另看 SKU 扣款阈值）");
        upsertIfAbsent(SETTLEMENT_RECOGNITION_MODE, RECOGNITION_MODE_VISION,
                "结算识别方式: VISION=纯视觉(忽略重力), VISION_GRAVITY=视觉+重力融合");
        upsertIfAbsent(SETTLEMENT_EMPTY_AUTO_NO_GRAVITY, FALSE,
                "纯视觉柜空车是否自动零结（无重力字段时）；默认 false 进争议");
        upsertIfAbsent(DISPUTE_AUTO_OPEN, "true",
                "识别低置信是否自动开争议工单；false 时仍结算但记日志（空车/超时等安全路径不受影响）");
        upsertIfAbsent(REFUND_DEFAULT_POLICY, "AUTO_REFUND",
                "全局默认退款策略: AUTO_REFUND=自助退款, DISPUTE_ONLY=仅申诉");
        upsertIfAbsent(REFUND_SELF_MAX_HOURS, "24", "消费者自助退款时限（下单后小时数）");
        upsertIfAbsent(REFUND_SELF_MAX_CENTS, "5000", "消费者自助单笔退款上限（分），0=不限制");
        upsertIfAbsent(REFUND_SELF_MAX_DAILY, "3", "消费者每日自助退款次数上限，0=不限制");
        upsertIfAbsent(REFUND_SELF_PARTIAL_ENABLED, "true", "是否允许消费者按行自助部分退");
        upsertIfAbsent("debt.block_open_on_pending", "true", "有待支付订单时是否禁止开门");
        upsertIfAbsent(UNPAID_AUTO_CANCEL_HOURS, "48", "待支付订单超时自动关单小时数, 0=关闭");
        upsertIfAbsent(UNPAID_AUTO_BLACKLIST, FALSE, "待支付超时关单时是否自动拉黑用户");
        upsertIfAbsent(RECHARGE_AUTO_CANCEL_MINUTES, "30", "待支付充值单超时自动取消分钟数, 0=关闭");
        upsertIfAbsent(RECHARGE_MAX_CENTS, "500000", "单次充值上限（分），默认 ¥5000，0=不限制");
        upsertIfAbsent(RECHARGE_BONUS_PERCENT, "0",
                "充值赠送比例（百分比，充 100 送 10 即填 10），0=关闭（不赠送）；赠送额按分向下取整");
        upsertIfAbsent(BALANCE_REFUND_MAX_CENTS, "500000", "单次余额退款申请上限（分），默认 ¥5000，0=不限制");
        upsertIfAbsent(REFUND_AUTO_APPROVE_MAX_CENTS, "0",
                "余额退款自动审批上限（分），0=关闭（全部人工审核）；≤该上限的申请提交后由系统账号立即审批并原路退款，不进入审批流");
        upsertIfAbsent(PRICING_TIME_WINDOW_ENABLED, "false",
                "时段折扣总开关（默认关闭）；开启后指定时段内成交按比例打折");
        upsertIfAbsent(PRICING_TIME_WINDOW_START_HOUR, "0",
                "时段折扣起始小时（0-23，含）；与结束小时相同视为空窗口⇒不生效");
        upsertIfAbsent(PRICING_TIME_WINDOW_END_HOUR, "0",
                "时段折扣结束小时（0-23，不含）；小于起始小时表示跨零点窗口");
        upsertIfAbsent(PRICING_TIME_WINDOW_DISCOUNT_PERCENT, "0",
                "时段折扣比例（1-99）；0 或 ≥100 视为不生效（不白送）");
        upsertIfAbsent(PRICING_CLEARANCE_ENABLED, "false",
                "库存清仓折扣总开关（默认关闭）；开启后滞销库存按比例清仓");
        upsertIfAbsent(PRICING_CLEARANCE_STOCK_AGE_DAYS, "0",
                "滞销判定天数（>0 生效）；最早可售批次入库满该天数视为滞销，0 或负数=不生效");
        upsertIfAbsent(PRICING_CLEARANCE_DISCOUNT_PERCENT, "0",
                "清仓折扣比例（1-99）；0 或 ≥100 视为不生效；与时段折扣取更深者不叠加");
        upsertIfAbsent(DEVICE_OFFLINE_AUTO_LOCK_MINUTES, "10", "设备离线超时自动锁机分钟数, 0=关闭");
        upsertIfAbsent(DEVICE_OFFLINE_MANUAL_UNLOCK_GRACE_MINUTES, "45",
                "人工解锁后离线自动锁机宽限分钟数, 0=无宽限");
        upsertIfAbsent(DEVICE_STABLE_ONLINE_AUTO_UNLOCK_ENABLED, FALSE,
                "设备恢复稳定在线后是否自动解锁起售（默认关闭）");
        upsertIfAbsent(DEVICE_STABLE_ONLINE_AUTO_UNLOCK_MINUTES,
                String.valueOf(DEFAULT_DEVICE_STABLE_ONLINE_AUTO_UNLOCK_MINUTES),
                "自动解锁前需保持稳定在线分钟数（默认 5）, 0=关闭");
        upsertIfAbsent(OTA_PROGRESS_ENABLED, "false",
                "OTA 升级进度上报（默认关闭）；开启后设备侧的下载/安装进度写入 ota_device_report");
        upsertIfAbsent(OPS_CONFIG_AUDIT_ENABLED, "false",
                "系统配置变更审计（默认关闭）；开启后每次改配置留版本历史与审计日志，可回滚");
        upsertIfAbsent(DEVICE_TEMP_ALERT_MAX_C, "8",
                "柜内温度高于该值(℃)时上报温度异常告警, 0=关闭");
        upsertIfAbsent(MERCHANT_INCIDENT_NOTIFY_COOLDOWN_MINUTES, "30",
                "柜机离线/停售即时通知冷却分钟数（同柜同类型），0=不冷却");
        upsertIfAbsent(DISPUTE_SLA_HOURS, "48", "争议工单 SLA 处理时限（小时）");
        upsertIfAbsent(DISPUTE_SLA_REMINDER_HOURS, "12", "争议 SLA 到期前提醒提前量（小时）");
        upsertIfAbsent(DISPUTE_SLA_WEBHOOK, "", "争议 SLA 提醒/逾期推送 Webhook URL（留空不推送）");
        upsertIfAbsent(OPS_ALERT_FEISHU_WEBHOOK, "",
                "运营告警：飞书自定义机器人 Webhook URL（留空不推送）");
        upsertIfAbsent(OPS_ALERT_FEISHU_SIGN_SECRET, "",
                "运营告警：飞书机器人签名密钥（仅勾选「签名校验」时必填，留空则不签名）");
        upsertIfAbsent(OPS_ALERT_DINGTALK_WEBHOOK, "", "运营告警：钉钉机器人 Webhook URL（留空不推送）");
        upsertIfAbsent(OPS_ALERT_WECOM_WEBHOOK, "", "运营告警：企业微信机器人 Webhook URL（留空不推送）");
        upsertIfAbsent(OPS_ALERT_WEBHOOK, "", "运营告警：通用 JSON Webhook URL（留空不推送）");
        upsertIfAbsent(OPS_ALERT_ESCALATION_ENABLED, FALSE,
                "P0 告警升级链开关（聊天渠道未送达时改打短信/电话给值班人）；默认关");
        upsertIfAbsent(OPS_ALERT_ESCALATION_TYPES,
                "DISPUTE_SLA_OVERDUE,PAYSCORE_ORDER_REFUND_REQUIRED,WECHAT_REFUND_ABNORMAL,"
                        + "PROFIT_SHARING_RETURN_FAILED,INVOICE_FULL_REFUND_RED_INVERSE",
                "需要升级的告警类型（逗号分隔），留空表示全类型；仅对投递失败的告警生效");
        upsertIfAbsent(OPS_ALERT_ONCALL_ROSTER, "",
                "值班表 JSON：[{\"name\":\"张三\",\"phone\":\"138...\",\"days\":[1,2,3,4,5],"
                        + "\"startHour\":9,\"endHour\":18}]；留空=无值班人=不升级");
        upsertIfAbsent(OPS_ALERT_ESCALATION_SMS_WEBHOOK, "",
                "告警升级一级：短信网关 Webhook URL（留空跳过；载荷 phoneNumber/message）");
        upsertIfAbsent(OPS_ALERT_ESCALATION_PHONE_WEBHOOK, "",
                "告警升级二级：电话外呼网关 Webhook URL（留空跳过；短信失败后才会走到这里）");
        upsertIfAbsent("ops.log_retention.notify_months", "6", "通知日志保留月数，0=不清理");
        upsertIfAbsent("ops.log_retention.points_months", "12",
                "【已废弃·请勿使用】积分流水是账本组成部分，禁止删除（见 GrowthLogArchiveScheduler）");
        // 扩展功能域（默认全关 = 维持现状）。🔴 seed 用**小写字面量**而非 FALSE 常量：
        // 门禁的 R5 比的是「解析出的真值」，而 `FALSE` 是 private 常量、解析会退化成原文 "FALSE"，
        // 那样注册表就得写成 type=TEXT / default="FALSE" 才不红（既有 settlement.empty_auto_* 就是这种
        // 历史形态）。用字面量才能保持 type=BOOLEAN（运营台渲染成开关而不是文本框）。
        upsertIfAbsent(CONSUMER_ORDER_SEARCH_ENABLED, "false",
                "消费端：订单列表关键字搜索入口（默认关闭）");
        upsertIfAbsent(CONSUMER_COUPON_ENTRY_ENABLED, "false",
                "消费端：券包入口前置到首页（默认关闭；关闭时仅「我的」页有入口）");
        upsertIfAbsent(MERCHANT_CHARTS_ENABLED, "false",
                "商户端：经营分析趋势/构成图（默认关闭；关闭时维持纯数字与列表）");
        upsertIfAbsent(CONSUMER_PRODUCT_DETAIL_ENABLED, "false",
                "消费端：本柜商品详情弹层（默认关闭；关闭时仅展示商品卡片摘要）");
        upsertIfAbsent(CONSUMER_AD_BANNER_ENABLED, "false",
                "消费端：首页广告位（默认关闭；关闭时首页不渲染广告位。开启后无投放内容时显示占位图）");
        upsertIfAbsent(OPS_SCAN_DOOR_OPEN_MINUTES, "10", "柜门开启超时告警分钟数");
        upsertIfAbsent(OPS_SCAN_UPLOAD_STUCK_MINUTES, "5", "视频上传卡点告警分钟数");
        upsertIfAbsent(OPS_SCAN_RECOGNITION_STUCK_MINUTES, "3", "识别卡点告警分钟数");
        upsertIfAbsent(OPS_SCAN_SETTLEMENT_STUCK_MINUTES, "3", "结算卡点告警分钟数");
        upsertIfAbsent(OPS_BRAND_TITLE, "AI开门柜", "运营后台品牌标题（登录页主标题）");
        upsertIfAbsent(OPS_BRAND_SUBTITLE, "运营管理系统", "运营后台副标题（登录页副文案）");
        upsertIfAbsent(OPS_BRAND_SIDEBAR_TITLE, "AI开门柜运营", "侧栏展开时的品牌文案");
        upsertIfAbsent(OPS_BRAND_LOGO_URL, "", "品牌标志图片地址（留空则用标题末字）");
        upsertIfAbsent(REPLENISHMENT_COMPLETE_REQUIRE_EVIDENCE, "true",
                "商户端完成补货是否必须上传现场凭证照片；false=可跳过（仍可上传）");
        upsertIfAbsent(REPLENISHMENT_CHECK_IN_REQUIRE_LOCATION, "true",
                "柜机已配置坐标时签到是否必须带定位；false=允许空定位签到");
        upsertIfAbsent(REPLENISHMENT_CHECK_IN_MAX_DISTANCE_M, "500",
                "签到距柜机最大允许距离（米）；≤0=关闭距离校验");
        upsertIfAbsent(REPLENISHMENT_COMPLETE_REQUIRE_DOOR, "true",
                "商户端完成补货是否必须先补货开门；false=可跳过");
        // ── 可观测性（P0-6）：追踪的运行期开关与端点。留空=跟随/不用，零行为变化 ──
        upsertIfAbsent(OPS_OBSERVABILITY_TRACING_ENABLED, "",
                "分布式追踪运行期开关: true/1=强制开, false/0=强制关(压过 OTLP_ENDPOINT), 留空=跟随端点");
        upsertIfAbsent(OPS_OBSERVABILITY_OTLP_ENDPOINT, "",
                "OTLP 导出端点，如 http://tempo:4318/v1/traces（容器间走服务名）；留空回退环境变量 OTLP_ENDPOINT");
        // ── 补齐原先「代码里读、却没 seed」的键：不补则运营台看不到，等于假可配置 ──
        upsertIfAbsent(OPS_DEMO_DATA_BANNER, "true", "运营台是否显示「演示数据」横幅（仅 mock 支付模式生效）");
        upsertIfAbsent(RISK_AUTO_CLEAR_INFO_HOURS, "72",
                "风控自动处置：INFO 级事件超该小时数自动清除, 0=关闭");
        upsertIfAbsent(RISK_AUTO_ACK_WARN_HOURS, "168",
                "风控自动处置：WARN 级事件超该小时数自动转待确认(ACK), 0=关闭");
        // 兼容旧默认值中的英文 OPS / 损坏的副标题（历史编码写成 ??????）
        repository.findById(OPS_BRAND_SIDEBAR_TITLE).ifPresent(row -> {
            if ("AI开门柜 OPS".equals(row.getConfigValue())) {
                row.setConfigValue("AI开门柜运营");
                row.setDescription("侧栏展开时的品牌文案");
                row.setUpdatedAt(Instant.now());
                repository.save(row);
            }
        });
        repository.findById(OPS_BRAND_SUBTITLE).ifPresent(row -> {
            String v = row.getConfigValue();
            if (v != null && !v.isBlank() && v.chars().allMatch(c -> c == '?')) {
                row.setConfigValue("运营管理系统");
                row.setDescription("运营后台副标题（登录页副文案）");
                row.setUpdatedAt(Instant.now());
                repository.save(row);
            }
        });
        repository.findById(OPS_BRAND_LOGO_URL).ifPresent(row -> {
            if (row.getDescription() != null && row.getDescription().contains("Logo URL")) {
                row.setDescription("品牌标志图片地址（留空则用标题末字）");
                row.setUpdatedAt(Instant.now());
                repository.save(row);
            }
        });
        refreshDescriptionIfPresent(REPLENISHMENT_CHECK_IN_REQUIRE_LOCATION,
                "柜机已配置坐标时签到是否必须带定位；false=允许空定位签到");
        refreshDescriptionIfPresent(REPLENISHMENT_CHECK_IN_MAX_DISTANCE_M,
                "签到距柜机最大允许距离（米）；≤0=关闭距离校验");
        refreshDescriptionIfPresent(REPLENISHMENT_COMPLETE_REQUIRE_DOOR,
                "商户端完成补货是否必须先补货开门；false=可跳过");
        upsertIfAbsent(CHECKOUT_PREAUTH_CENTS,
                String.valueOf(com.aicabinet.common.constants.CabinetConstants.MIN_BALANCE_CENTS),
                "消费者开门预授权冻结金额(分)");
        refreshDescriptionIfPresent(OPS_SUPPORT_EMAIL,
                "运营支持邮箱（下发 C 端公开配置 supportEmail）");
        refreshDescriptionIfPresent(SETTLEMENT_MIN_CONFIDENCE,
                "自动结算最低整体识别置信度（低于则触发人工审；单品另看 SKU 扣款阈值）");
        refreshDescriptionIfPresent(DISPUTE_AUTO_OPEN,
                "识别低置信是否自动开争议工单；false 时仍结算但记日志（空车/超时等安全路径不受影响）");
        // 把历史库里那条误导性描述刷成「已废弃」：原描述承诺的「积分日志清理」永远不会发生，
        // 与 GrowthLogArchiveScheduler「积分流水禁止 DELETE」的账本约束冲突。
        refreshDescriptionIfPresent("ops.log_retention.points_months",
                "【已废弃·请勿使用】积分流水是账本组成部分，禁止删除（见 GrowthLogArchiveScheduler）");
    }

    private void refreshDescriptionIfPresent(String key, String description) {
        repository.findById(key).ifPresent(row -> {
            if (description.equals(row.getDescription())) {
                return;
            }
            row.setDescription(description);
            row.setUpdatedAt(Instant.now());
            repository.save(row);
        });
    }

    private void upsertIfAbsent(String key, String value, String description) {
        if (!repository.existsById(key)) {
            SystemConfig config = new SystemConfig();
            config.setConfigKey(key);
            config.setConfigValue(value);
            config.setDescription(description);
            config.setUpdatedAt(Instant.now());
            repository.save(config);
        }
    }

    private static SystemConfigDto toDto(SystemConfig config) {
        return new SystemConfigDto(
                config.getConfigKey(),
                config.getConfigValue(),
                config.getDescription(),
                config.getUpdatedAt()
        );
    }
}
