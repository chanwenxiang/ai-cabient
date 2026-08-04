package com.aicabinet.trade.service;

import com.aicabinet.common.constants.CabinetConstants;
import com.aicabinet.trade.config.CheckoutProperties;
import com.aicabinet.trade.domain.DeviceInfo;
import com.aicabinet.trade.domain.ShoppingSession;
import com.aicabinet.trade.domain.UserAccount;
import com.aicabinet.trade.mapper.DeviceInfoMapper;
import com.aicabinet.trade.mapper.ShoppingSessionMapper;
import com.aicabinet.trade.mapper.UserAccountMapper;
import com.aicabinet.trade.support.ApiMessages;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 消费者开门预授权：余额路径在开门时冻结额度，结算时冲抵/释放剩余，取消时释放。
 * 免密（支付分/代扣）路径不冻结，由渠道信用承担。
 */
@Service
public class ConsumerPreauthService {

    private static final Logger log = LoggerFactory.getLogger(ConsumerPreauthService.class);

    public static final String STATUS_NONE = "NONE";
    public static final String STATUS_FROZEN = "FROZEN";
    public static final String STATUS_CAPTURED = "CAPTURED";
    public static final String STATUS_RELEASED = "RELEASED";

    private final UserAccountMapper accountRepository;
    private final ShoppingSessionMapper sessionRepository;
    private final DeviceInfoMapper deviceRepository;
    private final CheckoutProperties checkoutProperties;
    private final BalanceLedgerService balanceLedgerService;
    private final SystemConfigService systemConfigService;

    public ConsumerPreauthService(UserAccountMapper accountRepository,
                                  ShoppingSessionMapper sessionRepository,
                                  DeviceInfoMapper deviceRepository,
                                  CheckoutProperties checkoutProperties,
                                  BalanceLedgerService balanceLedgerService,
                                  SystemConfigService systemConfigService) {
        this.accountRepository = accountRepository;
        this.sessionRepository = sessionRepository;
        this.deviceRepository = deviceRepository;
        this.checkoutProperties = checkoutProperties;
        this.balanceLedgerService = balanceLedgerService;
        this.systemConfigService = systemConfigService;
    }

    public int resolvePreauthCents(String deviceId) {
        if (deviceId != null && !deviceId.isBlank()) {
            DeviceInfo device = deviceRepository.findById(deviceId.trim()).orElse(null);
            if (device != null && device.getDepositCents() != null && device.getDepositCents() > 0) {
                return (int) Math.min(Integer.MAX_VALUE, device.getDepositCents());
            }
        }
        int fromDb = systemConfigService.getInt(SystemConfigService.CHECKOUT_PREAUTH_CENTS, -1);
        if (fromDb > 0) {
            return fromDb;
        }
        return checkoutProperties.resolvePreauthCents();
    }

    public int availableCents(UserAccount account) {
        if (account == null) {
            return 0;
        }
        return Math.max(0, account.getBalanceCents() - Math.max(0, account.getFrozenCents()));
    }

    /** 余额开门路径：校验可用余额并冻结；免密/运营/补货不冻结。 */
    @Transactional
    public void freezeForOpen(ShoppingSession session, boolean passwordFreeReady) {
        if (session == null || session.getSessionId() == null) {
            return;
        }
        if (DeviceValidationService.isRestockSession(session)
                || isOpsRemote(session)
                || session.getUserId() == null
                || session.getUserId() >= CabinetConstants.OPERATOR_USER_ID_START) {
            clearSessionPreauth(session);
            return;
        }
        if (passwordFreeReady) {
            clearSessionPreauth(session);
            return;
        }
        if (STATUS_FROZEN.equalsIgnoreCase(blankToNone(session.getPreauthStatus()))
                && session.getPreauthCents() > 0) {
            return;
        }
        int amount = resolvePreauthCents(session.getDeviceId());
        if (amount <= 0) {
            clearSessionPreauth(session);
            return;
        }
        UserAccount account = accountRepository.findByIdForUpdate(session.getUserId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.ACCOUNT_NOT_FOUND));
        int available = availableCents(account);
        if (available < amount) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED,
                    "可用余额不足，开门需预授权冻结 ¥" + String.format("%.2f", amount / 100.0)
                            + "（可用 ¥" + String.format("%.2f", available / 100.0) + "）");
        }
        account.setFrozenCents(account.getFrozenCents() + amount);
        accountRepository.save(account);
        session.setPreauthCents(amount);
        session.setPreauthStatus(STATUS_FROZEN);
        sessionRepository.save(session);
        balanceLedgerService.recordFreezeOnly(session.getUserId(), amount, "PREAUTH_FREEZE",
                session.getSessionId(), "PREAUTH_FREEZE:" + session.getSessionId(),
                "开门预授权冻结", account.getBalanceCents(), account.getBalanceCents());
        log.info("preauth frozen session={} user={} amount={}",
                session.getSessionId(), session.getUserId(), amount);
    }

    /** 取消/超时/运维终止：释放冻结。 */
    @Transactional
    public void releaseIfFrozen(ShoppingSession session) {
        if (session == null || !STATUS_FROZEN.equalsIgnoreCase(blankToNone(session.getPreauthStatus()))) {
            return;
        }
        int amount = Math.max(0, session.getPreauthCents());
        if (amount <= 0 || session.getUserId() == null) {
            session.setPreauthStatus(STATUS_RELEASED);
            sessionRepository.save(session);
            return;
        }
        UserAccount account = accountRepository.findByIdForUpdate(session.getUserId())
                .orElse(null);
        if (account != null) {
            int frozen = Math.max(0, account.getFrozenCents());
            int release = Math.min(frozen, amount);
            account.setFrozenCents(frozen - release);
            accountRepository.save(account);
            if (release > 0) {
                balanceLedgerService.recordFreezeOnly(session.getUserId(), release, "PREAUTH_RELEASE",
                        session.getSessionId(), "PREAUTH_RELEASE:" + session.getSessionId(),
                        "开门预授权释放", account.getBalanceCents(), account.getBalanceCents());
            }
        }
        session.setPreauthStatus(STATUS_RELEASED);
        sessionRepository.save(session);
        log.info("preauth released session={} amount={}", session.getSessionId(), amount);
    }

    /**
     * 余额扣款时冲抵预授权：先消费冻结中的 min(order, held)，多余订单额再扣可用余额，剩余冻结释放。
     * 返回仍需从可用余额扣减的金额（分）。
     */
    @Transactional
    public int captureForCharge(ShoppingSession session, int orderAmountCents) {
        if (session == null || !STATUS_FROZEN.equalsIgnoreCase(blankToNone(session.getPreauthStatus()))) {
            return Math.max(0, orderAmountCents);
        }
        int held = Math.max(0, session.getPreauthCents());
        if (held <= 0 || session.getUserId() == null) {
            session.setPreauthStatus(STATUS_CAPTURED);
            sessionRepository.save(session);
            return Math.max(0, orderAmountCents);
        }
        UserAccount account = accountRepository.findByIdForUpdate(session.getUserId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.ACCOUNT_NOT_FOUND));
        int frozen = Math.max(0, account.getFrozenCents());
        int usableHold = Math.min(frozen, held);
        int capture = Math.min(Math.max(0, orderAmountCents), usableHold);
        int release = usableHold - capture;
        int before = account.getBalanceCents();
        account.setBalanceCents(before - capture);
        account.setFrozenCents(frozen - usableHold);
        if (account.getBalanceCents() < 0 || account.getFrozenCents() < 0) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED, ApiMessages.INSUFFICIENT_BALANCE);
        }
        accountRepository.save(account);
        if (capture > 0) {
            balanceLedgerService.recordFreezeOnly(session.getUserId(), capture, "PREAUTH_CAPTURE",
                    session.getSessionId(),
                    "PREAUTH_CAPTURE:" + session.getSessionId() + ":" + capture,
                    "开门预授权冲抵订单", before, account.getBalanceCents());
        }
        if (release > 0) {
            balanceLedgerService.recordFreezeOnly(session.getUserId(), release, "PREAUTH_RELEASE",
                    session.getSessionId(),
                    "PREAUTH_RELEASE:" + session.getSessionId() + ":remain",
                    "开门预授权剩余释放", account.getBalanceCents(), account.getBalanceCents());
        }
        session.setPreauthStatus(STATUS_CAPTURED);
        sessionRepository.save(session);
        int remain = Math.max(0, orderAmountCents - capture);
        log.info("preauth captured session={} orderAmount={} capture={} remainDebit={}",
                session.getSessionId(), orderAmountCents, capture, remain);
        return remain;
    }

    /** 按会话号释放预授权（待支付关单等）。 */
    @Transactional
    public void releaseBySessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        sessionRepository.findById(sessionId).ifPresent(this::releaseIfFrozen);
    }

    private void clearSessionPreauth(ShoppingSession session) {
        session.setPreauthCents(0);
        session.setPreauthStatus(STATUS_NONE);
        sessionRepository.save(session);
    }

    private static boolean isOpsRemote(ShoppingSession session) {
        String key = session.getIdempotencyKey();
        return key != null && key.startsWith("OPS_REMOTE:");
    }

    private static String blankToNone(String status) {
        return status == null || status.isBlank() ? STATUS_NONE : status.trim();
    }
}
