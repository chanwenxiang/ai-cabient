package com.aicabinet.trade.service;

import com.aicabinet.common.enums.SessionState;
import com.aicabinet.trade.domain.ShoppingSession;
import com.aicabinet.trade.mapper.ShoppingSessionMapper;
import com.aicabinet.trade.support.SessionLogContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * 将邑识别超时即时处置（CB-022）：gateway 在「识别上报到达但 class 映射未命中」
 * 时经内部接口触发，立即把 RECOGNIZING 会话转人工审核，不等 10 分钟扫描兜底。
 *
 * <p>状态迁移语义对齐既有识别超时路径（SessionExpireService.upgradeConsumerRecognizingTimeout，
 * C09）：释放预授权冻结 → 转 DISPUTED → 开超时争议单；DISPUTED 不可达则回退 FAILED。
 * 补货会话（isRestockSession）不在此处置——其超时语义不同（转 COMPLETED 快照关闭），
 * 交给既有扫描兜底，本服务直接跳过。</p>
 *
 * <p><b>模式一无关门上报 ⇒ 本服务也负责合成关门</b>：将邑会话可能还停在 SHOPPING 就收到设备
 * 对本单的终局上报（{@code device/pokerOrder/uploadOrderError} 的「再次确认/人工复核」，
 * 或 300s CLOSE watchdog——识别上报压根没来过）。原实现只认 RECOGNIZING，于是这两路都静默
 * no-op、watchdog 还顺手 evict 掉登记 ⇒ 会话永远卡在 SHOPPING，消费者只能手点「关门结算」
 * 才出记录（2026-10-21 真机 CQYB10720 四单实测）。现对将邑柜先补 closeTime + SHOPPING→
 * RECOGNIZING 再走既有升级，处置结论（DISPUTED + 争议单 + 本次不扣款）不变。</p>
 *
 * <p>幂等：加会话锁 + 行锁后校验 state==RECOGNIZING 才动作；非 RECOGNIZING
 * （已结算/已取消/已争议）一律返回 false 不重复处置。</p>
 *
 * <p><b>成因分档（2026-10-21，真机 CQYB10720 18:11 单实测）</b>：四条链路都收敛到本方法
 * （映射未命中 / forms 空 / 设备 {@code uploadOrderError} 的 errorMsg「人工复核」/ watchdog
 * 窗口内无上报），旧实现却一律写成「classId 无法解析到我方 SKU」并挂
 * {@code JIANGYI_CLASS_MAPPING_MISS}——设备根本没回识别上报的单被记成映射问题，排查时看不到
 * 厂商到底回了什么。现由 gateway 透传 {@code cause}+{@code vendorDetail}，成因文案与异常类型
 * 分档，设备原文截断后落 reason/fail_reason/detail。处置结论（DISPUTED + 争议单 + 本次不扣款）
 * 与资金语义一行不变；🔴 各档文案仍以「识别超时」开头——自动免单门控按该前缀筛单。</p>
 */
@Service
public class JiangyiRecognitionTimeoutService {

    private static final Logger log = LoggerFactory.getLogger(JiangyiRecognitionTimeoutService.class);

    /** 识别未成功成因——🔴 与 gateway {@code TradeInternalClient.CAUSE_*} 同字面量，改一侧必须改另一侧。 */
    public static final String CAUSE_CLASS_MAPPING_MISS = "CLASS_MAPPING_MISS";
    public static final String CAUSE_EMPTY_FORMS = "EMPTY_FORMS";
    public static final String CAUSE_DEVICE_ERROR_ORDER = "DEVICE_ERROR_ORDER";
    public static final String CAUSE_NO_REPORT = "NO_REPORT";
    /** V400：当前 classes 版本未经人工复核（重训平移/未知模型回执撤销）——一律不结算。 */
    public static final String CAUSE_MAPPING_UNVERIFIED = "MAPPING_UNVERIFIED";

    /** 设备原文落库上限：reason/fail_reason 列 256，成因文案另留余量。 */
    private static final int VENDOR_DETAIL_MAX = 120;

    private final ShoppingSessionMapper repository;
    private final SessionService sessionService;
    private final DisputeService disputeService;
    private final ConsumerPreauthService consumerPreauthService;
    private final DistributedLockService distributedLockService;
    private final OpsExceptionService opsExceptionService;
    private final JiangyiDeviceDirectory jiangyiDeviceDirectory;

    public JiangyiRecognitionTimeoutService(ShoppingSessionMapper repository,
                                            @Lazy SessionService sessionService,
                                            @Lazy DisputeService disputeService,
                                            ConsumerPreauthService consumerPreauthService,
                                            DistributedLockService distributedLockService,
                                            OpsExceptionService opsExceptionService,
                                            JiangyiDeviceDirectory jiangyiDeviceDirectory) {
        this.repository = repository;
        this.sessionService = sessionService;
        this.disputeService = disputeService;
        this.consumerPreauthService = consumerPreauthService;
        this.distributedLockService = distributedLockService;
        this.opsExceptionService = opsExceptionService;
        this.jiangyiDeviceDirectory = jiangyiDeviceDirectory;
    }

    /**
     * AU-060：reportingDeviceId 非空时须与会话绑定设备一致——将邑转发链透传 token deviceId，
     * 他人设备不得把受害会话转 DISPUTED；null 保留内部调用语义。
     *
     * <p>{@code cause} 缺省/未知一律按 {@link #CAUSE_NO_REPORT} 处置（旧口径），
     * {@code vendorDetail} 为设备原文（uploadOrderError 的 errorMsg 或未命中的 classId 列表），
     * 截断后写进 fail_reason / 争议单 reason / 异常单 detail。</p>
     *
     * @return true = 本次由本服务完成升级（转 DISPUTED/FAILED）；
     *         false = 会话不存在 / 不在 RECOGNIZING / 补货会话（无需或不应处置）。
     */
    public boolean markRecognitionTimeout(String sessionId, String reportingDeviceId,
                                          String cause, String vendorDetail) {
        if (!distributedLockService.tryLock(SessionService.sessionLifeLockKey(sessionId), 30, 0)) {
            log.debug("jiangyi recognize-timeout skipped busy session={}", sessionId);
            return false;
        }
        try {
            ShoppingSession locked = repository.findByIdForUpdate(sessionId).orElse(null);
            if (locked == null) {
                return false;
            }
            // AU-060：非会话绑定设备的 recognize-timeout 一律拒绝（DEVICE_MISMATCH 口径）
            if (reportingDeviceId != null && !reportingDeviceId.equals(locked.getDeviceId())) {
                log.warn("jiangyi recognize-timeout DEVICE_MISMATCH session={} bound={} reported={}",
                        sessionId, locked.getDeviceId(), reportingDeviceId);
                return false;
            }
            if (locked.getState() == SessionState.SHOPPING
                    && jiangyiDeviceDirectory.isJiangyi(locked.getDeviceId())
                    && !DeviceValidationService.isRestockSession(locked)
                    && !DeviceValidationService.isOpsRemoteSession(locked)) {
                // 模式一无关门上报：设备已对本单给出终局结论（异常单/甄别不回来），
                // 关门由本服务合成——close_time 取上报到达时刻，非真实门磁时间。
                if (locked.getCloseTime() == null) {
                    locked.setCloseTime(Instant.now());
                }
                log.warn("jiangyi recognize-timeout synthesizing close {} — 模式一无关门上报",
                        SessionLogContext.of(locked));
                sessionService.transition(locked, SessionState.RECOGNIZING);
            }
            if (locked.getState() != SessionState.RECOGNIZING) {
                // 幂等：已结算/已取消/已争议等一律不重复处置
                return false;
            }
            if (DeviceValidationService.isRestockSession(locked)) {
                log.info("jiangyi recognize-timeout skipped restock session {}",
                        SessionLogContext.of(locked));
                return false;
            }
            // 审计 T7（2026-10-15）：文案必须以「识别超时」开头——自动免单门控按 reason 前缀
            // findOpenTimeoutUnclaimedCreatedBefore（DisputeTicketMapper.likeRight）筛超时单，
            // 旧文案「识别结果无法匹配商品…」会让将邑超时单永远进不了 P3-4 自动免单兜底。
            // 分档只改括号内的成因与后续异常类型，前缀一律保留。
            String normalizedCause = normalizeCause(cause);
            String vendor = truncateVendor(vendorDetail);
            String causePhrase = causePhrase(normalizedCause);
            String failReason = withVendor(causePhrase + "，已转人工审核，本次暂未扣款", vendor);
            // C09：升级前释放预授权冻结；失败只记 error 并告警，不阻断状态迁移
            try {
                consumerPreauthService.releaseIfFrozen(locked);
            } catch (Exception e) {
                log.error("将邑识别超时释放预授权失败 sessionId={}", locked.getSessionId(), e);
            }
            if (locked.getState().canTransitionTo(SessionState.DISPUTED)) {
                locked.setFailReason(failReason);
                if (locked.getCloseTime() == null) {
                    locked.setCloseTime(Instant.now());
                }
                sessionService.transition(locked, SessionState.DISPUTED);
                try {
                    disputeService.createTimeoutTicket(locked, failReason);
                } catch (Exception ticketEx) {
                    log.warn("将邑超时争议单创建失败 {}", SessionLogContext.of(locked), ticketEx);
                }
            } else {
                locked.setFailReason(withVendor(causePhrase, vendor));
                sessionService.transition(locked, SessionState.FAILED);
            }
            opsExceptionService.report(
                    exceptionType(normalizedCause),
                    "HIGH",
                    new OpsExceptionService.ExceptionReport.ExceptionRefs(
                            locked.getDeviceId(), locked.getSessionId(),
                            locked.getOrderId(), locked.getUserId()),
                    exceptionTitle(normalizedCause),
                    exceptionDetail(normalizedCause, vendor));
            log.warn("将邑识别超时会话已升级 {} cause={} vendorDetail={} state={}",
                    SessionLogContext.of(locked), normalizedCause, vendor, locked.getState());
            return true;
        } finally {
            distributedLockService.unlock(SessionService.sessionLifeLockKey(sessionId));
        }
    }

    private static String normalizeCause(String cause) {
        String c = cause == null ? "" : cause.trim().toUpperCase();
        return switch (c) {
            case CAUSE_CLASS_MAPPING_MISS, CAUSE_EMPTY_FORMS, CAUSE_DEVICE_ERROR_ORDER,
                 CAUSE_NO_REPORT, CAUSE_MAPPING_UNVERIFIED -> c;
            default -> CAUSE_NO_REPORT;
        };
    }

    /** 成因短语（含「识别超时」前缀，自动免单门控判据）。 */
    private static String causePhrase(String cause) {
        return switch (cause) {
            case CAUSE_CLASS_MAPPING_MISS -> "识别超时（上报的 classId 未挂到我方 SKU）";
            case CAUSE_EMPTY_FORMS -> "识别超时（设备回传识别结果为空，未识别到商品）";
            case CAUSE_DEVICE_ERROR_ORDER -> "识别超时（设备上报异常订单）";
            case CAUSE_MAPPING_UNVERIFIED -> "识别超时（映射未按柜机当前模型人工复核，暂停结算）";
            default -> "识别超时（将邑端识别结果超时未回传）";
        };
    }

    private static String exceptionType(String cause) {
        return switch (cause) {
            case CAUSE_CLASS_MAPPING_MISS -> "JIANGYI_CLASS_MAPPING_MISS";
            // forms 空 / 设备异常单 / 映射未复核 = 「识别结果需人工审核」，与非将邑柜
            // SessionSettleService 同型，后台据此才给得出人工处置/重试入口
            // （旧实现挂 JIANGYI_CLASS_MAPPING_MISS 落在白名单外）。
            case CAUSE_EMPTY_FORMS, CAUSE_DEVICE_ERROR_ORDER, CAUSE_MAPPING_UNVERIFIED ->
                    "RECOGNITION_FAILED";
            default -> "RECOGNITION_TIMEOUT";
        };
    }

    private static String exceptionTitle(String cause) {
        return switch (cause) {
            case CAUSE_CLASS_MAPPING_MISS -> "将邑识别映射未命中";
            case CAUSE_EMPTY_FORMS -> "将邑识别结果为空";
            case CAUSE_DEVICE_ERROR_ORDER -> "将邑设备上报异常订单";
            case CAUSE_MAPPING_UNVERIFIED -> "将邑识别映射未复核";
            default -> "将邑识别结果超时未回传";
        };
    }

    private static String exceptionDetail(String cause, String vendor) {
        String tail = "，会话已 fail-closed 转人工审核";
        return switch (cause) {
            case CAUSE_CLASS_MAPPING_MISS -> "识别上报的 classId 无法解析到我方 SKU（"
                    + (vendor.isEmpty() ? "无上报行" : vendor) + ")" + tail;
            case CAUSE_EMPTY_FORMS -> "设备回传识别 forms 为空（未识别到商品）" + tail;
            case CAUSE_DEVICE_ERROR_ORDER -> "设备 uploadOrderError errorMsg："
                    + (vendor.isEmpty() ? "（设备未给 errorMsg）" : vendor) + tail;
            case CAUSE_MAPPING_UNVERIFIED -> "柜机当前 classes 版本未经人工复核（重训平移或未知模型回执），"
                    + "按错位映射结算会错扣款" + tail;
            default -> "识别上报在 watchdog 窗口（关门 300s / 大模型复核 600s）内未到达" + tail;
        };
    }

    private static String withVendor(String base, String vendor) {
        return vendor.isEmpty() ? base : base + "｜设备原文：" + vendor;
    }

    private static String truncateVendor(String value) {
        String v = value == null ? "" : value.trim();
        return v.length() <= VENDOR_DETAIL_MAX ? v : v.substring(0, VENDOR_DETAIL_MAX) + "…";
    }
}
