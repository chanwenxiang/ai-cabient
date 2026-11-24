package com.aicabinet.trade.payout;

import com.aicabinet.common.constants.CabinetConstants;
import com.aicabinet.trade.config.WeChatPayProperties;
import com.aicabinet.trade.config.WeChatTransferProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * 微信提现通道（商家转账 · 用户授权免确认模式，MA-407）。
 *
 * <p><b>接入清单</b>（已全部落地）：
 * <ol>
 *   <li>开通「商家转账」产品（商户平台 → 产品中心 → 商家转账）——✅ 场景 1005 佣金报酬已开通；</li>
 *   <li>设置接口安全 IP（服务器公网 IP）——✅ 120.25.246.179；</li>
 *   <li>运营账户充值（出款来源）——✅；</li>
 *   <li>用户授权免确认收款（小程序 JSAPI 拉起授权页，一次性长期有效）；</li>
 *   <li>APIv3 签名（复用 {@code WeChatPayV3Client}）+ 场景 1005 报备两条 + 幂等键原样作 out_bill_no。</li>
 * </ol>
 *
 * <p>🔴 <b>为什么用免确认模式</b>：用户申请提现时已明确表达收款意愿，再让每笔去微信点确认
 * 是多余摩擦。免确认授权一次后，{@code /transfer-bills/transfer} 同步返回 {@code SUCCESS} 终态，
 * 无需异步回调+查单闭环（那是确认模式的负担）。
 *
 * <p>🔴 <b>授权单号确定性派生</b>：微信要求「一个用户账号对应唯一一个生效中的授权」。
 * 用 openid 的 SHA-256 前 24 位十六进制作 out_authorization_no，天然幂等、无需额外存储；
 * 授权到期/关闭后同单号可复用（官方文档：未确认授权 30 天后原单号可再次发起）。
 *
 * <p>⚠️ <b>金额与商户号硬约束</b>：见 {@link #channelLimits()}。个体户主体口径更严，需改常量。
 */
@Component
public class WeChatPayoutChannel implements PayoutChannel {

    private static final Logger log = LoggerFactory.getLogger(WeChatPayoutChannel.class);

    private final WeChatPayProperties weChatPayProperties;
    private final WeChatTransferProperties transferProperties;
    private final WeChatTransferClient transferClient;

    public WeChatPayoutChannel(WeChatPayProperties weChatPayProperties,
                               WeChatTransferProperties transferProperties,
                               WeChatTransferClient transferClient) {
        this.weChatPayProperties = weChatPayProperties;
        this.transferProperties = transferProperties;
        this.transferClient = transferClient;
    }

    @Override
    public String channel() {
        return CabinetConstants.PAY_CHANNEL_WECHAT;
    }

    /**
     * 真实探测：支付凭证配齐 + 转账业务开关打开。
     *
     * <p>注意：这仍<b>不能</b>推断「用户已授权免确认」——那是逐用户的状态，
     * 由 {@link #transfer} 在打款时由微信侧校验（未授权会返回 USER_ERROR/ORDER_CLOSED）。
     */
    @Override
    public boolean isReady() {
        return weChatPayProperties.isConfigured() && transferClient.isConfigured();
    }

    @Override
    public String supportedAccountType() {
        return PayoutConstants.PAYEE_TYPE_PERSONAL;
    }

    @Override
    public PayoutChannelLimits channelLimits() {
        // 与商户号实际核准额度一致（2026-11-23 商户平台实测）：单笔 ¥200 / 单用户单日 ¥2000 / 单商户单日 ¥5 万
        return new PayoutChannelLimits(20_000L, 200_000L, 5_000_000L);
    }

    @Override
    public PayoutResult transfer(PayoutCommand command) {
        if (!isReady()) {
            return PayoutResult.notReady(channel(), "微信支付凭证 + 商家转账业务开关");
        }
        String openid = command.accountNo();
        if (openid == null || openid.isBlank()) {
            return PayoutResult.rejected("微信提现收款账户缺少 openid（请在收款账户中绑定微信）");
        }
        // 🔴 out_bill_no 形态前置校验（fail-closed）：微信只收数字/字母 6–32 位，
        //    不合规时微信回 PARAM_ERROR，而错误信息不会点名单号格式 ⇒ 排障来回好几轮。
        //    更要紧的是**绝不能「清洗后再发」**：清洗会让回调带回来的 out_bill_no 反查不回
        //    idem_key（幂等键/对账链断）。故键的形态在生成侧保证（newIdemKey），这里只做断言。
        if (!PayoutConstants.isChannelSafeBillNo(command.idemKey())) {
            String badKey = command.idemKey();
            log.error("wechat transfer blocked: illegal out_bill_no requestId={}, idemKey={}",
                    command.requestId(), badKey);
            return PayoutResult.rejected("幂等键「" + badKey
                    + "」不符合微信 out_bill_no 规范（仅数字/大小写字母、6–32 位）——"
                    + "请检查 PayoutAccountService.newIdemKey 的生成形态");
        }
        return transferProperties.confirmMode()
                ? transferConfirmMode(command, openid)
                : transferAuthorizedMode(command, openid);
    }

    /**
     * 用户确认收款模式（默认）：建单即锁定资金，等用户在微信里点「确认收款」。
     *
     * <p>返回 {@code awaitingUserConfirm} 而不是成功/失败——上层保持 PAYING，
     * 由 {@code /api/v2/payout/wechat/transfer-notify} 与定时查单收口。
     */
    private PayoutResult transferConfirmMode(PayoutCommand command, String openid) {
        try {
            WeChatTransferClient.ConfirmBillResponse bill = transferClient.createConfirmBill(
                    command.idemKey(), openid, command.netCents(), command.remark());
            if (bill.isSuccess()) {
                return PayoutResult.paid(bill.transferBillNo(), "微信商家转账成功（确认模式·即时到账）");
            }
            if (bill.isTerminalFail()) {
                return PayoutResult.rejected("微信转账终态=" + bill.state());
            }
            if (bill.awaitingUserConfirm()) {
                return PayoutResult.awaitingUserConfirm(bill.transferBillNo(), bill.packageInfo(),
                        "已发起转账，等待用户在小程序内确认收款（24 小时内有效）");
            }
            // 既非终态也非待确认（如 PROCESSING/TRANSFERING）⇒ 结果未定，保持 PAYING 查单收口
            throw new PayoutPendingException(
                    "微信转账非终态 state=" + bill.state() + "，保持 PAYING 待查单", command.idemKey());
        } catch (WeChatTransferClient.DefiniteFailure e) {
            return adjudicateDefiniteFailure(command, e);
        } catch (PayoutPendingException e) {
            throw e;
        } catch (PayoutChannel.PayeeIncompatibleException e) {
            throw e;
        } catch (RuntimeException e) {
            log.error("wechat transfer(bills) uncertain: requestId={}, idemKey={}, reason={}",
                    command.requestId(), command.idemKey(), e.getClass().getSimpleName());
            throw new PayoutPendingException("微信转账结果不确定（" + e.getClass().getSimpleName()
                    + "），保持 PAYING 转人工", command.idemKey());
        }
    }

    /** 用户授权免确认模式（白名单产品）：授权后同步返回终态，无需回调闭环。 */
    private PayoutResult transferAuthorizedMode(PayoutCommand command, String openid) {
        String outAuthorizationNo = authorizationNoFor(openid);
        try {
            WeChatTransferClient.TransferResponse response = transferClient.transfer(
                    command.idemKey(),          // 幂等键原样作商户单号——绝不换单重试
                    openid,                     // 仅日志/校验用；免确认转账按授权单号定位用户
                    command.netCents(),         // 金额由上游算好，通道不改
                    command.remark(),
                    outAuthorizationNo);
            if (response.isSuccess()) {
                return PayoutResult.paid(response.transferBillNo(), "微信商家转账成功（免确认）");
            }
            if (response.isTerminalFail()) {
                // 只有渠道明确的终态失败（FAIL / CANCELLED）才允许释放冻结
                return PayoutResult.rejected(response.describeFail()
                        .orElse("微信转账失败 state=" + response.state()));
            }
            // 其余一律非终态/未知（ACCEPTED / PROCESSING / WAIT_USER_CONFIRM / TRANSFERING /
            // CANCELING 及未知 state）：结果不确定，保持 PAYING（上层绝不释放冻结）。
            // 🔴 MA-409：此前这里对「非成功且不在在途白名单」的状态兜底 rejected
            //    ⇒ CANCELING 与任何未知 state 都被当失败释冻（方向错：不确定态必须兜底 Pending）。
            throw new PayoutPendingException(
                    "微信转账非终态 state=" + response.state() + "，保持 PAYING 待查单",
                    command.idemKey());
        } catch (WeChatTransferClient.DefiniteFailure e) {
            return adjudicateDefiniteFailure(command, e);
        } catch (PayoutPendingException e) {
            throw e;
        } catch (PayoutChannel.PayeeIncompatibleException e) {
            throw e;
        } catch (RuntimeException e) {
            // 网络超时/未知异常 ⇒ 结果不确定，绝不吞成失败（会释放冻结造成双重支出）
            log.error("wechat transfer uncertain: requestId={}, idemKey={}, reason={}",
                    command.requestId(), command.idemKey(), e.getClass().getSimpleName());
            throw new PayoutPendingException("微信转账结果不确定（" + e.getClass().getSimpleName() + "），保持 PAYING 转人工",
                    command.idemKey());
        }
    }

    /** 当前收款模式（运营台展示用）。 */
    public String mode() {
        return transferProperties.mode();
    }

    /** 确认收款模式 ⇒ 渠道一建单就锁资金，没有确认入口的调用方必须**建单前**拒掉。 */
    @Override
    public boolean requiresUserConfirmation() {
        return transferProperties.confirmMode();
    }

    /**
     * 「确定失败」的统一裁决口——只有 {@code ALREADY_EXISTS} 要特殊处理。
     *
     * <p>🔴 为什么不能直接 rejected：微信回 {@code ALREADY_EXISTS} 的语义是
     * 「这个 out_bill_no 在渠道侧<b>已经存在</b>」（典型是我们上一次请求其实受理了、只是回执丢了）。
     * 当「确定失败」处理 ⇒ 上层释放冻结、单据置 FAILED，而渠道那边这笔钱还能继续走或被用户确认
     * ⇒ 账实不符、极端情况重复出款。故必须**查单裁决**：
     * <ul>
     *   <li>查到且 SUCCESS ⇒ 按成功入账；</li>
     *   <li>查到且 FAIL/CANCELLED ⇒ 才判失败（钱确实没出去）；</li>
     *   <li>查不到 / 仍在途 / 查单本身失败 ⇒ 保持 PAYING 交定时查单与人工，绝不释放冻结。</li>
     * </ul>
     * 全程使用<b>原 out_bill_no</b>，不换单号（换单=重复出款）。
     */
    private PayoutResult adjudicateDefiniteFailure(PayoutCommand command,
                                                   WeChatTransferClient.DefiniteFailure e) {
        if (!e.alreadyExists()) {
            return PayoutResult.rejected(e.getMessage());
        }
        log.warn("wechat transfer ALREADY_EXISTS ⇒ 转查单裁决: requestId={}, outBillNo={}",
                command.requestId(), command.idemKey());
        WeChatTransferClient.QueryOutcome outcome;
        try {
            outcome = transferClient.queryOutcome(command.idemKey());
        } catch (RuntimeException qe) {
            throw new PayoutPendingException("渠道回报单据已存在但查单失败（"
                    + qe.getClass().getSimpleName() + "），保持 PAYING 转人工核对", command.idemKey());
        }
        if (outcome.found() && outcome.bill() != null) {
            WeChatTransferClient.TransferResponse bill = outcome.bill();
            if (bill.isSuccess()) {
                return PayoutResult.paid(bill.transferBillNo(), "微信转账成功（查单裁决：单据已存在且已到账）");
            }
            if (bill.isTerminalFail()) {
                return PayoutResult.rejected("微信转账终态=" + bill.state() + "（查单裁决）");
            }
            throw new PayoutPendingException("单据已存在但仍在途 state=" + bill.state()
                    + "，保持 PAYING 待查单", command.idemKey());
        }
        throw new PayoutPendingException("单据已存在但查不到明细（" + outcome.reason()
                + "），保持 PAYING 转人工核对", command.idemKey());
    }

    @Override
    public java.util.Optional<BillState> queryBill(String idemKey) {
        if (idemKey == null || idemKey.isBlank() || !isReady()) {
            return java.util.Optional.empty();
        }
        WeChatTransferClient.QueryOutcome outcome = transferClient.queryOutcome(idemKey);
        if (!outcome.found() || outcome.bill() == null) {
            return java.util.Optional.empty();
        }
        WeChatTransferClient.TransferResponse bill = outcome.bill();
        String state = bill.state();
        boolean success = bill.isSuccess();
        boolean failed = bill.isTerminalFail();
        String detail = bill.describeFail().orElse(state);
        return java.util.Optional.of(new BillState(state, success, failed,
                bill.transferBillNo(), detail));
    }

    /**
     * 由 openid 确定性派生商户侧授权单号（32 位内、纯字母数字）。
     * 公开供小程序授权页调用——授权页和转账必须用同一个单号，否则微信会当成两个授权。
     */
    public String authorizationNoFor(String openid) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(openid.getBytes(StandardCharsets.UTF_8));
            return "A" + HexFormat.of().formatHex(digest).substring(0, 24);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
