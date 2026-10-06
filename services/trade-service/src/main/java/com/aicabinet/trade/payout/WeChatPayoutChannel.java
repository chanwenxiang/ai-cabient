package com.aicabinet.trade.payout;

import com.aicabinet.common.constants.CabinetConstants;
import com.aicabinet.trade.config.WeChatPayProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 微信提现通道（商家转账到零钱 / 商家转账到银行卡）。
 *
 * <p><b>接入清单</b>（拿到认证后按此实现 transfer 即可）：
 * <ol>
 *   <li>开通「商家转账到零钱」产品（微信支付商户平台 → 产品中心 → 商家转账）；</li>
 *   <li>API 端点：{@code POST /v3/transfer/batches}（新版单笔：{@code /v3/funds/transfer/...}）；</li>
 *   <li>必须用 <b>APIv3 密钥</b>做 AEAD_AES_256_GCM 加解密（复用 {@code WeChatPayV3Aead}）；</li>
 *   <li>证书序列号 + APIv3 私钥签名；</li>
 *   <li><b>收款方需为该商户号的 openid</b>（对私）或已绑定的收款人openid（对公需先完成对公账户绑定/验证）。</li>
 * </ol>
 *
 * <p>⚠️ <b>金额与商户号硬约束</b>：微信「商家转账到零钱」<b>不支持从任意商户号给任意 openid 打款</b>，
 * 需在商户平台配置收款用户。额度方面，代码已按官方<b>默认档</b>预置
 * {@link #channelLimits()}（单笔 ¥200 / 单用户单日 ¥2000 / 单商户号单日 ¥5 万），
 * 真实接入前必须<b>核对目标商户号的实际核准额度</b>：
 * 若商户号已提额到单笔 ¥20000，代码这层限额会让本可出款的大额被<b>提前拒绝</b>，
 * 需相应上调（只调这三个常量，或由运营限额放宽无效 —— 见 {@link PayoutChannelLimits#effective}）。
 *
 * <p><b>本期状态</b>：骨架。{@link #isReady()} 恒 false —— 即使支付参数已配置，
 * 因为「转账产品未开通」无法从配置推断，硬报 ready 会让运营以为能出款。
 */
@Component
public class WeChatPayoutChannel implements PayoutChannel {

    private static final Logger log = LoggerFactory.getLogger(WeChatPayoutChannel.class);

    private final WeChatPayProperties weChatPayProperties;

    public WeChatPayoutChannel(WeChatPayProperties weChatPayProperties) {
        this.weChatPayProperties = weChatPayProperties;
    }

    @Override
    public String channel() {
        return CabinetConstants.PAY_CHANNEL_WECHAT;
    }

    /**
     * 恒 false：微信「收款参数已配」≠「商户号已开通商家转账且已配收款用户」，
     * 这两项无法从本地配置推断，误报 ready 会导致批量出款失败。
     */
    @Override
    public boolean isReady() {
        return false;
    }

    @Override
    public String supportedAccountType() {
        // 微信零钱转账本质是对私；对公需走「商家转账到银行卡」并额外核验
        return PayoutConstants.PAYEE_TYPE_PERSONAL;
    }

    /**
     * 微信「商家转账」<b>默认档</b>渠道限额（分）。
     *
     * <p>🔴 <b>来源与口径</b>：微信支付官方《设置转账额度》
     * （{@code pay.weixin.qq.com/doc/v3/merchant/4013747667}），
     * <b>商户号主体不为个体户</b>一栏：
     * <ul>
     *   <li>单笔限额<b>默认 ¥200</b>，可调区间 0.1–200 元；</li>
     *   <li>单用户转账限额（<b>单商户号单日</b>向<b>同一用户</b>）<b>默认 ¥2000</b>，可调 0.1–2000；</li>
     *   <li>单日转账额度（<b>单商户号单日总额</b>）<b>默认 ¥5 万</b>，可调 0.1–5 万；</li>
     *   <li>单月 3000 万，<b>不支持修改</b>（本类不设此维，改不了的东西不写进限额模型）。</li>
     * </ul>
     *
     * <p><b>为什么必须拆三个维度而不是一个 200</b>（2026-10-06 用户纠正）：
     * 单笔 ¥200 只是最窄的那道门，商户当日提 3 笔 ¥180（¥540）单笔都合规，
     * 却会撞上「单用户单日 ¥2000 / 单商户号单日 ¥5 万」——
     * 只校验单笔的商户会在<b>申请时通过、渠道侧被拒</b>，表现为「一直 PAYING 转人工」。
     *
     * <p>⚠️ <b>个体户主体更严</b>：官方同页个体户一栏为单笔 ¥200 / 单用户单日 ¥200 /
     * 单日 ¥5000。本项目收款主体是<b>商户</b>（非个体户），故用非个体户口径；
     * 若将来接入个体户收款，<b>必须改这三个数字</b>（而不是靠运营限额去压 ——
     * 运营限额只能更严，不能更松）。
     *
     * <p>📌 官方明确提示「限额仅供参考、请以接口实际返回为准」，
     * 因此这些数字的作用是<b>提前给用户一个可解释的拒绝</b>，不是精确的资金安全边界。
     */
    @Override
    public PayoutChannelLimits channelLimits() {
        return new PayoutChannelLimits(
                20_000L,     // 单笔 ¥200
                200_000L,    // 单用户单日 ¥2000
                5_000_000L   // 单商户号单日总额 ¥50000
        );
    }

    @Override
    public PayoutResult transfer(PayoutCommand command) {
        if (!weChatPayProperties.isConfigured()) {
            return PayoutResult.notReady(channel(), "微信支付商户号 + APIv3 密钥 + 证书");
        }
        // 🔴 只记 requestId/金额，绝不记 accountNo（openid 属个人敏感标识）
        log.warn("WeChat payout channel not wired yet: requestId={}, payeeType={}, netCents={}, idemKey={}",
                command.requestId(), command.accountType(), command.netCents(), command.idemKey());
        return PayoutResult.notReady(channel(),
                "微信「商家转账」产品开通 + 收款用户配置 + transfer API 实现");
    }
}
