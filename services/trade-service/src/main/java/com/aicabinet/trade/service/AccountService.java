package com.aicabinet.trade.service;

import com.aicabinet.common.constants.CabinetConstants;
import com.aicabinet.common.constants.PayChannels;
import com.aicabinet.common.dto.AccountDto;
import com.aicabinet.common.dto.PayContractDto;
import com.aicabinet.common.dto.VerifyIdentityRequest;
import com.aicabinet.trade.domain.UserAccount;
import com.aicabinet.trade.domain.UserInfo;
import com.aicabinet.trade.identity.IdentityVerifyClient;
import com.aicabinet.trade.mapper.UserAccountMapper;
import com.aicabinet.trade.mapper.UserInfoMapper;
import com.aicabinet.trade.support.ApiMessages;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AccountService {

    private final UserInfoMapper userInfoRepository;
    private final UserAccountMapper userAccountRepository;
    private final PayScoreService payScoreService;
    private final BalanceLedgerService balanceLedgerService;
    private final DistributedLockService distributedLockService;
    private final IdentityVerifyClient identityVerifyClient;
    private final AccountService self;

    public AccountService(UserInfoMapper userInfoRepository,
                          UserAccountMapper userAccountRepository,
                          PayScoreService payScoreService,
                          BalanceLedgerService balanceLedgerService,
                          DistributedLockService distributedLockService,
                          IdentityVerifyClient identityVerifyClient,
                          @Lazy AccountService self) {
        this.userInfoRepository = userInfoRepository;
        this.userAccountRepository = userAccountRepository;
        this.payScoreService = payScoreService;
        this.balanceLedgerService = balanceLedgerService;
        this.distributedLockService = distributedLockService;
        this.identityVerifyClient = identityVerifyClient;
        this.self = self;
    }

    @Transactional(readOnly = true)
    public com.aicabinet.common.dto.PageResult<com.aicabinet.common.dto.BalanceTransactionDto> transactions(
            Long userId, int page, int size) {
        return balanceLedgerService.list(userId, page, size);
    }

    @Transactional(readOnly = true)
    public AccountDto getAccount(Long userId) {
        UserInfo user = userInfoRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.USER_NOT_FOUND));
        UserAccount account = userAccountRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.ACCOUNT_NOT_FOUND));
        boolean alipayReady = PayScoreService.isActiveAlipayAgreementId(user.getAlipayAgreementId());
        int frozen = Math.max(0, account.getFrozenCents());
        int available = Math.max(0, account.getBalanceCents() - frozen);
        return new AccountDto(
                userId,
                user.getPhoneNumber(),
                account.getBalanceCents(),
                frozen,
                available,
                user.isVerified(),
                userId >= CabinetConstants.OPERATOR_USER_ID_START,
                user.getPayPreferredChannel() != null ? user.getPayPreferredChannel() : PayChannels.BALANCE,
                user.isPayscoreEnabled(),
                alipayReady,
                payScoreService.isPasswordFreeReady(user)
        );
    }

    @Transactional
    public PayContractDto signWeChatPayScore(Long userId) {
        return runWithUserAccountLock(userId, () -> {
            String contractId = payScoreService.signWeChatPayScore(userId);
            return new PayContractDto(PayChannels.WECHAT, true, contractId, "微信支付分免密已开通，购物将优先免密扣款");
        });
    }

    @Transactional
    public PayContractDto signAlipayAgreement(Long userId) {
        return runWithUserAccountLock(userId, () -> {
            var result = payScoreService.signAlipayAgreement(userId);
            if (result.pending()) {
                return new PayContractDto(
                        PayChannels.ALIPAY,
                        false,
                        result.contractId(),
                        "请在支付宝内完成免密签约，签约成功后自动生效",
                        true,
                        result.signFormHtml());
            }
            return new PayContractDto(
                    PayChannels.ALIPAY,
                    result.active(),
                    result.contractId(),
                    "支付宝免密代扣已开通",
                    false,
                    null);
        });
    }

    /**
     * G9：**用户主动解约**（关闭免密代扣），返回刷新后的账户。
     *
     * <p>返回 {@code AccountDto} 而非签约流的 {@code PayContractDto} 是刻意的：解约必然同时改变
     * {@code payscoreEnabled / alipayAgreementEnabled / passwordFreeReady / payPreferredChannel}
     * 四个字段，回一份账户快照比回一句 message 更不容易让前端状态与后端漂移；
     * 惯例对齐同类的 {@link #setPayPreferredChannel}（同样写状态 + 回账户）。
     *
     * <p>幂等：没有已开通合约时也返回 200 + 当前账户，绝不 500；文案由前端按
     * {@code passwordFreeReady} 前后差异决定，避免后端与 UI 两处各写一套判断。
     */
    @Transactional
    public AccountDto cancelPasswordFree(Long userId) {
        return runWithUserAccountLock(userId, () -> {
            payScoreService.cancelPasswordFree(userId);
            return self.getAccount(userId);
        });
    }

    @Transactional
    public void bindWxOpenId(Long userId, String openId) {
        runWithUserAccountLock(userId, () -> {
            UserInfo user = requireUserForUpdate(userId);
            user.setWxOpenId(openId);
            userInfoRepository.save(user);
            return null;
        });
    }

    @Transactional
    public AccountDto verifyIdentity(Long userId, VerifyIdentityRequest request) {
        if (userId >= CabinetConstants.OPERATOR_USER_ID_START) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ApiMessages.INVALID_REQUEST);
        }
        return runWithUserAccountLock(userId, () -> doVerifyIdentity(userId, request));
    }

    private AccountDto doVerifyIdentity(Long userId, VerifyIdentityRequest request) {
        UserInfo user = requireUserForUpdate(userId);
        if (user.isVerified()) {
            return self.getAccount(userId);
        }
        identityVerifyClient.verify(request.realName(), request.idCardLast4());
        user.setName(request.realName().trim());
        user.setVerified(true);
        userInfoRepository.save(user);
        return self.getAccount(userId);
    }

    @Transactional
    public AccountDto setPayPreferredChannel(Long userId, String channel) {
        return runWithUserAccountLock(userId, () -> doSetPayPreferredChannel(userId, channel));
    }

    private AccountDto doSetPayPreferredChannel(Long userId, String channel) {
        UserInfo user = requireUserForUpdate(userId);
        String normalized = channel == null ? "" : channel.trim().toUpperCase();
        if (!PayChannels.BALANCE.equals(normalized)
                && !PayChannels.WECHAT.equals(normalized)
                && !PayChannels.ALIPAY.equals(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ApiMessages.INVALID_REQUEST);
        }
        // 🔴 就绪判据与「结算页显式选择」同源（PayScoreService.isChannelUsable）：
        // 否则会出现「API 允许设为优先、但真到扣款时又不可用」的自相矛盾。
        // 附带收紧了支付宝一侧：原先只判 agreementId 非空，PENDING 协议（尚未异步激活）也能设为优先，
        // 而 AccountDto.alipayAgreementEnabled（前端据此禁用选项）用的是 isActiveAlipayAgreementId ⇒ 前后端口径原本不一致。
        if (PayChannels.WECHAT.equals(normalized)
                && !PayScoreService.isChannelUsable(user, PayChannels.WECHAT)) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED, "请先开通微信支付分后再设为优先");
        }
        if (PayChannels.ALIPAY.equals(normalized)
                && !PayScoreService.isChannelUsable(user, PayChannels.ALIPAY)) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED, "请先开通支付宝免密后再设为优先");
        }
        user.setPayPreferredChannel(normalized);
        userInfoRepository.save(user);
        return self.getAccount(userId);
    }

    static String userAccountLockKey(Long userId) {
        return "user:account:" + userId;
    }

    private UserInfo requireUserForUpdate(Long userId) {
        return userInfoRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.USER_NOT_FOUND));
    }

    private <T> T runWithUserAccountLock(Long userId, java.util.function.Supplier<T> action) {
        String key = userAccountLockKey(userId);
        if (!distributedLockService.tryLock(key, 60, 5)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "账户处理中，请稍后重试");
        }
        try {
            return action.get();
        } finally {
            distributedLockService.unlock(key);
        }
    }
}
