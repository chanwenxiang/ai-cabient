package com.aicabinet.trade.payout;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 银行代付通道（本项目自建/银企直连）。
 *
 * <p><b>与微信/支付宝的本质差异</b>：前两者是<b>平台级产品</b>（申请即得、SDK 统一）；
 * 银行代付是<b>机构级合作</b> —— 通常需要：与银行签代付协议、开通对公账户、
 * 上传合同/授权书、双方联调与小额真实打款验证。<b>周期以周/月计，不是申请认证当天可用。</b>
 *
 * <p><b>但它是唯一天然支持对公的通道</b> —— 微信/支付宝提现本质是对私（打给零钱/支付宝余额），
 * 打到企业对公户必须走银行代付或「商家转账到银行卡」（后者仍受商户号额度限制）。
 *
 * <p><b>接入清单</b>：
 * <ol>
 *   <li>银行侧代付接口（常见为「企业付款到银行卡」类接口，需证书/IP 白名单）；</li>
 *   <li>对公必填：<b>公司名 + 开户行全称 + 银行账号 + 纳税人识别号</b>，
 *       部分银行还要求「银行预留手机号」做二次确认；</li>
 *   <li>金额上限由<b>银行与协议约定</b>（常见单笔/单日上限），须在
 *       {@link #channelDailyLimitCents()} 显式配置并在申请时校验（当前恒 0 = 不限，依赖人工控制）。</li>
 *   <li>异步：多数银行代付为异步，<b>必须走 PayoutPendingException + 查单</b>。</li>
 * </ol>
 *
 * <p><b>本期状态</b>：骨架。但对公字段校验在本通道内<b>已实现</b>（不依赖银行接口），
 * 可提前拦住「对公却没填税号/开户行」这类结构性错误。
 */
@Component
public class BankPayoutChannel implements PayoutChannel {

    private static final Logger log = LoggerFactory.getLogger(BankPayoutChannel.class);

    @Override
    public String channel() {
        return PayoutConstants.PAY_CHANNEL_BANK;
    }

    /** 恒 false：银行代付取决于线下协议签订进度，代码无法探测。 */
    @Override
    public boolean isReady() {
        return false;
    }

    @Override
    public String supportedAccountType() {
        // 银行通道同时收对公与对私（对私=个人银行卡）
        return PayoutConstants.PAYEE_TYPE_COMPANY;
    }

    /** 银行协议约定的单日代付上限（分）。0 = 未配置 ⇒ 不做代码层拦截。 */
    public long channelDailyLimitCents() {
        return 0L;
    }

    /**
     * 银行渠道限额：单日上限取协议约定的 {@link #channelDailyLimitCents()}，
     * 单笔与单收款人不设（银行代付通常按收款人逐笔约定，不存在「同一用户单日 2000」这种平台级池子）。
     *
     * <p>未签协议时该值为 0 ⇒ 等价于不限，与「协议未落地」的现状一致。
     */
    @Override
    public PayoutChannelLimits channelLimits() {
        return new PayoutChannelLimits(0L, 0L, channelDailyLimitCents());
    }

    /**
     * 对公最小字段校验 —— <b>不依赖银行接口即可执行</b>。
     *
     * <p>提前拦下结构性缺字段，避免走到渠道才被拒（那时钱已冻结、状态已 PAYING，
     * 还得走人工释放，多一轮人工成本）。
     */
    static void requireCompanyFields(PayoutCommand command) {
        if (!PayoutConstants.isCompany(command.accountType())) {
            return;
        }
        requireText(command.accountName(), "对公收款户名（公司全称）");
        requireText(command.bankName(), "对公开户银行全称");
        requireText(command.taxNo(), "纳税人识别号");
        requireText(command.accountNo(), "对公银行账号");
    }

    private static void requireText(String value, String fieldLabel) {
        if (value == null || value.isBlank()) {
            throw new PayoutChannel.PayeeIncompatibleException(
                    "对公代付缺少「" + fieldLabel + "」，请补全收款账户信息");
        }
    }

    @Override
    public PayoutResult transfer(PayoutCommand command) {
        try {
            requireCompanyFields(command);
        } catch (PayoutChannel.PayeeIncompatibleException e) {
            // 结构性缺字段：属于「确定未出款」，可安全释放冻结
            return PayoutResult.rejected(e.getMessage());
        }
        // 🔴 只记 requestId/金额/渠道单号，绝不记 accountNo/taxNo
        log.warn("Bank payout channel not wired yet: requestId={}, payeeType={}, netCents={}, idemKey={}",
                command.requestId(), command.accountType(), command.netCents(), command.idemKey());
        return PayoutResult.notReady(channel(),
                "与银行签订代付协议 + 开通对公代付接口 + 双方联调");
    }
}
