package com.aicabinet.trade.payout;

import com.aicabinet.common.constants.CabinetConstants;

/**
 * 提现收款方相关常量（对公/对私、主体类型）。
 *
 * <p>🔴 <b>命名纪律</b>：这里用 {@code PAYEE_TYPE_*} 前缀，<b>不复用</b>
 * {@link CabinetConstants#ACCOUNT_TYPE_CONSUMER} / {@code ACCOUNT_TYPE_OPERATOR}——
 * 后者是<b>登录账号类型</b>（消费者/运营），与「对公/对私收款账户」语义完全无关，
 * 混用会造成「对私收款=消费者账号」这类误判。
 */
public final class PayoutConstants {

    private PayoutConstants() {
    }

    // ============ 收款账户类型（对公/对私）============

    /** 对公（企业）收款：需公司名 + 开户行 + 税号，代付通常需验章/合同。 */
    public static final String PAYEE_TYPE_COMPANY = "PAYEE_TYPE_COMPANY";
    /** 对私（个人）收款：需实名，通常个人卡/零钱。 */
    public static final String PAYEE_TYPE_PERSONAL = "PAYEE_TYPE_PERSONAL";

    // ============ 主体类型（谁的收款账户）============

    public static final String PAYEE_OWNER_MERCHANT = "MERCHANT";
    public static final String PAYEE_OWNER_LINE_MANAGER = "LINE_MANAGER";
    public static final String PAYEE_OWNER_PLATFORM = "PLATFORM";

    // ============ 状态 ============

    public static final String PAYEE_STATUS_ACTIVE = "ACTIVE";
    public static final String PAYEE_STATUS_DISABLED = "DISABLED";

    // ============ 银行通道（不需要第三方认证，但需银行侧资料）============

    public static final String PAY_CHANNEL_BANK = "BANK";

    /**
     * 对公代付的最小字段集 —— 缺任一都不该发起到银行。
     * 实现方在 {@link PayoutChannel#transfer} 入口用 {@link #requireCompanyFields} 校验。
     */
    public static boolean isCompany(String payeeType) {
        return PAYEE_TYPE_COMPANY.equals(payeeType);
    }

    public static boolean isPersonal(String payeeType) {
        return PAYEE_TYPE_PERSONAL.equals(payeeType);
    }

    public static boolean isValidPayeeType(String payeeType) {
        return isCompany(payeeType) || isPersonal(payeeType);
    }
}
