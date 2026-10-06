package com.aicabinet.trade.service;

import com.aicabinet.trade.config.LineWithdrawProperties;
import com.aicabinet.trade.config.MerchantWithdrawProperties;
import org.springframework.stereotype.Component;

/**
 * 提现参数解析：**运营台配置（SystemConfig）优先于 yml**，运行期生效免重启。
 *
 * <p><b>为什么要这层</b>：限额与费率是运营参数（业务方要随时调），写进 {@code application.yml}
 * 每次调整都要改配置 + 重启；写进 {@code system_config} 则运营台改完立即生效。
 * 两者都要保留 —— yml 负责「部署期的初值」，SystemConfig 负责「运行期的调整」，
 * 且 {@code upsertIfAbsent} seed 让首次启动就有正确的行。
 *
 * <p><b>取值优先级</b>：{@code SystemConfig}（运营台已改）> {@code application.yml} > 代码兜底。
 * 判据是「配置行是否存在」而非「值是否为空」：seed 已在首次启动写入行，
 * 因此正常情况下 yml 改动<b>不会</b>生效（配置行已存在）—— 这是有意的：
 * 一旦交给运营台调，就不该被部署动作悄悄改回去（同类设计的先例见
 * {@code DEVICE_STABLE_ONLINE_AUTO_UNLOCK_MINUTES} 的注释）。
 *
 * <p>🔴 <b>0 一律视为「不限制」</b>（与 {@code RECHARGE_MAX_CENTS} 一致）：
 * 运营台把上限填 0 的语义是「不拦」，不是「禁止提现」。
 *
 * <p>⚠️ <b>性能</b>：每次读取都是一次 {@code system_config} 主键查询。
 * 提现申请是低频操作（人工点击触发），不引入本地缓存 ——
 * 缓存会让「刚改的限额」在 TTL 内不生效，正是我们要避免的。
 */
@Component
public class WithdrawPolicyResolver {

    private final SystemConfigService systemConfigService;
    private final MerchantWithdrawProperties merchantProperties;
    private final LineWithdrawProperties lineProperties;

    public WithdrawPolicyResolver(SystemConfigService systemConfigService,
                                  MerchantWithdrawProperties merchantProperties,
                                  LineWithdrawProperties lineProperties) {
        this.systemConfigService = systemConfigService;
        this.merchantProperties = merchantProperties;
        this.lineProperties = lineProperties;
    }

    /**
     * 只读 yml 的构造（**不查 system_config**）——供 V307 前的调用点与纯单元测试使用。
     *
     * <p>{@code systemConfigService} 为 {@code null} 时 {@link #pick} 直接取 yml 值，
     * 语义 = 「运营台没配」时的样子 ⇒ 行为与接入前一致。
     */
    public static WithdrawPolicyResolver ymlOnly(MerchantWithdrawProperties merchantProperties) {
        return new WithdrawPolicyResolver(null, merchantProperties, null);
    }

    /**
     * 只读 yml 的构造（仅线长侧）。
     *
     * <p>商户侧参数留 null —— 线长服务不会读商户限额，故无影响。
     * 两个 Properties 是<b>彼此独立的 record</b>，不存在「一个对象同时当两者传」的可能。
     */
    public static WithdrawPolicyResolver ymlOnly(LineWithdrawProperties lineProperties) {
        return new WithdrawPolicyResolver(null, null, lineProperties);
    }

    /**
     * 只读 yml 的构造（商户 + 线长两侧）。
     */
    public static WithdrawPolicyResolver ymlOnly(MerchantWithdrawProperties merchantProperties,
                                                 LineWithdrawProperties lineProperties) {
        return new WithdrawPolicyResolver(null, merchantProperties, lineProperties);
    }

    /** 商户提现单笔下限（分）；0 = 不限制。 */
    public long merchantMinAmountCents() {
        return pick(SystemConfigService.MERCHANT_WITHDRAW_MIN_CENTS,
                merchantProperties.minAmountCents(), 100L);
    }

    /** 商户提现单笔上限（分）；0 = 不限制。 */
    public long merchantMaxAmountCents() {
        return pick(SystemConfigService.MERCHANT_WITHDRAW_MAX_CENTS,
                merchantProperties.maxAmountCents(), 0L);
    }

    /** 商户提现单日累计上限（分）；0 = 不限制。 */
    public long merchantDailyLimitCents() {
        return pick(SystemConfigService.MERCHANT_WITHDRAW_DAILY_LIMIT_CENTS,
                merchantProperties.dailyLimitCents(), 500_000L);
    }

    /** 商户提现免审阈值（分）；0 = 全部人工审核。 */
    public long merchantReviewThresholdCents() {
        return pick(SystemConfigService.MERCHANT_WITHDRAW_REVIEW_THRESHOLD_CENTS,
                merchantProperties.reviewThresholdCents(), 50_000L);
    }

    public long merchantFeeCents() {
        return pick(SystemConfigService.MERCHANT_WITHDRAW_FEE_CENTS,
                merchantProperties.feeCents(), 0L);
    }

    public long merchantFeeBps() {
        return pick(SystemConfigService.MERCHANT_WITHDRAW_FEE_BPS,
                merchantProperties.feeBps(), 0L);
    }

    /** 商户提现手续费单笔封顶（分）；0 = 不封顶。 */
    public long merchantFeeCapCents() {
        return pick(SystemConfigService.MERCHANT_WITHDRAW_FEE_CAP_CENTS, 0L, 0L);
    }

    /** 线长提现单笔上限（分）；0 = 不限制。 */
    public long lineMaxAmountCents() {
        // V308 修：此前误传 0L 作yml 值 ⇒ yml 里的 max-amount-cents 永远不生效
        return pick(SystemConfigService.LINE_WITHDRAW_MAX_CENTS,
                lineProperties == null ? 0L : lineProperties.maxAmountCents(), 0L);
    }

    /** 线长提现单日累计上限（分）；0 = 不限制。 */
    public long lineDailyLimitCents() {
        return pick(SystemConfigService.LINE_WITHDRAW_DAILY_LIMIT_CENTS,
                lineProperties == null ? 0L : lineProperties.dailyLimitCents(), 500_000L);
    }

    /** 线长提现手续费固定额（分）。 */
    public long lineFeeCents() {
        return pick(SystemConfigService.LINE_WITHDRAW_FEE_CENTS,
                lineProperties == null ? 0L : lineProperties.feeCents(), 0L);
    }

    /** 线长提现手续费万分比（如 50 = 0.5%）。 */
    public long lineFeeBps() {
        return pick(SystemConfigService.LINE_WITHDRAW_FEE_BPS,
                lineProperties == null ? 0L : lineProperties.feeBps(), 0L);
    }

    /** 线长提现手续费单笔封顶（分）；0 = 不封顶。语义同 {@link #merchantFeeCapCents}。 */
    public long lineFeeCapCents() {
        return pick(SystemConfigService.LINE_WITHDRAW_FEE_CAP_CENTS, 0L, 0L);
    }

    /** 线长提现单笔下限（分）；0 = 不限制。 */
    public long lineMinAmountCents() {
        return pick(SystemConfigService.LINE_WITHDRAW_MIN_CENTS,
                lineProperties == null ? 0L : lineProperties.minAmountCents(), 100L);
    }

    /** 线长提现免审阈值（分）；0 = 全部人工审核。 */
    public long lineReviewThresholdCents() {
        return pick(SystemConfigService.LINE_WITHDRAW_REVIEW_THRESHOLD_CENTS,
                lineProperties == null ? 0L : lineProperties.reviewThresholdCents(), 50_000L);
    }

    /** 运营台「打款模式」面板展示用：全部提现参数 + 各通道就绪态。 */
    public java.util.Map<String, Object> describeForOps() {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("merchantMinAmountCents", merchantMinAmountCents());
        m.put("merchantMaxAmountCents", merchantMaxAmountCents());
        m.put("merchantDailyLimitCents", merchantDailyLimitCents());
        m.put("merchantReviewThresholdCents", merchantReviewThresholdCents());
        m.put("merchantFeeCents", merchantFeeCents());
        m.put("merchantFeeBps", merchantFeeBps());
        m.put("merchantFeeCapCents", merchantFeeCapCents());
        m.put("lineMaxAmountCents", lineMaxAmountCents());
        m.put("lineDailyLimitCents", lineDailyLimitCents());
        m.put("lineFeeCapCents", lineFeeCapCents());
        return m;
    }

    /**
     * 取配置行；行不存在时用 yml 值；两者都无则用代码兜底。
     *
     * <p>负数一律归零（0 = 不限制）：运营台误填负数不该变成「禁止一切提现」。
     */
    private long pick(String key, long ymlValue, long codeDefault) {
        // systemConfigService 为 null（ymlOnly 模式）⇒ 直接用 yml，语义 =「运营台没配」
        String raw = systemConfigService == null ? null : systemConfigService.getValue(key, null);
        if (raw == null || raw.isBlank()) {
            return Math.max(0L, ymlValue > 0 ? ymlValue : codeDefault);
        }
        try {
            return Math.max(0L, Long.parseLong(raw.trim()));
        } catch (NumberFormatException e) {
            // 配置值不是数字 ⇒ 落回 yml，而不是让一次误填把提现功能整体打挂
            return Math.max(0L, ymlValue > 0 ? ymlValue : codeDefault);
        }
    }
}
