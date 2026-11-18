package com.aicabinet.trade.service;

import com.aicabinet.trade.config.CheckoutProperties;
import com.aicabinet.trade.domain.ConsumerPreauthHold;
import com.aicabinet.trade.domain.DeviceInfo;
import com.aicabinet.trade.domain.ShoppingSession;
import com.aicabinet.trade.domain.UserAccount;
import com.aicabinet.trade.mapper.ConsumerPreauthHoldMapper;
import com.aicabinet.trade.mapper.DeviceInfoMapper;
import com.aicabinet.trade.mapper.ShoppingSessionMapper;
import com.aicabinet.trade.mapper.UserAccountMapper;
import com.aicabinet.trade.support.ApiMessages;
import com.aicabinet.trade.support.CommissionParamBounds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;

/**
 * 消费者开门预授权：余额路径在开门时冻结额度，结算时冲抵/释放剩余，取消时释放。
 * 免密（支付分/代扣）路径不冻结，由渠道信用承担。
 * <p>
 * 账户 {@code frozenCents} 为各会话冻结之和；单笔冲抵/释放只动本会话
 * {@link ConsumerPreauthHold#getHoldCents()}，避免多会话并发时误放他会话冻结。
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
    private final ConsumerPreauthHoldMapper holdRepository;
    private final CheckoutProperties checkoutProperties;
    private final BalanceLedgerService balanceLedgerService;
    private final SystemConfigService systemConfigService;
    private final DistributedLockService distributedLockService;

    public ConsumerPreauthService(UserAccountMapper accountRepository,
                                  ShoppingSessionMapper sessionRepository,
                                  DeviceInfoMapper deviceRepository,
                                  ConsumerPreauthHoldMapper holdRepository,
                                  CheckoutProperties checkoutProperties,
                                  BalanceLedgerService balanceLedgerService,
                                  SystemConfigService systemConfigService,
                                  DistributedLockService distributedLockService) {
        this.accountRepository = accountRepository;
        this.sessionRepository = sessionRepository;
        this.deviceRepository = deviceRepository;
        this.holdRepository = holdRepository;
        this.checkoutProperties = checkoutProperties;
        this.balanceLedgerService = balanceLedgerService;
        this.systemConfigService = systemConfigService;
        this.distributedLockService = distributedLockService;
    }

    public int resolvePreauthCents(String deviceId) {
        // MA-294（2026-11-15 拍板：0 压倒一切）：全局键 0=「关闭预授权」是运营的停用开关，
        // 必须压倒设备押金分支——原实现设备分支先返回，配了 deposit_cents 的柜机在全局 0
        // 时照样冻结消费者余额，「运营关掉了预授权却关不掉」。回落分支保持在方法后半。
        int fromDb = systemConfigService.getInt(SystemConfigService.CHECKOUT_PREAUTH_CENTS, -1);
        if (fromDb == 0) {
            return 0;
        }
        if (deviceId != null && !deviceId.isBlank()) {
            DeviceInfo device = deviceRepository.findById(deviceId.trim()).orElse(null);
            if (device != null && device.getDepositCents() != null && device.getDepositCents() > 0) {
                long deposit = device.getDepositCents();
                if (deposit <= CommissionParamBounds.PREAUTH_CENTS_CEILING) {
                    return (int) deposit;
                }
                // 存量荒谬值（写侧上界之前的历史行）：与全局键超限同款降级，
                // 记 warn 后回落配置默认，而不是拿它去冻结消费者余额。
                log.warn("DEVICE deposit_cents={} exceeds preauth ceiling {}; ignoring device value",
                        deposit, CommissionParamBounds.PREAUTH_CENTS_CEILING);
            }
        }
        // 🔴 fromDb==0（关闭预授权）已在方法最前返回；-1（未配置哨兵）与 0 语义不同，勿合并。
        if (fromDb > 0 && fromDb <= CommissionParamBounds.PREAUTH_CENTS_CEILING) {
            return fromDb;
        }
        if (fromDb > CommissionParamBounds.PREAUTH_CENTS_CEILING) {
            // S12 扩面（2026-11-14 拍板）：写侧天花板 400 只拦新写入，存量荒谬值读侧回落配置默认
            log.warn("CONFIG {} = {} exceeds ceiling {}; ignoring config value",
                    SystemConfigService.CHECKOUT_PREAUTH_CENTS, fromDb,
                    CommissionParamBounds.PREAUTH_CENTS_CEILING);
        }
        return checkoutProperties.resolvePreauthCents();
    }

    public int availableCents(UserAccount account) {
        if (account == null) {
            return 0;
        }
        return Math.max(0, account.getBalanceCents() - Math.max(0, account.getFrozenCents()));
    }

    /**
     * 余额开门路径：校验可用余额并冻结；补货/远程不冻结。
     *
     * <p>F3b：{@code skipFreeze} 的唯一来源是「本次会话是否创建了微信支付分单」
     * （SessionOpenService 在建单后读 payscore_order），不再由扫码渠道的免密就绪推断——
     * 只有支付宝签约的用户从支付宝码进来时，旧判据会跳过冻结而结算又回落余额，应收落空。</p>
     */
    @Transactional
    public void freezeForOpen(ShoppingSession session, boolean skipFreeze) {
        if (session == null || session.getSessionId() == null) {
            return;
        }
        if (DeviceValidationService.isRestockSession(session)
                || isOpsRemote(session)
                || session.getUserId() == null) {
            reloadSession(session.getSessionId()).ifPresent(this::clearSessionPreauth);
            return;
        }
        if (skipFreeze) {
            reloadSession(session.getSessionId()).ifPresent(this::clearSessionPreauth);
            return;
        }
        runWithPreauthLock(session.getUserId(),
                () -> doFreezeForOpen(session.getSessionId()));
    }

    private void doFreezeForOpen(String sessionId) {
        ShoppingSession session = reloadSession(sessionId).orElse(null);
        if (session == null) {
            return;
        }
        ConsumerPreauthHold existing = holdRepository.findByIdForUpdate(sessionId).orElse(null);
        if (existing != null && STATUS_FROZEN.equalsIgnoreCase(blankToNone(existing.getStatus()))
                && existing.getHoldCents() > 0) {
            syncSessionFromHold(session, existing);
            return;
        }
        if (STATUS_FROZEN.equalsIgnoreCase(blankToNone(session.getPreauthStatus()))
                && session.getPreauthCents() > 0
                && existing == null) {
            // 兼容迁移前仅写了 session 字段的数据
            upsertHold(session.getSessionId(), session.getUserId(), session.getPreauthCents(), STATUS_FROZEN);
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
        upsertHold(session.getSessionId(), session.getUserId(), amount, STATUS_FROZEN);
        session.setPreauthCents(amount);
        session.setPreauthStatus(STATUS_FROZEN);
        sessionRepository.save(session);
        balanceLedgerService.recordFreezeOnly(session.getUserId(), new BalanceLedgerService.BalanceFreezeCommand(
                amount, "PREAUTH_FREEZE", session.getSessionId(), "PREAUTH_FREEZE:" + session.getSessionId(),
                "开门预授权冻结", account.getBalanceCents(), account.getBalanceCents(), null));
        log.info("preauth frozen session={} user={} amount={}",
                session.getSessionId(), session.getUserId(), amount);
    }

    /** 取消/超时/运维终止：释放冻结。 */
    @Transactional
    public void releaseIfFrozen(ShoppingSession session) {
        if (session == null || session.getSessionId() == null) {
            return;
        }
        if (session.getUserId() == null) {
            reloadSession(session.getSessionId()).ifPresent(s -> {
                if (STATUS_FROZEN.equalsIgnoreCase(blankToNone(s.getPreauthStatus()))) {
                    s.setPreauthStatus(STATUS_RELEASED);
                    sessionRepository.save(s);
                }
                markHoldTerminal(s.getSessionId(), STATUS_RELEASED);
            });
            return;
        }
        runWithPreauthLock(session.getUserId(), () -> doReleaseIfFrozen(session.getSessionId()));
    }

    private void doReleaseIfFrozen(String sessionId) {
        ShoppingSession session = reloadSession(sessionId).orElse(null);
        if (session == null) {
            return;
        }
        ConsumerPreauthHold hold = holdRepository.findByIdForUpdate(sessionId).orElse(null);
        int amount = resolveSessionHoldCents(session, hold);
        boolean sessionFrozen = STATUS_FROZEN.equalsIgnoreCase(blankToNone(session.getPreauthStatus()));
        boolean holdFrozen = hold != null && STATUS_FROZEN.equalsIgnoreCase(blankToNone(hold.getStatus()));
        if (!sessionFrozen && !holdFrozen) {
            return;
        }
        if (amount <= 0 || session.getUserId() == null) {
            session.setPreauthStatus(STATUS_RELEASED);
            sessionRepository.save(session);
            markHoldTerminal(sessionId, STATUS_RELEASED);
            return;
        }
        UserAccount account = accountRepository.findByIdForUpdate(session.getUserId()).orElse(null);
        int release = 0;
        if (account != null) {
            int frozen = Math.max(0, account.getFrozenCents());
            release = Math.min(frozen, amount);
            if (release < amount) {
                log.warn("preauth release clamp session={} hold={} frozen={}", sessionId, amount, frozen);
            }
            account.setFrozenCents(frozen - release);
            accountRepository.save(account);
            if (release > 0) {
                balanceLedgerService.recordFreezeOnly(session.getUserId(), new BalanceLedgerService.BalanceFreezeCommand(
                        release, "PREAUTH_RELEASE", session.getSessionId(), "PREAUTH_RELEASE:" + session.getSessionId(),
                        "开门预授权释放", account.getBalanceCents(), account.getBalanceCents(), null));
            }
        }
        session.setPreauthStatus(STATUS_RELEASED);
        sessionRepository.save(session);
        markHoldTerminal(sessionId, STATUS_RELEASED);
        log.info("preauth released session={} amount={} applied={}", session.getSessionId(), amount, release);
    }

    /**
     * 余额扣款时冲抵预授权：先消费本会话冻结中的 min(order, held)，多余订单额再扣可用余额，剩余冻结释放。
     * 返回仍需从可用余额扣减的金额（分）。
     */
    public record CaptureChargeResult(int capturedCents, int remainDebitCents, boolean remainderUnpaid) {}

    /**
     * 余额扣款时冲抵预授权（F1：返回结果对象；不足时零资金不变量由调用方转 PENDING 保留冲抵）。
     *
     * @param orderId 订单 id（冲抵流水挂单，供 netCompletedCents 可见）；可空（无单场景退化为旧行为）
     */
    @Transactional
    public CaptureChargeResult captureForCharge(ShoppingSession session, int orderAmountCents, String orderId) {
        if (session == null || session.getSessionId() == null) {
            return new CaptureChargeResult(0, Math.max(0, orderAmountCents), false);
        }
        if (session.getUserId() == null) {
            return new CaptureChargeResult(0, Math.max(0, orderAmountCents), false);
        }
        return runWithPreauthLock(session.getUserId(),
                () -> doCaptureForCharge(session.getSessionId(), orderAmountCents, orderId));
    }

    private CaptureChargeResult doCaptureForCharge(String sessionId, int orderAmountCents, String orderId) {
        ShoppingSession session = reloadSession(sessionId).orElse(null);
        if (session == null) {
            return new CaptureChargeResult(0, Math.max(0, orderAmountCents), false);
        }
        ConsumerPreauthHold hold = holdRepository.findByIdForUpdate(sessionId).orElse(null);
        boolean sessionFrozen = STATUS_FROZEN.equalsIgnoreCase(blankToNone(session.getPreauthStatus()));
        boolean holdFrozen = hold != null && STATUS_FROZEN.equalsIgnoreCase(blankToNone(hold.getStatus()));
        if (!sessionFrozen && !holdFrozen) {
            return new CaptureChargeResult(0, Math.max(0, orderAmountCents), false);
        }
        int held = resolveSessionHoldCents(session, hold);
        if (held <= 0 || session.getUserId() == null) {
            session.setPreauthStatus(STATUS_CAPTURED);
            sessionRepository.save(session);
            markHoldTerminal(sessionId, STATUS_CAPTURED);
            return new CaptureChargeResult(0, Math.max(0, orderAmountCents), false);
        }
        UserAccount account = accountRepository.findByIdForUpdate(session.getUserId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.ACCOUNT_NOT_FOUND));
        int frozen = Math.max(0, account.getFrozenCents());
        // 只动本会话 hold；若账户冻结不足本会话（历史不一致），钳制并告警，绝不按更大的 held 抽干他会话
        int usableHold = Math.min(frozen, held);
        if (usableHold < held) {
            log.warn("preauth capture clamp session={} hold={} frozen={}", sessionId, held, frozen);
        }
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
            balanceLedgerService.recordFreezeOnly(session.getUserId(), new BalanceLedgerService.BalanceFreezeCommand(
                    capture, "PREAUTH_CAPTURE", session.getSessionId(),
                    "PREAUTH_CAPTURE:" + session.getSessionId() + ":" + capture,
                    "开门预授权冲抵订单", before, account.getBalanceCents(),
                    // F1-A：冲抵行挂订单 → netCompletedCents 可见，补扣按净额不再多付
                    orderId));
        }
        if (release > 0) {
            balanceLedgerService.recordFreezeOnly(session.getUserId(), new BalanceLedgerService.BalanceFreezeCommand(
                    release, "PREAUTH_RELEASE", session.getSessionId(),
                    "PREAUTH_RELEASE:" + session.getSessionId() + ":remain",
                    "开门预授权剩余释放", account.getBalanceCents(), account.getBalanceCents(),
                    null));
        }
        session.setPreauthStatus(STATUS_CAPTURED);
        sessionRepository.save(session);
        markHoldTerminal(sessionId, STATUS_CAPTURED);
        int remain = Math.max(0, orderAmountCents - capture);
        // F1-B：锁内预判余额是否够扣差额——不足是业务结果而非异常（异常穿越代理会毒化事务）
        boolean remainderUnpaid = account.getBalanceCents() < remain;
        log.info("preauth captured session={} orderAmount={} capture={} remainDebit={} remainderUnpaid={}",
                session.getSessionId(), orderAmountCents, capture, remain, remainderUnpaid);
        return new CaptureChargeResult(capture, remain, remainderUnpaid);
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
        holdRepository.findById(session.getSessionId()).ifPresent(h -> {
            if (STATUS_FROZEN.equalsIgnoreCase(blankToNone(h.getStatus()))) {
                h.setStatus(STATUS_RELEASED);
                h.setHoldCents(0);
                h.setUpdatedAt(Instant.now());
                holdRepository.save(h);
            }
        });
    }

    private static boolean isOpsRemote(ShoppingSession session) {
        String key = session.getIdempotencyKey();
        return key != null && key.startsWith("OPS_REMOTE:");
    }

    private static String blankToNone(String status) {
        return status == null || status.isBlank() ? STATUS_NONE : status.trim();
    }

    static String preauthLockKey(long userId) {
        return "preauth:user:" + userId;
    }

    private java.util.Optional<ShoppingSession> reloadSession(String sessionId) {
        return sessionRepository.findByIdForUpdate(sessionId);
    }

    private void runWithPreauthLock(long userId, Runnable action) {
        runWithPreauthLock(userId, () -> {
            action.run();
            return null;
        });
    }

    private <T> T runWithPreauthLock(long userId, java.util.function.Supplier<T> action) {
        String lockKey = preauthLockKey(userId);
        if (!distributedLockService.tryLock(lockKey, 60, 5)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "预授权处理中，请稍后重试");
        }
        try {
            return action.get();
        } finally {
            distributedLockService.unlock(lockKey);
        }
    }

    private int resolveSessionHoldCents(ShoppingSession session, ConsumerPreauthHold hold) {
        if (hold != null && STATUS_FROZEN.equalsIgnoreCase(blankToNone(hold.getStatus()))) {
            return Math.max(0, hold.getHoldCents());
        }
        return Math.max(0, session.getPreauthCents());
    }

    private void upsertHold(String sessionId, Long userId, int holdCents, String status) {
        Instant now = Instant.now();
        ConsumerPreauthHold hold = holdRepository.findById(sessionId).orElseGet(ConsumerPreauthHold::new);
        hold.setSessionId(sessionId);
        hold.setUserId(userId);
        hold.setHoldCents(Math.max(0, holdCents));
        hold.setStatus(status);
        if (hold.getCreatedAt() == null) {
            hold.setCreatedAt(now);
        }
        hold.setUpdatedAt(now);
        holdRepository.save(hold);
    }

    private void markHoldTerminal(String sessionId, String status) {
        holdRepository.findById(sessionId).ifPresent(h -> {
            h.setStatus(status);
            h.setUpdatedAt(Instant.now());
            holdRepository.save(h);
        });
    }

    private void syncSessionFromHold(ShoppingSession session, ConsumerPreauthHold hold) {
        session.setPreauthCents(Math.max(0, hold.getHoldCents()));
        session.setPreauthStatus(STATUS_FROZEN);
        sessionRepository.save(session);
    }
}
