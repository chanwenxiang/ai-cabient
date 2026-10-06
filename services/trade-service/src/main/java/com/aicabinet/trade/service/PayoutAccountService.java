package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.PayoutAccount;
import com.aicabinet.trade.domain.PayoutAccountStatus;
import com.aicabinet.trade.mapper.LineWithdrawRequestMapper;
import com.aicabinet.trade.mapper.MerchantWithdrawRequestMapper;
import com.aicabinet.trade.mapper.PayoutAccountMapper;
import com.aicabinet.trade.payout.PayoutChannel;
import com.aicabinet.trade.payout.PayoutChannelRegistry;
import com.aicabinet.trade.payout.PayoutConstants;
import com.aicabinet.trade.payout.PayoutFieldCipher;
import com.aicabinet.trade.util.BizIds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 收款账户管理：新增/查询/设默认/停用。
 *
 * <p>🔴 <b>三条铁律</b>（改本类前先看）：
 * <ol>
 *   <li><b>明文只进不出</b>：写入时加密、返回时只给掩码。唯一解密出口是
 *       {@link #resolveForPayout}，仅供发起打款时使用。</li>
 *   <li><b>默认账户唯一</b>：由 {@code uk_payout_account_default} 部分唯一索引保证，
 *       本类通过 {@link PayoutAccountMapper#switchDefault} 做常态路径。</li>
 *   <li><b>停用不影响历史</b>：停用只拦「新申请」，已成立的提现单已有快照，照常打款。</li>
 * </ol>
 */
@Service
public class PayoutAccountService {

    private static final Logger log = LoggerFactory.getLogger(PayoutAccountService.class);

    /** 对公开户行：中文/英文/空格/括号，长度 2-128 */
    private static final Pattern BANK_NAME_RE = Pattern.compile("^[\\\\u4e00-\\\\u9fa5A-Za-z0-9 ()（）\\\\-]{2,128}$");
    /** 纳税人识别号：15/18/20 位字母数字 */
    private static final Pattern TAX_NO_RE = Pattern.compile("^[A-Za-z0-9]{15,20}$");
    /** 银行账号：6-32 位数字（对公可能带 * 掩码标记，但我们只存真号） */
    private static final Pattern ACCOUNT_NO_RE = Pattern.compile("^[0-9]{6,32}$");

    private final PayoutAccountMapper accountMapper;
    private final PayoutFieldCipher cipher;
    private final PayoutChannelRegistry channelRegistry;
    /** V308：跨主体额度统计（商户侧 + 线长侧共用渠道池）。 */
    private final MerchantWithdrawRequestMapper withdrawMapper;
    private final LineWithdrawRequestMapper lineWithdrawMapper;

    public PayoutAccountService(PayoutAccountMapper accountMapper,
                                PayoutFieldCipher cipher,
                                PayoutChannelRegistry channelRegistry,
                                MerchantWithdrawRequestMapper withdrawMapper,
                                LineWithdrawRequestMapper lineWithdrawMapper) {
        this.accountMapper = accountMapper;
        this.cipher = cipher;
        this.channelRegistry = channelRegistry;
        this.withdrawMapper = withdrawMapper;
        this.lineWithdrawMapper = lineWithdrawMapper;
    }

    /**
     * 新增收款账户。
     *
     * @param accountNo 明文账号，<b>方法内立即加密，调用方不得再持有明文</b>
     */
    public PayoutAccount create(String ownerType, String ownerId,
                                String accountType, String channel, String accountName,
                                String accountNo, String bankName, String bankBranch, String taxNo,
                                boolean asDefault, Long operatorId) {
        validateOwner(ownerType, ownerId);
        requireValidPayeeType(accountType);
        requireRegisteredChannel(channel);
        validateFields(accountType, accountName, accountNo, bankName, taxNo);

        PayoutAccount account = new PayoutAccount();
        account.setOwnerType(ownerType);
        account.setOwnerId(ownerId.trim());
        account.setAccountType(accountType);
        account.setChannel(channel);
        account.setAccountName(accountName.trim());
        account.setAccountNo(cipher.encrypt(accountNo.trim()));
        account.setAccountNoMask(cipher.mask(accountNo.trim()));
        account.setBankName(trimOrNull(bankName));
        account.setBankBranch(trimOrNull(bankBranch));
        account.setTaxNo(trimOrNull(taxNo));
        account.setStatus(PayoutAccountStatus.ACTIVE);
        account.setVersion(0);
        account.setCreatedBy(operatorId);
        account.setCreatedAt(Instant.now());
        account.setUpdatedAt(Instant.now());
        // 首个账户强制为默认：否则主体永远没有默认账户，提现会在申请时找不到收款方
        boolean shouldBeDefault = asDefault
                || accountMapper.findActiveByOwner(ownerType, ownerId).isEmpty();
        account.setIsDefault(shouldBeDefault);
        accountMapper.insert(account);

        if (shouldBeDefault) {
            accountMapper.switchDefault(ownerType, ownerId, accountType, account.getAccountId());
        }
        // 🔴 日志只记 accountId + mask，绝不记明文账号
        log.info("payout account created: accountId={}, ownerType={}, ownerId={}, type={}, channel={}, mask={}",
                account.getAccountId(), ownerType, ownerId, accountType, channel, account.getAccountNoMask());
        return account;
    }

    public List<PayoutAccount> listByOwner(String ownerType, String ownerId) {
        return accountMapper.findByOwner(ownerType, ownerId);
    }

    /** 设为该主体该类型的默认账户。 */
    public void setDefault(String ownerType, String ownerId, long accountId) {
        PayoutAccount account = requireOwned(ownerType, ownerId, accountId);
        if (!account.isActive()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "已停用的账户不能设为默认");
        }
        accountMapper.switchDefault(ownerType, ownerId, account.getAccountType(), accountId);
    }

    /**
     * 停用账户。
     *
     * <p><b>不做物理删除</b>：历史提现单靠快照展示收款方，删账户会让已打款记录失去可追溯性。
     */
    public void disable(String ownerType, String ownerId, long accountId, Long operatorId) {
        PayoutAccount account = requireOwned(ownerType, ownerId, accountId);
        Instant now = Instant.now();
        account.setStatus(PayoutAccountStatus.DISABLED);
        account.setIsDefault(false);
        account.setUpdatedAt(now);
        account.setReviewedBy(operatorId);
        accountMapper.updateById(account);
        log.info("payout account disabled: accountId={}, ownerType={}, ownerId={}, operator={}",
                accountId, ownerType, ownerId, operatorId);
    }

    /**
     * 取提现申请该用的收款账户：指定则用指定的，否则用默认账户。
     *
     * <p>🔴 本方法<b>不解密</b>，返回的是含密文的实体 —— 上层需要的是掩码快照。
     * 真正打款走 {@link #resolveForPayout}。
     */
    public PayoutAccount resolveForApply(String ownerType, String ownerId, Long accountId) {
        PayoutAccount account = accountId == null
                ? accountMapper.findDefault(ownerType, ownerId)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.PRECONDITION_FAILED,
                                "未配置收款账户，请先在「收款账户」中配置对公/对私账户后再提现"))
                : requireOwned(ownerType, ownerId, accountId);

        if (!account.isActive()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "所选收款账户已停用，请另选一个");
        }
        // 通道自洽性：选 WECHAT 却配了对公税号账户 ⇒ 打款必失败，早拦
        PayoutChannel channel = channelRegistry.find(account.getChannel()).orElse(null);
        if (channel == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "收款账户的打款通道未注册：" + account.getChannel());
        }
        if (PayoutConstants.isCompany(account.getAccountType())
                && !PayoutConstants.isCompany(channel.supportedAccountType())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    account.getChannel() + " 通道不支持对公收款（对公需走银行代付），请改用银行账户");
        }
        return account;
    }

    /**
     * 取<b>含明文账号</b>的账户，仅供发起打款。
     *
     * <p>🔴 调用方禁止把返回值打进日志/返回给前端/落审计日志。
     * 这是全系统唯一允许解密的地方。
     */
    public ResolvedPayee resolveForPayout(PayoutAccount account) {
        ResolvedPayee payee = new ResolvedPayee();
        payee.accountId = account.getAccountId();
        payee.accountType = account.getAccountType();
        payee.accountName = account.getAccountName();
        payee.channel = account.getChannel();
        payee.bankName = account.getBankName();
        payee.bankBranch = account.getBankBranch();
        payee.bankCode = account.getBankCode();
        payee.bankProvinceCity = account.getBankProvinceCity();
        payee.taxNo = account.getTaxNo();
        payee.accountNoMask = account.getAccountNoMask();
        payee.accountNoPlain = cipher.decrypt(account.getAccountNo());
        return payee;
    }

    /** 幂等键：同一笔提现单复用（重试打款不换键 ⇒ 渠道侧不重复出款）。 */
    public static String newIdemKey(String prefix, long requestId) {
        return prefix + ":" + requestId + ":" + BizIds.nextNumeric();
    }

    /**
     * V308：某收款账户在 {@code since} 之后已占用的当日额度（分）—— <b>跨商户与线长两侧</b>。
     *
     * <p>🔴 <b>为什么要跨主体</b>：微信「单用户单日 ¥2000」限制的是
     * <b>单商户号向同一用户</b>的转账总额。线长与商户是<b>不同主体、不同收款账户</b>，
     * 各查各的不会漏。但若<b>同一收款账户</b>被两个主体共用（运营配错、或
     * 线长账户与商户账户指向同一人），只查单侧就会漏算 ⇒ 绕过限额。
     * 故这里<b>按账户</b>聚合而非按主体。
     *
     * <p>口径与 {@code MerchantWithdrawRequestMapper.sumAmountByPayeeSince} 一致
     * （非终态集合 + {@code createdAt} 当日），改一处必须同时改另一处。
     */
    public long sumPaidAmountByAccountSince(long accountId, Instant since) {
        return withdrawMapper.sumAmountByPayeeSince(accountId, since);
    }

    /**
     * V308：某打款通道当日已出/待出总额（分）—— <b>全平台跨主体</b>。
     *
     * <p>🔴 这是<b>商户提现 + 线长提现共用</b>的池子：微信的 5 万/日限制的是
     * <b>整个商户号</b>，不是「每主体各一份」。按主体分别统计会严重高估可用额度 ——
     * 5 个商户各提 4 万都能过单侧校验，合计 20 万早已超渠道上限。
     *
     * <p>⚠️ 商户侧与线长侧<b>都必须走本方法</b>，不可各自查自己那张表 ——
     * 两侧各查各的等于把池子算了两遍，限额形同虚设。
     */
    public long sumPaidAmountByChannelSince(String channel, Instant since) {
        if (channel == null) {
            return 0L;
        }
        return withdrawMapper.sumAmountByChannelSince(channel, since)
                + lineWithdrawMapper.sumAmountByChannelSince(channel, since);
    }

    // ============ 校验 ============

    private void validateFields(String accountType, String accountName, String accountNo,
                                String bankName, String taxNo) {
        if (isBlank(accountName) || accountName.trim().length() > 128) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "户名必填且不超过 128 字");
        }
        if (isBlank(accountNo)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "收款账号必填");
        }
        String no = accountNo.trim();
        if (!ACCOUNT_NO_RE.matcher(no).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "收款账号格式不正确（应为 6-32 位数字，不含空格与连字符）");
        }
        if (!PayoutConstants.isCompany(accountType)) {
            // 对私：银行信息可空（微信/支付宝零钱不需要）
            return;
        }
        // 对公：开户行 + 税号硬性要求（缺了到渠道才被拒，届时钱已冻结、状态已 PAYING）
        if (isBlank(bankName) || !BANK_NAME_RE.matcher(bankName.trim()).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "对公收款必须填开户银行全称（如「中国工商银行杭州西湖支行」）");
        }
        if (isBlank(taxNo) || !TAX_NO_RE.matcher(taxNo.trim()).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "对公收款必须填纳税人识别号（15-20 位字母数字）");
        }
    }

    private void validateOwner(String ownerType, String ownerId) {
        if (isBlank(ownerType) || isBlank(ownerId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "收款主体无效");
        }
    }

    private void requireValidPayeeType(String accountType) {
        if (!PayoutConstants.isValidPayeeType(accountType)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "收款账户类型只能是 " + PayoutConstants.PAYEE_TYPE_COMPANY
                            + "（对公）或 " + PayoutConstants.PAYEE_TYPE_PERSONAL + "（对私）");
        }
    }

    private void requireRegisteredChannel(String channel) {
        if (channelRegistry.find(channel).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "打款通道未注册：" + channel);
        }
    }

    private PayoutAccount requireOwned(String ownerType, String ownerId, long accountId) {
        PayoutAccount account = accountMapper.findByAccountId(accountId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "收款账户不存在"));
        if (!ownerType.equals(account.getOwnerType()) || !ownerId.trim().equals(account.getOwnerId())) {
            // 🔴 跨主体访问一律 404（不泄露「该账户存在」）
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "收款账户不存在");
        }
        return account;
    }

    private static boolean isBlank(String v) {
        return v == null || v.isBlank();
    }

    private static String trimOrNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }

    /**
     * 打款用收款方（含明文账号）。
     *
     * <p>🔴 本对象携带明文账号 ⇒ <b>禁止</b>打日志、禁止直接返回前端、禁止进审计备注。
     */
    public static final class ResolvedPayee {
        public Long accountId;
        public String accountType;
        public String accountName;
        public String channel;
        public String bankName;
        public String bankBranch;
        /** V309：联行号（可选）—— 银行代付路由用，见 {@link PayoutAccount#getBankCode()} */
        public String bankCode;
        /** V309：开户行省市（可选） */
        public String bankProvinceCity;
        public String taxNo;
        public String accountNoMask;
        /** 明文账号 —— 仅传递给 {@link PayoutChannel} */
        public String accountNoPlain;

        @Override
        public String toString() {
            // 刻意不输出 accountNoPlain / taxNo
            return "ResolvedPayee{accountId=" + accountId + ", channel=" + channel
                    + ", type=" + accountType + ", mask=" + accountNoMask + "}";
        }
    }
}
