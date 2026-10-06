package com.aicabinet.trade.service;
import com.aicabinet.common.constants.CabinetConstants;

import com.aicabinet.trade.config.LineWithdrawProperties;
import com.aicabinet.trade.domain.LineManager;
import com.aicabinet.trade.domain.LineWithdrawRequest;
import com.aicabinet.trade.domain.PayoutAccount;
import com.aicabinet.trade.payout.PayoutChannel;
import com.aicabinet.trade.payout.PayoutChannelRegistry;
import com.aicabinet.trade.payout.PayoutConstants;
import com.aicabinet.trade.payout.PayoutFieldCipher;
import com.aicabinet.trade.util.BizIds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;

/**
 * 线长提现打款：走统一通道抽象（{@link PayoutChannelRegistry}），V308 起与商户侧<b>同构</b>。
 *
 * <p><b>V308 改造要点</b>（修的是「线长侧与商户侧两套打款逻辑」这个结构性问题）：
 * <ul>
 *   <li>渠道从「{@code WithdrawPayoutPolicy.channelFor(mock)}」的硬编码
 *       改为<b>按收款账户的 channel 字段分派</b> —— 线长可按账户走 WECHAT / ALIPAY / BANK；</li>
 *   <li>真实出款时<b>解密账号</b>后传给通道（快照里只有掩码，<b>不能</b>拿掩码去出款）；</li>
 *   <li>幂等键用 {@link PayoutAccountService#newIdemKey} 生成并原样传渠道。</li>
 * </ul>
 *
 * <p>🔴 <b>与商户侧同构的 4 条约定</b>（改本类前先看）：
 * <ol>
 *   <li><b>mock 关闭且通道未就绪 ⇒ 必须失败</b>，不得「假装成功」；</li>
 *   <li>渠道抛 {@link PayoutChannel.PayoutPendingException} ⇒ <b>不得</b>当失败吞掉，
 *       必须向上传播让上层保持 PAYING 转人工（否则渠道已出款 + 本地释放冻结 = 双重支出）；</li>
 *   <li><b>不按快照内容出款</b>：快照列只用于展示与对账，真实出款必须解密账户密文；</li>
 *   <li>无收款账户（V308 前的存量单）⇒ <b>明确失败</b>并提示重提，不静默降级。</li>
 * </ol>
 *
 * <p>⚠️ <b>为什么线长侧默认是「对私微信」而商户侧是「对公银行」</b>：线长的钱来自
 * <b>佣金分成</b>、主体是<b>自然人</b>；商户是<b>法人主体</b>。这不是配置差异而是
 * 业务事实差异，硬套会出现「给自然人打对公代付」这种渠道侧必拒的组合。
 */
@Service
public class LineWithdrawPayoutService {

    private static final Logger log = LoggerFactory.getLogger(LineWithdrawPayoutService.class);

    /** 幂等键前缀（线长提现）。与商户侧 {@code MW} 区分，便于渠道侧排障时一眼识别来源。 */
    static final String IDEM_PREFIX = "LW";

    private final LineWithdrawProperties properties;
    private final PayoutChannelRegistry channelRegistry;
    private final PayoutAccountService payoutAccountService;
    private final PayoutFieldCipher cipher;
    private final WithdrawPolicyResolver policy;

    public LineWithdrawPayoutService(LineWithdrawProperties properties,
                                     PayoutChannelRegistry channelRegistry,
                                     PayoutAccountService payoutAccountService,
                                     PayoutFieldCipher cipher,
                                     WithdrawPolicyResolver policy) {
        this.properties = properties;
        this.channelRegistry = channelRegistry;
        this.payoutAccountService = payoutAccountService;
        this.cipher = cipher;
        this.policy = policy;
    }

    /** 向前兼容重载：无收款账户（V308 之前的调用方/存量单）。 */
    public PayoutResult payout(LineWithdrawRequest request, LineManager manager) {
        return payout(request, manager, null);
    }

    /**
     * 发起打款。
     *
     * @param account 本次申请快照的收款账户（V308 前的存量单为 null ⇒ 明确失败）
     */
    public PayoutResult payout(LineWithdrawRequest request, LineManager manager, PayoutAccount account) {
        long feeCents = request.getFeeCents() == null ? 0L : request.getFeeCents();
        long netCents = WithdrawFeeCalculator.netPayoutCents(request.getAmountCents(), feeCents);

        // ============ 记账打款（mock）============
        // 🔴 mock 分支不解密账号：① 无谓的密钥依赖 ② 让 mock 在未配密钥时也能演示
        if (properties.mockEnabled()) {
            String ref = BizIds.nextNumeric();
            log.info("Mock line withdraw payout: requestId={}, managerId={}, amountCents={}, "
                            + "feeCents={}, netCents={}, ref={}",
                    request.getRequestId(), manager.getManagerId(), request.getAmountCents(),
                    feeCents, netCents, ref);
            return PayoutResult.success("MOCK", ref,
                    "仅 Mock 成功（记账打款，未发起真实转账），净额 " + netCents + " 分");
        }

        // ============ 真实出款 ============
        if (account == null) {
            return PayoutResult.failure(null, null,
                    "未绑定收款账户，无法出款（历史提现单无收款方快照，请重新申请或在账户管理中补配）");
        }
        PayoutChannel channel = channelRegistry.find(account.getChannel())
                .orElseThrow(() -> new PayoutChannel.PayeeIncompatibleException(
                        "未注册的打款通道：" + account.getChannel()));
        if (!channel.isReady()) {
            return PayoutResult.failure(channel.channel(), null,
                    channel.channel() + " 提现通道未就绪（" + notReadyReason(channel) + "）");
        }
        if (!cipher.isReady()) {
            // fail-closed：渠道就绪但本地解不开账号 ⇒ 绝不能「跳过解密照发」，也不能当成功
            return PayoutResult.failure(channel.channel(), null,
                    "收款账号解密密钥未配置（AICABINET_PAYOUT_ENCRYPTION_KEY），拒绝出款");
        }

        PayoutAccountService.ResolvedPayee payee = payoutAccountService.resolveForPayout(account);
        String idemKey = request.getIdemKey() != null && !request.getIdemKey().isBlank()
                ? request.getIdemKey()
                : PayoutAccountService.newIdemKey(IDEM_PREFIX, request.getRequestId());

        PayoutChannel.PayoutCommand command = new PayoutChannel.PayoutCommand(
                request.getRequestId(), idemKey,
                payee.accountType, payee.accountName, payee.accountNoPlain,
                payee.bankName, payee.bankBranch, payee.bankCode, payee.bankProvinceCity, payee.taxNo,
                netCents, "线长提现 " + request.getRequestNo());

        try {
            PayoutChannel.PayoutResult result = channel.transfer(command);
            if (result.success()) {
                return PayoutResult.success(channel.channel(), result.channelOrderNo(), result.message());
            }
            return PayoutResult.failure(channel.channel(), result.channelOrderNo(), result.message());
        } catch (PayoutChannel.PayoutPendingException e) {
            // 🔴 结果不确定 ⇒ 绝不当失败（会释放冻结），直接传播给上层保持 PAYING
            log.error("line withdraw payout PENDING: requestId={}, channel={}, orderNo={}, reason={}",
                    request.getRequestId(), channel.channel(), e.channelOrderNo(), e.getMessage());
            throw e;
        }
    }

    /**
     * 运营后台展示打款模式，避免误以为已真实到账。
     *
     * 🔴 这是<b>运行期真值提示</b>，只换术语不弱化语义：不得删除或改弱，
     * 否则运营会把「标记成功」当成「钱已到账」。费率改读 {@code policy}（运营台可运行期调），
     * 否则面板显示的是「部署期初值」而实际按「运营台当前值」执行，运营会被误导。
     */
    public java.util.Map<String, Object> modeInfo() {
        boolean mock = properties.mockEnabled();
        long effectiveFlat = policy.lineFeeCents();
        long effectiveBps = policy.lineFeeBps();
        long effectiveCap = policy.lineFeeCapCents();
        String feeNote = "；手续费=固定 " + effectiveFlat + " 分 + " + effectiveBps
                + " bps，单笔封顶 " + (effectiveCap > 0 ? effectiveCap + " 分" : "不封顶")
                + "（仅新申请写入 feeCents；已成立的提现单按快照费率不变）";
        String note;
        if (mock) {
            note = "当前为记账打款（未接入真实转账）：审核通过后标记为成功，不向任何渠道发起转账" + feeNote;
        } else {
            note = "记账打款已关闭：真实出款需各通道就绪（当前就绪状态见 channelReadiness）" + feeNote;
        }
        // ⚠️ 用 LinkedHashMap 显式构建而非 Map.of：Map.of 拒绝 null 值，且返回无序 map
        //    （前端展示顺序不稳）。这里是运行期真值面板，顺序有语义。
        java.util.Map<String, Object> info = new LinkedHashMap<>();
        info.put("mockEnabled", mock);
        info.put("accountEncryptionReady", cipher.isReady());
        info.put("maxAmountCents", policy.lineMaxAmountCents());
        // V308：与商户侧同构 —— 通道就绪态与限额从统一注册表取，不再各写一份字面量
        info.put("channelReadiness", channelRegistry.readiness());
        info.put("feeCents", effectiveFlat);
        info.put("feeBps", effectiveBps);
        info.put("feeCapCents", effectiveCap);
        info.put("note", note);
        return info;
    }

    private String notReadyReason(PayoutChannel channel) {
        return switch (channel.channel()) {
            case CabinetConstants.PAY_CHANNEL_WECHAT ->
                    "需开通商家转账产品 + 配置收款用户 + 收款方须为已绑 openid";
            case CabinetConstants.PAY_CHANNEL_ALIPAY ->
                    "需开通商家转账产品 + 绑定收款支付宝用户";
            case PayoutConstants.PAY_CHANNEL_BANK ->
                    "需签订银行代付协议 + 开通对公代付接口";
            default ->
                    "通道 " + channel.channel() + " 未接入";
        };
    }

    public record PayoutResult(
            boolean success,
            String payChannel,
            String payoutRef,
            String message
    ) {
        static PayoutResult success(String channel, String ref, String message) {
            return new PayoutResult(true, channel, ref, message);
        }

        static PayoutResult failure(String channel, String ref, String message) {
            return new PayoutResult(false, channel, ref, message);
        }
    }
}
