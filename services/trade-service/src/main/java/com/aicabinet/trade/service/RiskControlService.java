package com.aicabinet.trade.service;

import com.aicabinet.common.enums.SessionState;
import com.aicabinet.trade.config.RiskControlProperties;
import com.aicabinet.trade.domain.RiskEvent;
import com.aicabinet.trade.domain.UserBlacklist;
import com.aicabinet.trade.mapper.RiskEventMapper;
import com.aicabinet.trade.mapper.ShoppingSessionMapper;
import com.aicabinet.trade.mapper.UserBlacklistMapper;
import com.aicabinet.trade.support.ApiMessages;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

@Service
public class RiskControlService {
    private static final String REASON = "reason";


    private static final Logger log = LoggerFactory.getLogger(RiskControlService.class);

    private final RiskControlProperties properties;
    private final UserBlacklistMapper blacklistRepository;
    private final RiskEventMapper riskEventRepository;
    private final ShoppingSessionMapper sessionRepository;
    private final ObjectMapper objectMapper;
    private final DistributedLockService distributedLockService;

    /**
     * V325（CB-011）欠款催缴阶梯：判「是否已到冻结免密先享档」。
     *
     * <p>🔴 用 <b>setter 可选注入</b>而非构造器参数，原因是本类的既有单测
     * （{@code RiskControlServiceUnpaidTest}）按 6 参构造器实例化；
     * 改构造器会让「与本次改动无关的测试」被迫修改 ⇒ 掩盖真实破坏面。
     * 为 null 时阶梯校验整体跳过（退化为 V324 的行为），不阻断既有用法。
     */
    private UnpaidDunningService unpaidDunningService;

    /** 由 Spring 在容器装配完成后注入（{@code @Autowired(required=false)} 语义）。 */
    @org.springframework.beans.factory.annotation.Autowired
    public void setUnpaidDunningService(UnpaidDunningService unpaidDunningService) {
        this.unpaidDunningService = unpaidDunningService;
    }

    public RiskControlService(RiskControlProperties properties,
                              UserBlacklistMapper blacklistRepository,
                              RiskEventMapper riskEventRepository,
                              ShoppingSessionMapper sessionRepository,
                              ObjectMapper objectMapper,
                              DistributedLockService distributedLockService) {
        this.properties = properties;
        this.blacklistRepository = blacklistRepository;
        this.riskEventRepository = riskEventRepository;
        this.sessionRepository = sessionRepository;
        this.objectMapper = objectMapper;
        this.distributedLockService = distributedLockService;
    }

    public void validateCanOpenDoor(Long userId, String deviceId) {
        if (userId == null || !properties.enabled()) {
            return;
        }
        Instant now = Instant.now();
        blacklistRepository.findById(userId).ifPresent(bl -> {
            if (bl.getExpiresAt() == null || bl.getExpiresAt().isAfter(now)) {
                recordEvent(userId, deviceId, "BLACKLIST_HIT", "BLOCK", Map.of(REASON, bl.getReason()));
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        ApiMessages.USER_BLACKLISTED + (bl.getReason() == null || bl.getReason().isBlank()
                                ? "" : "：" + bl.getReason()));
            }
        });

        // V325（CB-011）：欠款阶梯达到「冻结免密先享」⇒ 限制再次开柜。
        // 🔴 为什么在这里拦而不是下单时：CB-011 的判据是「累计欠款次数」，
        //   而消费前那一刻用户还没产生新欠款，拦不住逃单 ⇒ 只能拦「再次开门」。
        // 🔴 绝不越界（CB-011 合规红线）：只做**本平台**行为限制，
        //   不跨商户/全平台、不上报征信 —— 我们无持牌资质。
        if (unpaidDunningService != null && unpaidDunningService.isPreauthRestricted(userId)) {
            recordEvent(userId, deviceId, "UNPAID_TIER_RESTRICTED", "WARN", Map.of());
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    ApiMessages.USER_BLACKLISTED + "（存在多笔未支付订单，请先结清）");
        }

        Instant since1h = now.minus(1, ChronoUnit.HOURS);
        long recentOpens = sessionRepository.countByUserIdAndCreatedAtAfter(userId, since1h);
        if (recentOpens >= properties.maxOpensPerHour()) {
            recordEvent(userId, deviceId, "MALICIOUS_OPEN", "WARN",
                    Map.of("opensLastHour", recentOpens));
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, ApiMessages.TOO_MANY_OPENS);
        }
    }

    /**
     * 🔴 记一笔「欠款（待支付）订单」风控事件。
     *
     * <p><b>为什么必须记</b>：欠款路径此前<b>完全不写</b> {@code RiskEvent}
     * ⇒ 运营台的风控事件列表只能看到纠纷与黑名单命中，<b>看不到逃单</b>
     * ⇒ 逃单在风控视角是「隐形」的，既没法主动催缴，也没法统计逃单率。
     *
     * <p><b>为什么统一收口到这里</b>：欠款有<b>三个</b>落库点
     * （结算时余额不足、扣款失败转 PENDING、信号化不足收尾），
     * 各自散着记会漏；统一走本方法 ⇒ 事件语义一致、便于统计。
     *
     * @param stage 触发阶段（决定 severity）：
     *              {@code PRE_CHARGE} =扣款前就发现余额不足（还没扣）
     *              {@code CHARGE_FAILED} = 扣款失败转 PENDING（已尝试扣）
     * @param amountCents 欠款金额（分）
     */
    public void onUnpaidOrderCreated(Long userId,
                                      String deviceId,
                                      String orderId,
                                      String stage,
                                      long amountCents) {
        if (userId == null) {
            return;
        }
        // 🔴 刻意**只记不拦**：本方法绝不能抛异常 ——
        //   它在结算主流程里被调用，抛异常会让「欠款」升级成「结算失败」，
        //   而欠款本身是**可继续追缴的正常状态**，不该阻断交易收尾。
        try {
            recordEvent(userId, deviceId, "UNPAID_ORDER", "WARN", Map.of(
                    "orderId", orderId == null ? "" : orderId,
                    "stage", stage == null ? "" : stage,
                    // 🔴 金额记「元」而非分：风控事件详情是给人看的，1334 分不直观
                    "amountYuan", String.format("%.2f", amountCents / 100.0)));
        } catch (RuntimeException e) {
            log.warn("record UNPAID_ORDER risk event failed userId={} orderId={} err={}",
                    userId, orderId, e.getMessage());
        }
    }

    public void onDisputeCreated(Long userId, String sessionId) {
        recordEvent(userId, null, "DISPUTE_CREATED", "INFO", Map.of("sessionId", sessionId));
        Instant since7d = Instant.now().minus(7, ChronoUnit.DAYS);
        long disputes = sessionRepository.countByUserIdAndStateAndCreatedAtAfter(
                userId, SessionState.DISPUTED, since7d);
        if (disputes >= properties.maxDisputesPer7Days()) {
            autoBlacklist(userId, "频繁申诉 " + disputes + " 次/7天");
            recordEvent(userId, null, "FREQUENT_DISPUTE", "WARN", Map.of("count", disputes));
        }
    }

    @Transactional
    public void addBlacklist(Long operatorId, Long userId, String reason, Instant expiresAt) {
        runWithBlacklistLock(userId, () -> {
            UserBlacklist bl = blacklistRepository.findByIdForUpdate(userId).orElseGet(UserBlacklist::new);
            bl.setUserId(userId);
            bl.setReason(reason);
            bl.setSource("MANUAL");
            bl.setExpiresAt(expiresAt);
            blacklistRepository.save(bl);
            recordEvent(userId, null, "BLACKLIST_ADD", "INFO", Map.of(REASON, reason, "by", operatorId));
            log.info("user blacklisted userId={} by={}", userId, operatorId);
            return null;
        });
    }

    @Transactional
    public void removeBlacklist(Long userId) {
        runWithBlacklistLock(userId, () -> {
            blacklistRepository.findByIdForUpdate(userId).ifPresent(blacklistRepository::delete);
            return null;
        });
    }

    private void autoBlacklist(Long userId, String reason) {
        if (!distributedLockService.tryLock(blacklistLockKey(userId), 60, 5)) {
            log.warn("blacklist auto lock busy userId={}", userId);
            return;
        }
        try {
            if (blacklistRepository.findByIdForUpdate(userId).isPresent()) {
                return;
            }
            UserBlacklist bl = new UserBlacklist();
            bl.setUserId(userId);
            bl.setReason(reason);
            bl.setSource("AUTO");
            bl.setExpiresAt(Instant.now().plus(30, ChronoUnit.DAYS));
            blacklistRepository.save(bl);
            recordEvent(userId, null, "BLACKLIST_AUTO", "BLOCK", Map.of(REASON, reason));
        } finally {
            distributedLockService.unlock(blacklistLockKey(userId));
        }
    }

    private void recordEvent(Long userId, String deviceId, String type, String severity, Map<String, ?> detail) {
        RiskEvent event = new RiskEvent();
        event.setUserId(userId);
        event.setDeviceId(deviceId);
        event.setEventType(type);
        event.setSeverity(severity);
        try {
            event.setDetail(objectMapper.writeValueAsString(detail));
        } catch (JsonProcessingException e) {
            event.setDetail("{}");
        }
        riskEventRepository.save(event);
    }

    static String blacklistLockKey(Long userId) {
        return "risk:blacklist:" + userId;
    }

    private void runWithBlacklistLock(Long userId, java.util.function.Supplier<Void> action) {
        if (!distributedLockService.tryLock(blacklistLockKey(userId), 60, 5)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "黑名单处理中，请稍后重试");
        }
        try {
            action.get();
        } finally {
            distributedLockService.unlock(blacklistLockKey(userId));
        }
    }
}
