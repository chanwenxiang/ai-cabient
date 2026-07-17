package com.aicabinet.trade.service;

import com.aicabinet.common.constants.CabinetConstants;
import com.aicabinet.common.constants.PayChannels;
import com.aicabinet.trade.domain.UserAccount;
import com.aicabinet.trade.domain.UserInfo;
import com.aicabinet.trade.config.CheckoutProperties;
import com.aicabinet.trade.mapper.UserAccountMapper;
import com.aicabinet.trade.mapper.UserInfoMapper;
import com.aicabinet.trade.support.ApiMessages;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class UserValidationService {

    private final UserInfoMapper userInfoRepository;
    private final UserAccountMapper userAccountRepository;
    private final RiskControlService riskControlService;
    private final PayScoreService payScoreService;
    private final CheckoutProperties checkoutProperties;

    public UserValidationService(UserInfoMapper userInfoRepository,
                                 UserAccountMapper userAccountRepository,
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
        validateCanOpenDoor(userId, null, null);
    }

    public void validateCanOpenDoor(Long userId, String deviceId) {
        validateCanOpenDoor(userId, deviceId, null);
    }

    public void validateCanOpenDoor(Long userId, String deviceId, String entryChannel) {
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

        // 真实业务：优先按扫码渠道的免密能力开门；余额仅兜底
        if (!checkoutProperties.balanceOnly()
                && payScoreService.isPasswordFreeReadyForChannel(user, entryChannel)) {
            return;
        }

        UserAccount account = userAccountRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.ACCOUNT_NOT_FOUND));
        if (account.getBalanceCents() < CabinetConstants.MIN_BALANCE_CENTS) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED, ApiMessages.BALANCE_TOO_LOW);
        }
    }

    /** 结算前：若已可按渠道免密扣款，则不强制要求余额覆盖订单金额。 */
    public boolean canChargeViaPasswordFree(Long userId, String entryChannel) {
        if (userId == null || userId >= CabinetConstants.OPERATOR_USER_ID_START) {
            return true;
        }
        if (checkoutProperties.balanceOnly()) {
            return false;
        }
        UserInfo user = userInfoRepository.findById(userId).orElse(null);
        return payScoreService.isPasswordFreeReadyForChannel(user, entryChannel);
    }

    /** 结算前校验可用余额是否覆盖订单金额（运营账号跳过）。 */
    public void validateSufficientBalanceForCharge(Long userId, int amountCents) {
        if (userId == null || userId >= CabinetConstants.OPERATOR_USER_ID_START || amountCents <= 0) {
            return;
        }
        UserAccount account = userAccountRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.ACCOUNT_NOT_FOUND));
        if (account.getBalanceCents() < amountCents) {
            throw new BalanceInsufficientException(ApiMessages.INSUFFICIENT_BALANCE);
        }
    }
}
