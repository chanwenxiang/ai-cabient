package com.aicabinet.trade.domain;

/**
 * {@code payment_operation} 的金额类型口径**单一事实源**（2026-10-18，巡检假阳性复盘）。
 *
 * <p>起因：F-10（2026-10-17）只修了资金读路径 {@code PaymentOperationMapper#findCompletedPaymentOpsByOrderId}，
 * 而同一份「已完成净额 = 进 − 退」清单在仓库里还有 4 个手写副本（{@code DataConsistencyService} 的
 * 报警判据、提示语净额、以及**两条会动钱的修复路径取数**）。它们都停在 {@code CHARGE/ADJUST_CHARGE}，
 * 于是余额预付单（收款只写 {@code PREAUTH_CAPTURE}）在运营台「数据一致性」页恒报
 * 「期望净入账 686 ≠ 实际净入账 0」。教训 = 口径类修复必须扫全副本，见 lessons-learned #326。
 *
 * <p>由 {@code scripts/check-payment-net-types-single-source.mjs} 钉住：净额形态的类型清单
 * 只允许从这里出发；确实**应当**排除 {@code PREAUTH_CAPTURE} 的渠道现金对账口径须显式登记豁免与理由。
 */
public final class PaymentOperationTypes {

    /** 购物扣款入账。 */
    public static final String CHARGE = "CHARGE";

    /** 结算调整入账（可正可负）。 */
    public static final String ADJUST_CHARGE = "ADJUST_CHARGE";

    /** 余额预授权冲抵入账：开门预授权冻结后按实结算的凭据行，全额冲抵时**不另写 CHARGE**。 */
    public static final String PREAUTH_CAPTURE = "PREAUTH_CAPTURE";

    /** 退款出账。 */
    public static final String REFUND = "REFUND";

    /** 「进」类型 SQL 清单（含预授权冲抵）。 */
    public static final String INBOUND_TYPES_SQL_LIST =
            "'CHARGE', 'ADJUST_CHARGE', 'PREAUTH_CAPTURE'";

    /** 净额判据需要读取的完成态类型全集（进 + 退）。 */
    public static final String NET_TYPES_SQL_LIST = INBOUND_TYPES_SQL_LIST + ", 'REFUND'";

    /**
     * 净入账 CASE 片段（进为正、退为负）。
     *
     * @param colPrefix 列前缀，如 {@code "po."} 或表内直引时传 {@code ""}
     */
    public static String netCaseSql(String colPrefix) {
        return "CASE "
                + "WHEN " + colPrefix + "operation_type IN (" + INBOUND_TYPES_SQL_LIST + ") "
                + "THEN " + colPrefix + "amount_cents "
                + "WHEN " + colPrefix + "operation_type = '" + REFUND + "' "
                + "THEN -" + colPrefix + "amount_cents "
                + "ELSE 0 END";
    }

    /** 净额判据的类型过滤片段（把 ADJUST/REFUND 之外的行挡在门外）。 */
    public static String netTypesFilterSql(String colPrefix) {
        return colPrefix + "operation_type IN (" + NET_TYPES_SQL_LIST + ")";
    }

    private PaymentOperationTypes() {}
}
