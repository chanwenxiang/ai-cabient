package com.aicabinet.trade.payout;

import com.aicabinet.common.constants.CabinetConstants;
import com.aicabinet.trade.config.AlipayProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 支付宝提现通道（商家转账到支付宝账户）。
 *
 * <p><b>接入清单</b>（拿到认证后按此实现 transfer 即可）：
 * <ol>
 *   <li>开通「商家转账」产品（支付宝开放平台 → 产品 → 商家转账）；</li>
 *   <li>API：{@code alipay.fund.trans.uni.transfer}（异步通知
 *       {@code alipay_fund_trans_uni_transfer_response}）；</li>
 *   <li>本项目已有 {@link AlipayProperties}（含 privateKey / alipayPublicKey），
 *       签名与验签复用现有客户端能力，<b>不需要新的密钥体系</b>；</li>
 *   <li>收款方需为<b>已绑定的支付宝用户</b>（userId 或登录账号），未绑定会被渠道拒绝；</li>
 *   <li><b>注意异步语义</b>：该接口同步返回受理结果、异步通知最终结果 ⇒ 成功路径也要等回调，
 *       或查单接口兜底（详见 {@link PayoutChannel} 的 PayoutPendingException 约定）。</li>
 * </ol>
 *
 * <p>⚠️ <b>异步是本通道最大的坑</b>：同步 200 只代表「受理」，不代表出款成功。
 * 绝不能把「同步成功」当 {@code success=true} 返回 —— 那会在实际未出款时释放冻结，
 * 造成平台资损。正确做法：同步受理后转 {@link PayoutPendingException}，等异步回调或查单再定终态。
 *
 * <p><b>本期状态</b>：骨架，恒返回 notReady。
 */
@Component
public class AlipayPayoutChannel implements PayoutChannel {

    private static final Logger log = LoggerFactory.getLogger(AlipayPayoutChannel.class);

    private final AlipayProperties alipayProperties;

    public AlipayPayoutChannel(AlipayProperties alipayProperties) {
        this.alipayProperties = alipayProperties;
    }

    @Override
    public String channel() {
        return CabinetConstants.PAY_CHANNEL_ALIPAY;
    }

    /** 恒 false：与微信同理，「参数已配」≠「商家转账产品已开通」。 */
    @Override
    public boolean isReady() {
        return false;
    }

    @Override
    public String supportedAccountType() {
        return PayoutConstants.PAYEE_TYPE_PERSONAL;
    }

    /**
     * 支付宝「商家转账」<b>默认档</b>渠道限额（分）。
     *
     * <p>🔴 <b>来源与口径</b>：支付宝开放平台《商家转账》
     * （{@code opendocs.alipay.com/open/009zdp}）§5.2 + 官方错误码说明
     * （{@code EXCEED_LIMIT_PERSONAL_SM_AMOUNT} / {@code EXCEED_LIMIT_DM_MAX_AMOUNT}）：
     * <ul>
     *   <li>单笔：转给<b>个人</b>支付宝账户最高 <b>5 万元</b>；转给企业账户最高 10 万
     *       —— 本通道 {@link #supportedAccountType()} 是<b>对私</b>，故取 5 万；</li>
     *   <li>日：默认额度（见下方口径说明）；</li>
     *   <li>月：默认额度（同上）。<b>月维度不写入本模型</b> —— 见下。</li>
     * </ul>
     *
     * <p>⚠️ <b>日限额取值是「取最保守」而非抄某一个页面</b>：官方不同页面
     * 对同一产品给了不一致的数字（错误码文档写<b>日 100 万 / 月 300 万</b>，
     * 另一 FAQ 页面写<b>日 200 万 / 月 3100 万</b>）。
     * 两个数字都注明「支付宝会根据实际转账资金情况进行调整，具体以实际支持为准」，
     * 即<b>官方自己都不保证</b>。
     * 面对这种分歧，取<b>更严的那个</b>（100 万）——
     * 少拦一点的后果是「渠道拒付、提现转 FAILED」（商户能理解）；
     * 多拦一点的后果是「本可出款的钱不让提」（运营要背投诉）。
     * 若实际商户号已提额，<b>需上调本常量</b>。
     *
     * <p><b>为什么「单收款人单日」不设</b>：支付宝的日/月限额按<b>付款方商户</b>计，
     * <b>没有</b>「同一收款人单日 X 元」这种维度（那是微信的规则）。
     * 填一个不存在的维度会让运营误以为有这道闸门。
     *
     * <p>📌 官方明确「具体以实际支持为准」，故本常量作用是
     * <b>提前给用户一个可解释的拒绝</b>，不是精确的资金安全边界。
     */
    @Override
    public PayoutChannelLimits channelLimits() {
        return new PayoutChannelLimits(
                5_000_000L,   // 单笔 ¥50000（转个人支付宝账户上限；企业账户为 ¥10万）
                0L,           // 无「单收款人单日」维度（支付宝按付款方计）
                100_000_000L  // 单日 ¥1000000（官方多页面不一致，取最保守值）
        );
    }

    @Override
    public PayoutResult transfer(PayoutCommand command) {
        if (!alipayProperties.isConfigured()) {
            return PayoutResult.notReady(channel(), "支付宝开放平台应用 + 应用私钥/公钥");
        }
        // 🔴 只记 requestId/金额，绝不记 accountNo
        log.warn("Alipay payout channel not wired yet: requestId={}, payeeType={}, netCents={}, idemKey={}",
                command.requestId(), command.accountType(), command.netCents(), command.idemKey());
        return PayoutResult.notReady(channel(), "「商家转账」产品开通 + 收款用户绑定 + uni.transfer 实现");
    }
}
