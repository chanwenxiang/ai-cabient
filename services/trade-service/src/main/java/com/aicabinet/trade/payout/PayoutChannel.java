package com.aicabinet.trade.payout;

import com.aicabinet.common.constants.CabinetConstants;

/**
 * 提现打款通道抽象：WECHAT / ALIPAY / BANK 三通道统一出口。
 *
 * <p><b>为什么抽这层</b>：真实出款时三类通道的参数、校验、签名、回执形态完全不同
 * （微信=openid+商户转账、支付宝=userId/支付宝账号、银行=卡号+开户行+税号）。
 * 不抽接口就得在 {@code *WithdrawPayoutService} 里堆 if-else，加第 4 个通道要改主干。
 *
 * <p><b>本期的实现状态</b>：三个 {@code *Channel} 实现都是<b>骨架</b>——
 * {@link #isReady()} 恒 false、{@link #transfer} 恒返回失败并说明未接入。
 * <b>拿到支付认证后只需实现各通道的 HTTP 调用 + 把 isReady 改为真实探测</b>，
 * 上层编排（冻结/审核/幂等/终态）零改动。
 *
 * <p>🔴 <b>接入契约（实现方必须遵守）</b>：
 * <ol>
 *   <li><b>幂等</b>：必须把 {@link PayoutCommand#idemKey()} 原样传给渠道作为商户单号。
 *       渠道侧对该号幂等 —— 这是「渠道已出款但本地回执丢失」时不重复出款的唯一保障。</li>
 *   <li><b>不得吞异常</b>：网络超时等不确定态应抛 {@link PayoutPendingException}
 *       （⇒ 上层保持 PAYING 转人工），<b>不得</b>当失败返回（⇒ 会释放冻结造成双重支出）。</li>
 *   <li><b>不得在通道内改金额</b>：{@code netCents} 由上游算好（申请时快照 feeCents），
 *       通道只负责把这一笔钱送出去。</li>
 *   <li><b>不得抛裸 RuntimeException 表达业务失败</b>：业务不可受理（如实名不符）返
 *       {@link PayoutResult#rejected}，让上层区分「可重试」与「已确定失败」。</li>
 * </ol>
 */
public interface PayoutChannel {

    /** 通道标识，对应 {@link CabinetConstants#PAY_CHANNEL_*} 。 */
    String channel();

    /**
     * 该通道是否已具备真实出款能力。
     *
     * <p>实现方应做<b>真实探测</b>（凭据是否配全、必要是否已开通），而不是返回配置布尔值——
     * 「配了但没开通」会让上层以为可以出款，实际发起后被渠道拒绝。
     */
    boolean isReady();

    /** 该通道要求的收款账户类型（对公/对私），用于申请时前置校验。 */
    String supportedAccountType();

    /**
     * 通道的<b>渠道侧硬限额</b>（厂商规则，代码常量）。
     *
     * <p>🔴 与运营限额（{@code merchant.withdraw.*}）的关系是<b>取更严者</b>，见
     * {@link PayoutChannelLimits#effective}。本方法<b>不查数据库</b>，
     * 供申请校验与运营台展示使用。
     *
     * <p>默认返回 {@link PayoutChannelLimits#UNLIMITED}：
     * 新增通道时若限额尚未确认，<b>宁可先不限</b>（由人工把关）也不要写错数字把大额提现全拦死。
     */
    default PayoutChannelLimits channelLimits() {
        return PayoutChannelLimits.UNLIMITED;
    }

    /**
     * 发起打款。
     *
     * @throws PayoutPendingException 渠道结果不确定（超时/未知）—— 上层保持 PAYING 转人工
     */
    PayoutResult transfer(PayoutCommand command);

    /**
     * 打款请求。
     *
     * @param requestId    本地提现单 ID（仅日志用，不可作渠道幂等号）
     * @param idemKey      <b>幂等键，必须原样传给渠道</b>
     * @param accountType  ACCOUNT_TYPE_COMPANY / ACCOUNT_TYPE_PERSONAL
     * @param accountName  户名（对公=公司名）
     * @param accountNo    <b>明文账号</b>（由上层解密后传入；实现方不得落日志）
     * @param bankName     开户行（BANK 通道必填）
     * @param bankBranch   支行（可选）
     * @param bankCode     <b>联行号</b>（CNAPS 12 位，可选）—— V309 新增。
     *                     银行代付只有「户名+账号+开户行」三要素时，部分银行<b>无法自动路由</b>，
     *                     打款会被退回且失败原因常只写「收款行不匹配」，排查成本高。
     *                     大额/跨行代付基本必填。<b>不强制</b>：强制会在未签约阶段把所有
     *                     对公打款拦掉，而那时我们还不知道对方到底要哪几要素。
     * @param bankProvinceCity 开户行省市（如「广东省深圳市」，可选）——
     *                     部分渠道大额代付要求用于匹配清算网点。
     * @param taxNo        纳税人识别号（对公代付必填）
     * @param netCents     实际出款金额（分）= 提现额 − 手续费，已由上游算好
     * @param remark       打款附言（展示给收款方）
     */
    record PayoutCommand(
            long requestId,
            String idemKey,
            String accountType,
            String accountName,
            String accountNo,
            String bankName,
            String bankBranch,
            String bankCode,
            String bankProvinceCity,
            String taxNo,
            long netCents,
            String remark
    ) {
    }

    /**
     * 打款结果。
     *
     * @param success    是否已确认出款成功
     * @param channelOrderNo 渠道单号（对账用；失败时可为 null）
     * @param message    面向运营的失败原因/备注
     */
    record PayoutResult(boolean success, String channelOrderNo, String message) {

        /** 出款成功。 */
        public static PayoutResult paid(String channelOrderNo, String message) {
            return new PayoutResult(true, channelOrderNo, message);
        }

        /**
         * 出款被渠道<b>明确拒绝</b>（可确定未出款，如实名不符/余额不足/账户不存在）。
         * 上层据此释放冻结并置 FAILED。
         */
        public static PayoutResult rejected(String message) {
            return new PayoutResult(false, null, message);
        }

        /**
         * 通道<b>尚未接入</b>——不是业务失败，是部署状态问题。
         * 上层按「确定未出款」处理（释放冻结），但文案须与业务拒绝区分，避免误判为商户问题。
         */
        public static PayoutResult notReady(String channel, String requiredAuth) {
            return new PayoutResult(false, null,
                    channel + " 提现通道未接入（需完成" + requiredAuth + "后方可出款）");
        }
    }

    /**
     * 渠道结果不确定（网络超时、已发出但未收到应答）。
     *
     * <p>🔴 上层<b>必须</b>保持 PAYING 并转人工核对，<b>禁止</b>释放冻结——
     * 否则会出现「渠道已出款 + 本地已解冻」的双重支出。
     */
    public static class PayoutPendingException extends RuntimeException {
        private final String channelOrderNo;

        public PayoutPendingException(String message, String channelOrderNo) {
            super(message);
            this.channelOrderNo = channelOrderNo;
        }

        public String channelOrderNo() {
            return channelOrderNo;
        }
    }

    /**
     * 收款方信息不满足通道要求（结构性缺字段，如对公缺税号）。
     *
     * <p>属于<b>申请前</b>应拦下的问题，不该发起到渠道才失败。
     */
    public static class PayeeIncompatibleException extends RuntimeException {
        public PayeeIncompatibleException(String message) {
            super(message);
        }
    }
}
