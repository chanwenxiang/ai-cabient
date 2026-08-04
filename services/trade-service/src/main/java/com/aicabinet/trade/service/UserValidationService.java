package com.aicabinet.trade.service;

import com.aicabinet.common.constants.CabinetConstants;
import com.aicabinet.trade.domain.DeviceInfo;
import com.aicabinet.trade.domain.MerchantOpsConfig;
import com.aicabinet.trade.domain.UserAccount;
import com.aicabinet.trade.domain.UserInfo;
import com.aicabinet.trade.config.CheckoutProperties;
import com.aicabinet.trade.mapper.CabinetOrderMapper;
import com.aicabinet.trade.mapper.DeviceInfoMapper;
import com.aicabinet.trade.mapper.MerchantOpsConfigMapper;
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
    private final ConsumerPreauthService consumerPreauthService;
    private final DeviceInfoMapper deviceRepository;
    private final MerchantOpsConfigMapper opsConfigRepository;
    private final CabinetOrderMapper orderRepository;

    public UserValidationService(UserInfoMapper userInfoRepository,
                                 UserAccountMapper userAccountRepository,
                                 RiskControlService riskControlService,
                                 PayScoreService payScoreService,
                                 CheckoutProperties checkoutProperties,
                                 ConsumerPreauthService consumerPreauthService,
                                 DeviceInfoMapper deviceRepository,
                                 MerchantOpsConfigMapper opsConfigRepository,
                                 CabinetOrderMapper orderRepository) {
        this.userInfoRepository = userInfoRepository;
        this.userAccountRepository = userAccountRepository;
        this.riskControlService = riskControlService;
        this.payScoreService = payScoreService;
        this.checkoutProperties = checkoutProperties;
        this.consumerPreauthService = consumerPreauthService;
        this.deviceRepository = deviceRepository;
        this.opsConfigRepository = opsConfigRepository;
        this.orderRepository = orderRepository;
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
        enforceMaxInflightOrders(userId, deviceId);

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
        int need = consumerPreauthService.resolvePreauthCents(deviceId);
        int available = consumerPreauthService.availableCents(account);
        if (available < need) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED,
                    "可用余额不足，开门需预授权冻结 ¥" + String.format("%.2f", need / 100.0)
                            + "（可用 ¥" + String.format("%.2f", available / 100.0) + "）");
        }
    }

    /**
     * 商户「进行中订单上限」：0=不限制；否则用户 PENDING 未支付订单达到上限时禁止开门。
     */
    private void enforceMaxInflightOrders(Long userId, String deviceId) {
        if (deviceId == null || deviceId.isBlank()) {
            return;
        }
        DeviceInfo device = deviceRepository.findById(deviceId).orElse(null);
        if (device == null || device.getMerchantId() == null || device.getMerchantId().isBlank()) {
            return;
        }
        int max = opsConfigRepository.findById(device.getMerchantId())
                .map(MerchantOpsConfig::getMaxInflightOrders)
                .orElse(0);
        if (max <= 0) {
            return;
        }
        long pending = orderRepository.countByUserIdAndStatus(userId, "PENDING");
        if (pending >= max) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED,
                    "待支付订单已达上限（" + pending + "/" + max + "），请先完成支付后再开门");
        }
    }

    public boolean isPasswordFreeReady(Long userId, String entryChannel) {
        if (userId == null || userId >= CabinetConstants.OPERATOR_USER_ID_START) {
            return true;
        }
        if (checkoutProperties.balanceOnly()) {
            return false;
        }
        UserInfo user = userInfoRepository.findById(userId).orElse(null);
        return payScoreService.isPasswordFreeReadyForChannel(user, entryChannel);
    }

    /** 结算前：若已可按渠道免密扣款，则不强制要求余额覆盖订单金额。 */
    public boolean canChargeViaPasswordFree(Long userId, String entryChannel) {
        return isPasswordFreeReady(userId, entryChannel);
    }

    /** 结算前校验可用余额是否覆盖订单金额（运营账号跳过）。 */
    public void validateSufficientBalanceForCharge(Long userId, int amountCents) {
        validateSufficientBalanceForCharge(userId, amountCents, 0);
    }

    /** 结算前校验：计入本会话已冻结预授权。 */
    public void validateSufficientBalanceForCharge(Long userId, int amountCents, int sessionPreauthHoldCents) {
        if (userId == null || userId >= CabinetConstants.OPERATOR_USER_ID_START || amountCents <= 0) {
            return;
        }
        UserAccount account = userAccountRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.ACCOUNT_NOT_FOUND));
        int frozen = Math.max(0, account.getFrozenCents());
        int hold = Math.max(0, Math.min(frozen, sessionPreauthHoldCents));
        int spendable = account.getBalanceCents() - frozen + hold;
        if (spendable < amountCents) {
            throw new BalanceInsufficientException(ApiMessages.INSUFFICIENT_BALANCE);
        }
    }
}
