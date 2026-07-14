package com.aicabinet.trade.service;

import com.aicabinet.common.constants.CabinetConstants;
import com.aicabinet.common.constants.PayChannels;
import com.aicabinet.common.dto.AccountDto;
import com.aicabinet.common.dto.PayContractDto;
import com.aicabinet.common.dto.VerifyIdentityRequest;
import com.aicabinet.trade.domain.UserAccount;
import com.aicabinet.trade.domain.UserInfo;
import com.aicabinet.trade.repository.UserAccountRepository;
import com.aicabinet.trade.repository.UserInfoRepository;
import com.aicabinet.trade.support.ApiMessages;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AccountService {

    private final UserInfoRepository userInfoRepository;
    private final UserAccountRepository userAccountRepository;
    private final PayScoreService payScoreService;
    private final BalanceLedgerService balanceLedgerService;

    public AccountService(UserInfoRepository userInfoRepository,
                          UserAccountRepository userAccountRepository,
                          PayScoreService payScoreService,
                          BalanceLedgerService balanceLedgerService) {
        this.userInfoRepository = userInfoRepository;
        this.userAccountRepository = userAccountRepository;
        this.payScoreService = payScoreService;
        this.balanceLedgerService = balanceLedgerService;
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
        boolean alipayReady = user.getAlipayAgreementId() != null && !user.getAlipayAgreementId().isBlank();
        return new AccountDto(
                userId,
                user.getPhoneNumber(),
                account.getBalanceCents(),
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
        String contractId = payScoreService.signWeChatPayScore(userId);
        return new PayContractDto(PayChannels.WECHAT, true, contractId, "微信支付分免密已开通，购物将优先免密扣款");
    }

    @Transactional
    public PayContractDto signAlipayAgreement(Long userId) {
        String agreementId = payScoreService.signAlipayAgreement(userId);
        return new PayContractDto(PayChannels.ALIPAY, true, agreementId, "支付宝免密代扣已开通");
    }

    @Transactional
    public void bindWxOpenId(Long userId, String openId) {
        UserInfo user = userInfoRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.USER_NOT_FOUND));
        user.setWxOpenId(openId);
        userInfoRepository.save(user);
    }

    @Transactional
    public AccountDto verifyIdentity(Long userId, VerifyIdentityRequest request) {
        if (userId >= CabinetConstants.OPERATOR_USER_ID_START) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ApiMessages.INVALID_REQUEST);
        }
        UserInfo user = userInfoRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.USER_NOT_FOUND));
        if (user.isVerified()) {
            return getAccount(userId);
        }
        user.setName(request.realName().trim());
        user.setVerified(true);
        userInfoRepository.save(user);
        return getAccount(userId);
    }
}
