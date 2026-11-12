package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.Member;
import com.aicabinet.trade.domain.MemberPointsLog;
import com.aicabinet.trade.mapper.MemberMapper;
import com.aicabinet.trade.mapper.MemberPointsLogMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

/**
 * 积分过期管理：到期前 N 天提醒，到期后把可用积分结转至过期并写日志。
 */
@Service
public class PointsExpiryScheduler {
    private static final String POINTS_EXPIRY = "points-expiry";


    private static final Logger log = LoggerFactory.getLogger(PointsExpiryScheduler.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZONE);

    private final MemberPointsLogMapper pointsLogRepository;
    private final MemberMapper memberRepository;
    private final NotificationService notificationService;
    /** 经 Spring 代理调用本类 @Transactional 方法，避免自调用失效。 */
    private final PointsExpiryScheduler self;
    private final ScheduledTaskService taskService;

    public PointsExpiryScheduler(MemberPointsLogMapper pointsLogRepository,
                                 MemberMapper memberRepository,
                                 NotificationService notificationService,
                                 @Lazy PointsExpiryScheduler self,
                                 ScheduledTaskService taskService) {
        this.pointsLogRepository = pointsLogRepository;
        this.memberRepository = memberRepository;
        this.notificationService = notificationService;
        this.self = self;
        this.taskService = taskService;
    }

    @Scheduled(fixedRate = 6 * 3_600_000L)
    @Transactional
    public void scan() {
        long start = System.nanoTime();
        if (!taskService.tryBegin(POINTS_EXPIRY, 600)) {
            return;
        }
        boolean failed = false;
        String summary = "本次无积分提醒或过期";
        try {
            int reminded = self.remind(7);
            int expired = self.expire();
            summary = "提醒 " + reminded + " 人，过期结转 " + expired + " 人";
            if (reminded > 0 || expired > 0) {
                log.info("points expiry scan reminded={} expired={}", reminded, expired);
            }
        } catch (Exception e) {
            failed = true;
            taskService.finish(POINTS_EXPIRY, "FAILED", e.getMessage(), start);
            throw e;
        } finally {
            if (!failed) {
                taskService.finish(POINTS_EXPIRY, "SUCCESS", summary, start);
            }
        }
    }

    /** 到期前 remindDays 提醒一次，返回提醒的用户数。 */
    @Transactional
    public int remind(int remindDays) {
        Instant now = Instant.now();
        Instant end = now.plus(remindDays, ChronoUnit.DAYS);
        List<MemberPointsLog> logs = pointsLogRepository.findEarnExpiringBetween(now, end);
        Map<Long, List<MemberPointsLog>> byMember = groupByMember(logs);
        int remindedUsers = 0;
        for (Map.Entry<Long, List<MemberPointsLog>> e : byMember.entrySet()) {
            Long memberId = e.getKey();
            Member member = memberRepository.findById(memberId).orElse(null);
            if (member == null || member.getUserId() == null) {
                continue;
            }
            // R20-5：提醒的是「还没过期掉的余额」，部分结转过的行只报剩余部分
            int points = e.getValue().stream()
                    .mapToInt(l -> Math.max(0, nz(l.getPoints()) - nz(l.getExpiredPoints())))
                    .sum();
            if (points <= 0) {
                continue;
            }
            Instant expireAt = e.getValue().stream()
                    .map(MemberPointsLog::getExpireAt)
                    .filter(java.util.Objects::nonNull)
                    .min(Instant::compareTo)
                    .orElse(end);
            try {
                notificationService.notifyConsumer(
                        member.getUserId(),
                        "points_expiring",
                        Map.of("points", String.valueOf(points), "expireAt", DATE_FMT.format(expireAt)),
                        "POINTS",
                        "expire-" + memberId);
            } catch (Exception ex) {
                log.warn("points expiry remind failed member={}", memberId, ex);
            }
            for (MemberPointsLog l : e.getValue()) {
                l.setRemindedAt(now);
                pointsLogRepository.save(l);
            }
            remindedUsers++;
        }
        return remindedUsers;
    }

    /** 过期结转：可用→过期并写 EXPIRE 日志，返回受影响用户数。 */
    @Transactional
    public int expire() {
        Instant now = Instant.now();
        Map<Long, List<MemberPointsLog>> byMember = groupByMember(
                pointsLogRepository.findEarnExpiredBefore(now));
        int affected = 0;
        for (Map.Entry<Long, List<MemberPointsLog>> e : byMember.entrySet()) {
            if (expireMemberPoints(e.getKey(), e.getValue(), now)) {
                affected++;
            }
        }
        return affected;
    }

    private boolean expireMemberPoints(Long memberId, List<MemberPointsLog> logs, Instant now) {
        Member member = memberRepository.findByIdForUpdate(memberId).orElse(null);
        if (member == null) {
            return false;
        }
        // R20-5：预算只算「本行还没过期掉的部分」——部分结转过的行会再次入选，
        // 用原始 points 求和会把已过期额度重复计入预算，进而多扣用户积分。
        int expirePoints = logs.stream()
                .mapToInt(l -> Math.max(0, nz(l.getPoints()) - nz(l.getExpiredPoints())))
                .sum();
        if (expirePoints <= 0) {
            return false;
        }
        int available = nz(member.getAvailablePoints());
        int actualExpire = Math.min(available, expirePoints);
        if (actualExpire <= 0) {
            return false;
        }
        member.setAvailablePoints(available - actualExpire);
        member.setExpiredPoints(nz(member.getExpiredPoints()) + actualExpire);
        member.setUpdatedAt(now);
        memberRepository.save(member);
        markEarnLogsExpired(logs, actualExpire, now);
        writeExpireLog(memberId, actualExpire, now);
        return true;
    }

    /**
     * 按行标记已过期积分（R20-5）。
     *
     * <p>旧实现的两种标记是「全或无」：{@code pts <= remaining} 就整行置 {@code expired_at}，
     * 否则整行跳过 —— 而 {@code actualExpire = min(available, Σ)} 常常小于某一行面额，
     * 于是这行既不标记也不扣减额度，下一轮又被整额重算，同一笔 EARN 被反复过期
     * （EARN=100、余额 10 ⇒ 本轮扣 10、下轮再扣 100，累计 110 &gt; 100）。</p>
     *
     * <p>现在每行记 {@code expired_points} 累计已过期数：可过期额 = {@code points − 已过期}，
     * 本轮取 {@code min(可过期额, 预算余量)}；整行耗尽才置 {@code expired_at}（并因此不再入选
     * {@code findEarnExpiredBefore}）。判据：同一 EARN 行累计过期 ≤ 其 points。</p>
     */
    private void markEarnLogsExpired(List<MemberPointsLog> logs, int actualExpire, Instant now) {
        int remaining = actualExpire;
        for (MemberPointsLog l : logs) {
            if (remaining <= 0) {
                break;
            }
            int pts = nz(l.getPoints());
            if (pts <= 0) {
                continue;
            }
            int alreadyExpired = nz(l.getExpiredPoints());
            int expireLeft = pts - alreadyExpired;
            if (expireLeft <= 0) {
                // 存量数据兜底：已整额过期但 expired_at 缺失（V459 回填之后不应出现）
                if (l.getExpiredAt() == null) {
                    l.setExpiredAt(now);
                    pointsLogRepository.save(l);
                }
                continue;
            }
            int take = Math.min(expireLeft, remaining);
            int totalExpired = alreadyExpired + take;
            l.setExpiredPoints(totalExpired);
            if (totalExpired >= pts) {
                l.setExpiredAt(now);
            }
            pointsLogRepository.save(l);
            remaining -= take;
        }
    }

    private void writeExpireLog(Long memberId, int actualExpire, Instant now) {
        MemberPointsLog expireLog = new MemberPointsLog();
        expireLog.setMemberId(memberId);
        expireLog.setPoints(-actualExpire);
        expireLog.setPointsType("EXPIRE");
        expireLog.setSourceType("EXPIRE");
        expireLog.setSourceId("points-expire-" + DATE_FMT.format(now));
        expireLog.setDescription("积分过期");
        expireLog.setExpiredAt(now);
        pointsLogRepository.save(expireLog);
    }

    private static Map<Long, List<MemberPointsLog>> groupByMember(List<MemberPointsLog> logs) {
        Map<Long, List<MemberPointsLog>> map = new LinkedHashMap<>();
        for (MemberPointsLog l : logs) {
            map.computeIfAbsent(l.getMemberId(), k -> new ArrayList<>()).add(l);
        }
        return map;
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }
}
