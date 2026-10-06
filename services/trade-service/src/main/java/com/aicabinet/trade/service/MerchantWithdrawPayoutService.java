package com.aicabinet.trade.service;

import com.aicabinet.common.constants.CabinetConstants;
import com.aicabinet.trade.config.MerchantWithdrawProperties;
import com.aicabinet.trade.domain.Merchant;
import com.aicabinet.trade.domain.MerchantWithdrawRequest;
import com.aicabinet.trade.domain.PayoutAccount;
import com.aicabinet.trade.mapper.MerchantWalletLedgerMapper;
import com.aicabinet.trade.payout.PayoutChannel;
import com.aicabinet.trade.payout.PayoutChannelRegistry;
import com.aicabinet.trade.payout.PayoutConstants;
import com.aicabinet.trade.payout.PayoutFieldCipher;
import com.aicabinet.trade.util.BizIds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

/**
 * 商户提现打款：走统一通道抽象（{@link PayoutChannelRegistry}）。
 *
 * <p><b>V307 改造要点</b>：
 * <ul>
 *   <li>渠道从「mock 开关 → MOCK/WECHAT 字面量」改为<b>按收款账户的 channel 字段分派</b>
 *       —— 一个商户可同时配微信/支付宝/银行账户，提现时用哪个由账户决定，不再全局一刀切。</li>
 *   <li>真实出款时<b>解密账号</b>并按 {@link PayoutChannel.PayoutCommand} 传给通道；
 *       mock 模式<b>不解密</b>（避免无谓的密钥依赖，也让 mock 在未配密钥时也能跑）。</li>
 * </ul>
 *
 * <p>🔴 <b>三条不可绕过的约定</b>：
 * <ol>
 *   <li><b>mock 关闭且通道未就绪 ⇒ 必须失败</b>，不得「假装成功」（那等于凭空记账）。</li>
 *   <li>渠道抛 {@link PayoutChannel.PayoutPendingException} ⇒ <b>不得</b>当失败吞掉，
 *       必须向上传播让上层保持 PAYING 转人工（否则渠道已出款 + 本地释放冻结 = 双重支出）。</li>
 *   <li>幂等键用 {@link PayoutAccountService#newIdemKey} 生成并<b>原样传渠道</b>。</li>
 * </ol>
 */
@Service
public class MerchantWithdrawPayoutService {

    private static final Logger log = LoggerFactory.getLogger(MerchantWithdrawPayoutService.class);

    /** 幂等键前缀（商户提现）。 */
    static final String IDEM_PREFIX = "MW";

    private final MerchantWithdrawProperties properties;
    private final PayoutChannelRegistry channelRegistry;
    private final PayoutAccountService payoutAccountService;
    private final PayoutFieldCipher cipher;
    /** V308：费率解析（运营台 > yml），保证面板展示与实际执行同源。 */
    private final WithdrawPolicyResolver policy;
    /** V308：平台侧手续费收入汇总。 */
    private final MerchantWalletLedgerMapper ledgerMapper;

    public MerchantWithdrawPayoutService(MerchantWithdrawProperties properties,
                                         PayoutChannelRegistry channelRegistry,
                                         PayoutAccountService payoutAccountService,
                                         PayoutFieldCipher cipher,
                                         WithdrawPolicyResolver policy,
                                         MerchantWalletLedgerMapper ledgerMapper) {
        this.properties = properties;
        this.channelRegistry = channelRegistry;
        this.payoutAccountService = payoutAccountService;
        this.cipher = cipher;
        this.policy = policy;
        this.ledgerMapper = ledgerMapper;
    }

    /**
     * 向后兼容重载：无收款账户（V307 之前的调用方）。
     *
     * <p>行为：mock 下正常出（MOCK）；非 mock 下返回失败并说明「未绑定收款账户」。
     */
    public PayoutResult payout(MerchantWithdrawRequest request, Merchant merchant) {
        return payout(request, merchant, null);
    }

    /**
     * 发起打款。
     *
     * @param account 本次申请快照的收款账户（mock 模式下可为空 —— 老单无快照）
     */
    public PayoutResult payout(MerchantWithdrawRequest request, Merchant merchant, PayoutAccount account) {
        long netCents = WithdrawFeeCalculator.netPayoutCents(request.getAmountCents(), request.getFeeCents());

        // ============ 记账打款（mock）============
        // 🔴 mock 分支不解密账号：① 无谓的密钥依赖 ② 让 mock 在未配密钥时也能演示
        if (properties.mockEnabled()) {
            String ref = BizIds.nextNumeric();
            log.info("Mock merchant withdraw payout: requestId={}, merchantId={}, amountCents={}, "
                            + "feeCents={}, netCents={}, ref={}",
                    request.getRequestId(), merchant.getMerchantId(), request.getAmountCents(),
                    request.getFeeCents(), netCents, ref);
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
                    channel.channel() + " 提现通道未就绪（" + notReadyReason(channel, account) + "）");
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
                netCents, "提现 " + request.getRequestNo());

        try {
            PayoutChannel.PayoutResult result = channel.transfer(command);
            if (result.success()) {
                return PayoutResult.success(channel.channel(), result.channelOrderNo(), result.message());
            }
            return PayoutResult.failure(channel.channel(), result.channelOrderNo(), result.message());
        } catch (PayoutChannel.PayoutPendingException e) {
            // 🔴 结果不确定 ⇒ 绝不当失败（会释放冻结），直接传播给上层保持 PAYING
            log.error("merchant withdraw payout PENDING: requestId={}, channel={}, orderNo={}, reason={}",
                    request.getRequestId(), channel.channel(), e.channelOrderNo(), e.getMessage());
            throw e;
        }
    }

    /**
     * 运营后台展示打款模式，避免误以为已真实到账。
     *
     * 🔴 这是<b>运行期真值提示</b>，只换术语不弱化语义：不得删除或改弱，
     * 否则运营会把「标记成功」当成「钱已到账」。
     */
    public java.util.Map<String, Object> modeInfo() {
        boolean mock = properties.mockEnabled();
        long feeCents = properties.feeCents();
        long feeBps = properties.feeBps();
        // V308：费率/封顶改读 policy（运营台可运行期调），不再直读 yml ——
        // 否则面板显示的是「部署期初值」，而实际按「运营台当前值」执行，运营会被误导。
        long effectiveFlat = policy.merchantFeeCents();
        long effectiveBps = policy.merchantFeeBps();
        long effectiveCap = policy.merchantFeeCapCents();
        String feeNote = "；手续费=固定 " + effectiveFlat + " 分 + " + effectiveBps
                + " bps，单笔封顶 " + (effectiveCap > 0 ? effectiveCap + " 分" : "不封顶")
                + "（仅新申请写入 feeCents；已成立的提现单按快照费率不变）";
        String note;
        if (mock) {
            note = "当前为记账打款（未接入真实转账）：审核通过后标记为成功，不向任何渠道发起转账" + feeNote;
        } else {
            note = "记账打款已关闭：真实出款需各通道就绪（当前就绪状态见 channelReadiness）" + feeNote;
        }
        // ⚠️ 用 LinkedHashMap 显式构建而非 Map.of：Map.of 拒绝 null 值，
        //    且返回无序 map（前端展示顺序不稳）。这里是运行期真值面板，顺序有语义。
        java.util.Map<String, Object> info = new java.util.LinkedHashMap<>();
        info.put("mockEnabled", mock);
        info.put("accountEncryptionReady", cipher.isReady());
        info.put("maxAmountCents", policy.merchantMaxAmountCents());
        info.put("channelReadiness", channelRegistry.readiness());
        info.put("feeCents", effectiveFlat);
        info.put("feeBps", effectiveBps);
        info.put("feeCapCents", effectiveCap);
        // V308：平台侧手续费收入（当日/近 30 日累计）。这是「钱从哪来」的运营口径，
        // 也是与渠道流水对账的锚点——此前该数字在任何页面都查不到。
        info.put("feeIncomeTodayCents", feeIncomeSince(startOfToday()));
        info.put("feeIncomeLast30dCents", feeIncomeSince(Instant.now().minus(30, ChronoUnit.DAYS)));
        info.put("note", note);
        return info;
    }

    private long feeIncomeSince(Instant since) {
        if (ledgerMapper == null) {
            return 0L;
        }
        return ledgerMapper.sumAmountByEntryTypeSince("WITHDRAW_FEE", since);
    }

    private static Instant startOfToday() {
        return LocalDate.now(ZoneId.of("Asia/Shanghai")).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant();
    }

    private String notReadyReason(PayoutChannel channel, PayoutAccount account) {
        return switch (channel.channel()) {
            case CabinetConstants.PAY_CHANNEL_WECHAT ->
                    "需开通商家转账产品 + 配置收款用户 + 收款方须为已绑 openid";
            case CabinetConstants.PAY_CHANNEL_ALIPAY ->
                    "需开通商家转账产品 + 绑定收款支付宝用户";
            case PayoutConstants.PAY_CHANNEL_BANK ->
                    "需签订银行代付协议 + 开通对公代付接口";
            default ->
                    "通道 " + channel.channel() + " 未接入（账户类型=" + account.getAccountType() + "）";
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
