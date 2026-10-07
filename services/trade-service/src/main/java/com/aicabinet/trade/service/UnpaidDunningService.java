package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.CabinetOrder;
import com.aicabinet.trade.domain.UnpaidDunningRecord;
import com.aicabinet.trade.domain.UserInfo;
import com.aicabinet.trade.mapper.UnpaidDunningRecordMapper;
import com.aicabinet.trade.mapper.UserInfoMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

/**
 * 欠款催缴（CB-003 / CB-004 / CB-011）。
 *
 * <p>🔴 为什么必须有短信：现有 {@code UnpaidOrderService.remind()} 只走**微信订阅消息**，
 * 而订阅消息<b>需用户主动订阅</b> ⇒ <b>最该被催的逃单者恰恰收不到</b>（这正是
 * 「自动拉黑会误伤、必须有事后追缴」里追缴那一环缺失的原因）。
 *
 * <p>竞品依据：
 * <ul>
 *   <li>CB-003：微信支付分官方称扣款失败会按频次自动重试并发催收消息；
 *       支付宝芝麻先享定义了「2 小时转待守约 / 7 天转已逾期」状态机；
 *       服务商侧实操是「每天自动重扣 + 每天催款短信」。</li>
 *   <li>CB-004：升级路径 = 催短信 → 降信用分（交由支付平台）→ <b>平台内</b>黑名单。</li>
 *   <li>CB-011：抄共享充电宝阶梯（累计 3 次 → 谨慎名单 → 冻结该平台免密能力）；
 *       <b>我们不是持牌机构，不得自行上报征信</b>。</li>
 * </ul>
 *
 * <p>🔴 合规红线：本服务只做**平台内行为限制**（限制再次开柜/免密先享），
 * <b>绝不</b>对外宣称上报征信，也<b>绝不</b>做跨商户/全平台黑名单 ——
 * 无数据共享协议、无持牌资质，越界即「曝光隐私/跨平台惩戒」的法律风险。
 */
@Service
public class UnpaidDunningService {

    private static final Logger log = LoggerFactory.getLogger(UnpaidDunningService.class);
    private static final String ORDER = "ORDER";

    /** 催缴结果：本次是否真的发出了短信（模板缺失/无手机号 ⇒ false）。 */
    public record DunningResult(boolean smsSent, String channel, String message) {
    }

    /** 阶梯阈值：CB-011「累计 3 次未还进谨慎名单」。 */
    public static final int TIER_REMIND = 1;
    public static final int TIER_RESTRICT = 2;
    public static final int TIER_RESTRICT_PREAUTH = 3;
    /** 进入 TIER_RESTRICT 的累计次数。 */
    public static final int THRESHOLD_RESTRICT = 2;
    /** 进入 TIER_RESTRICT_PREAUTH 的累计次数。 */
    public static final int THRESHOLD_RESTRICT_PREAUTH = 3;
    /** 催缴最小间隔（小时）：竞品是「每天」，我们取 24h 避免同一用户被短信轰炸。 */
    private static final int REMIND_INTERVAL_HOURS = 24;

    private final UnpaidDunningRecordMapper dunningRepository;
    private final UserInfoMapper userInfoRepository;
    private final NotificationService notificationService;

    public UnpaidDunningService(UnpaidDunningRecordMapper dunningRepository,
                                UserInfoMapper userInfoRepository,
                                NotificationService notificationService) {
        this.dunningRepository = dunningRepository;
        this.userInfoRepository = userInfoRepository;
        this.notificationService = notificationService;
    }

    /**
     * 记录一次欠款关单并推进阶梯。
     *
     * <p>🔴 绝不抛异常：它跑在欠款处理链路里，抛异常会把「欠款」升级成「关单失败」。
     * 欠款是**可继续追缴的正常状态**（与 {@code RiskControlService.onUnpaidOrderCreated} 同纪律）。
     *
     * @return 推进后的阶梯等级；未发生异常
     */
    @Transactional
    public int recordUnpaidAndEscalate(Long userId) {
        if (userId == null) {
            return 0;
        }
        try {
            UnpaidDunningRecord record = dunningRepository.findByIdForUpdate(userId)
                    .orElseGet(UnpaidDunningRecord::new);
            if (record.getUserId() == null) {
                record.setUserId(userId);
            }
            int count = (record.getUnpaidCount() == null ? 0 : record.getUnpaidCount()) + 1;
            record.setUnpaidCount(count);
            record.setTier(tierFor(count));
            record.setLastDunningAt(Instant.now());
            record.setUpdatedAt(Instant.now());
            dunningRepository.save(record);
            log.info("unpaid escalated userId={} count={} tier={}", userId, count, record.getTier());
            notifyRestrictionIfNeeded(userId, count, record.getTier());
            return record.getTier();
        } catch (Exception ex) {
            // 阶梯是「加强管控」的辅助信号，记不上不该阻断欠款处理本身
            log.warn("unpaid dunning escalate failed userId={}", userId, ex);
            return 0;
        }
    }

    /**
     * 阶梯判据（纯函数，便于测试与口径复核）。
     *
     * <p>🔴 {@code count <= 0} 必须返回 <b>0（无）</b>，不是 1（提醒）：
     * 0 次欠款的用户就是正常用户，标成「提醒」会让「有多少用户处于催缴态」这个
     * 运营问题得到错误答案（同 铁律 31 的种子数据判据：零行 vs 有行，答案完全不同）。
     */
    public static int tierFor(int unpaidCount) {
        if (unpaidCount <= 0) {
            return 0;
        }
        if (unpaidCount >= THRESHOLD_RESTRICT_PREAUTH) {
            return TIER_RESTRICT_PREAUTH;
        }
        if (unpaidCount >= THRESHOLD_RESTRICT) {
            return TIER_RESTRICT;
        }
        return TIER_REMIND;
    }

    /**
     * 欠款还清后阶梯降级（自愈）。
     *
     * <p>为什么要降级而不是直接删：留着计数让「反复逃单」可被识别；
     * 但<b>必须让用户能通过还清恢复使用</b>，否则等于永久失信（与 CB-011 的
     * 「冻结的是该平台免密能力、不是征信」一致）。
     */
    @Transactional
    public void onUnpaidCleared(Long userId) {
        if (userId == null) {
            return;
        }
        try {
            dunningRepository.findByIdForUpdate(userId).ifPresent(record -> {
                record.setUnpaidCount(0);
                record.setTier(0);
                record.setUpdatedAt(Instant.now());
                dunningRepository.save(record);
                log.info("unpaid dunning cleared userId={}", userId);
            });
        } catch (Exception ex) {
            log.warn("unpaid dunning clear failed userId={}", userId, ex);
        }
    }

    /** 当前阶梯等级；无记录视为 0。 */
    public int currentTier(Long userId) {
        if (userId == null) {
            return 0;
        }
        return dunningRepository.findByUserId(userId)
                .map(r -> r.getTier() == null ? 0 : r.getTier())
                .orElse(0);
    }

    /** 该用户是否已达「限制免密先享」阶梯（供开门/下单前置校验调用）。 */
    public boolean isPreauthRestricted(Long userId) {
        return currentTier(userId) >= TIER_RESTRICT_PREAUTH;
    }

    /**
     * 发催缴短信（走既有通知链路：模板 channels 含 SMS 才会真发）。
     *
     * @param force 运营手动催缴时为 true，跳过「24h 内已催过」节流（人工催缴应即时生效）
     */
    public DunningResult remindBySms(CabinetOrder order, boolean force) {
        if (order == null || order.getUserId() == null) {
            return new DunningResult(false, "NONE", "订单无用户，无法催缴");
        }
        Long userId = order.getUserId();
        UserInfo user = userInfoRepository.findById(userId).orElse(null);
        if (user == null || user.getPhoneNumber() == null || user.getPhoneNumber().isBlank()) {
            // 登录要短信验证码 ⇒ user_info 必有手机号；走到这里说明数据异常，值得运营知道
            log.warn("dunning skipped: no phone userId={} orderId={}", userId, order.getOrderId());
            return new DunningResult(false, "NONE", "用户无手机号（异常：登录需短信验证码），请线下触达");
        }
        if (!force && recentlyReminded(userId)) {
            return new DunningResult(false, "NONE", "24 小时内已催缴过，请稍后再试");
        }
        String amount = BigDecimal.valueOf(order.getTotalAmountCents(), 2).stripTrailingZeros().toPlainString();
        try {
            notificationService.notifyConsumer(
                    userId,
                    "unpaid_order_dunning",
                    Map.of("orderId", order.getOrderId(), "amount", amount),
                    ORDER,
                    order.getOrderId());
            markReminded(userId);
            // notifyConsumer 内部已按模板 channels 决定是否真发短信并落库；
            // 短信总开关（aicabinet.notify.sms-enabled）关闭时它会只落站内信。
            log.info("dunning notified userId={} orderId={} tier={}",
                    userId, order.getOrderId(), currentTier(userId));
            return new DunningResult(true, "SMS", "催缴通知已发送（短信需 sms-enabled 开启）");
        } catch (Exception ex) {
            log.warn("dunning notify failed userId={} orderId={}", userId, order.getOrderId(), ex);
            return new DunningResult(false, "NONE", "催缴发送失败，已记日志");
        }
    }

    private void notifyRestrictionIfNeeded(Long userId, int count, int tier) {
        if (tier < TIER_RESTRICT_PREAUTH) {
            return;
        }
        try {
            notificationService.notifyConsumer(
                    userId,
                    "unpaid_order_blacklisted",
                    Map.of("unpaidCount", String.valueOf(count)),
                    ORDER,
                    null);
        } catch (Exception ex) {
            log.warn("restriction notify failed userId={}", userId, ex);
        }
    }

    private boolean recentlyReminded(Long userId) {
        return dunningRepository.findByUserId(userId)
                .map(r -> r.getLastRemindedAt() != null
                        && r.getLastRemindedAt().isAfter(Instant.now().minus(REMIND_INTERVAL_HOURS, ChronoUnit.HOURS)))
                .orElse(false);
    }

    private void markReminded(Long userId) {
        try {
            UnpaidDunningRecord record = dunningRepository.findByUserId(userId)
                    .orElseGet(UnpaidDunningRecord::new);
            if (record.getUserId() == null) {
                record.setUserId(userId);
            }
            record.setLastRemindedAt(Instant.now());
            record.setUpdatedAt(Instant.now());
            dunningRepository.save(record);
        } catch (Exception ex) {
            log.warn("mark reminded failed userId={}", userId, ex);
        }
    }
}
