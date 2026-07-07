package com.aicabinet.trade.service;

import com.aicabinet.common.constants.CabinetConstants;
import com.aicabinet.common.dto.AccountDto;
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

    public AccountService(UserInfoRepository userInfoRepository,
                          UserAccountRepository userAccountRepository) {
        this.userInfoRepository = userInfoRepository;
        this.userAccountRepository = userAccountRepository;
    }

    @Transactional(readOnly = true)
    public AccountDto getAccount(Long userId) {
        UserInfo user = userInfoRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.USER_NOT_FOUND));
        UserAccount account = userAccountRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.ACCOUNT_NOT_FOUND));
        return new AccountDto(
                userId,
                user.getPhoneNumber(),
                account.getBalanceCents(),
                user.isVerified(),
                userId >= CabinetConstants.OPERATOR_USER_ID_START
        );
    }

    @Transactional
    public void bindWxOpenId(Long userId, String openId) {
        UserInfo user = userInfoRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.USER_NOT_FOUND));
        user.setWxOpenId(openId);
        userInfoRepository.save(user);
    }
}
