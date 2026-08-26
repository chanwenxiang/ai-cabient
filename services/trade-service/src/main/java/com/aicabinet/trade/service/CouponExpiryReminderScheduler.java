package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.CouponDefinition;
import com.aicabinet.trade.domain.UserCoupon;
import com.aicabinet.trade.mapper.CouponDefinitionMapper;
import com.aicabinet.trade.mapper.UserCouponMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 优惠券临期提醒：到期前 3 天推送一次，避免过期浪费。 */
@Service
public class CouponExpiryReminderScheduler {
    private static final String COUPON_EXPIRY_REMIND = "coupon-expiry-remind";


    private static final Logger log = LoggerFactory.getLogger(CouponExpiryReminderScheduler.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZONE);

    private final UserCouponMapper couponRepository;
    private final CouponDefinitionMapper definitionRepository;
    private final NotificationService notificationService;
        /** 经 Spring 代理调用本类 @Transactional 方法，避免自调用失效。 */
    private final CouponExpiryReminderScheduler self;

    @Autowired
    private ScheduledTaskService taskService;

    public CouponExpiryReminderScheduler(UserCouponMapper couponRepository,
                                         CouponDefinitionMapper definitionRepository,
                                         NotificationService notificationService, @Lazy CouponExpiryReminderScheduler self) {
        this.couponRepository = couponRepository;
        this.definitionRepository = definitionRepository;
        this.notificationService = notificationService;
        this.self = self;
    }

    @Scheduled(fixedRate = 6 * 3_600_000L)
    @Transactional
    public void scan() {
        long start = System.nanoTime();
        if (!taskService.tryBegin(COUPON_EXPIRY_REMIND, 600)) {
            return;
        }
        boolean failed = false;
        String summary = "本次无临期优惠券";
        try {
            int reminded = self.remind(3);
            summary = reminded <= 0 ? "本次无临期优惠券" : "临期提醒 " + reminded + " 人";
            if (reminded > 0) {
                log.info("coupon expiry remind users={}", reminded);
            }
        } catch (Exception e) {
            failed = true;
            taskService.finish(COUPON_EXPIRY_REMIND, "FAILED", e.getMessage(), start);
            throw e;
        } finally {
            if (!failed) {
                taskService.finish(COUPON_EXPIRY_REMIND, "SUCCESS", summary, start);
            }
        }
    }

    @Transactional
    public int remind(int remindDays) {
        Instant now = Instant.now();
        Instant end = now.plus(remindDays, ChronoUnit.DAYS);
        List<UserCoupon> coupons = couponRepository.findByStatusAndExpireAtBetween("UNUSED", now, end);
        Map<Long, List<UserCoupon>> byUser = new LinkedHashMap<>();
        for (UserCoupon c : coupons) {
            byUser.computeIfAbsent(c.getUserId(), k -> new ArrayList<>()).add(c);
        }
        int remindedUsers = 0;
        for (Map.Entry<Long, List<UserCoupon>> e : byUser.entrySet()) {
            UserCoupon first = e.getValue().get(0);
            CouponDefinition def = first.getCouponDefId() == null
                    ? null
                    : definitionRepository.findById(first.getCouponDefId()).orElse(null);
            String couponName = def != null ? def.getCouponName() : "优惠券";
            String expireAt = first.getExpireAt() != null ? DATE_FMT.format(first.getExpireAt()) : "";
            try {
                notificationService.notifyConsumer(
                        e.getKey(),
                        "coupon_expiring",
                        Map.of("couponName", couponName, "expireAt", expireAt),
                        "COUPON",
                        null);
            } catch (Exception ex) {
                log.warn("coupon expiry remind failed userId={}", e.getKey(), ex);
            }
            for (UserCoupon c : e.getValue()) {
                c.setRemindedAt(now);
                couponRepository.save(c);
            }
            remindedUsers++;
        }
        return remindedUsers;
    }
}
