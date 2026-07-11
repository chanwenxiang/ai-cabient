package com.aicabinet.trade.service;

import com.aicabinet.common.constants.CabinetConstants;
import com.aicabinet.trade.domain.UserAccount;
import com.aicabinet.trade.domain.UserInfo;
import com.aicabinet.trade.config.CheckoutProperties;
import com.aicabinet.trade.repository.UserAccountRepository;
import com.aicabinet.trade.repository.UserInfoRepository;
import com.aicabinet.trade.support.ApiMessages;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class UserValidationService {

    private final UserInfoRepository userInfoRepository;
    private final UserAccountRepository userAccountRepository;
    private final RiskControlService riskControlService;
    private final PayScoreService payScoreService;
    private final CheckoutProperties checkoutProperties;

    public UserValidationService(UserInfoRepository userInfoRepository,
                                 UserAccountRepository userAccountRepository,
                                 RiskControlService riskControlService,
                                 PayScoreService payScoreService,
                                 CheckoutProperties checkoutProperties) {
        this.userInfoRepository = userInfoRepository;
        this.userAccountRepository = userAccountRepository;
        this.riskControlService = riskControlService;
        this.payScoreService = payScoreService;
        this.checkoutProperties = checkoutProperties;
    }

    /**
     * 参考旧 M8MachineService.openDoor 前置校验。
     * 运营账号 (userId >= 100000000) 跳过实名和余额检查。
     */
    public void validateCanOpenDoor(Long userId) {
        validateCanOpenDoor(userId, null);
    }

    public void validateCanOpenDoor(Long userId, String deviceId) {
        if (userId == null || userId <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ApiMessages.INVALID_REQUEST);
        }
        if (userId >= CabinetConstants.OPERATOR_USER_ID_START) {
            return;
        }

        riskControlService.validateCanOpenDoor(userId, deviceId);

        UserInfo user = userInfoRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.USER_NOT_FOUND));
        if (!user.isVerified()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, ApiMessages.USER_NOT_VERIFIED);
        }

        if (!checkoutProperties.balanceOnly() && payScoreService.isPasswordFreeReady(user)) {
            return;
        }

        UserAccount account = userAccountRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.ACCOUNT_NOT_FOUND));
        if (account.getBalanceCents() < CabinetConstants.MIN_BALANCE_CENTS) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED, ApiMessages.BALANCE_TOO_LOW);
        }
    }
}
