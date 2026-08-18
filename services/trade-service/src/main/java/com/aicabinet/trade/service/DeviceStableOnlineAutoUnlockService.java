package com.aicabinet.trade.service;

import com.aicabinet.common.enums.SessionState;
import com.aicabinet.trade.domain.DeviceInfo;
import com.aicabinet.trade.domain.OpsException;
import com.aicabinet.trade.domain.RepairTicket;
import com.aicabinet.trade.domain.ShoppingSession;
import com.aicabinet.trade.mapper.DeviceInfoMapper;
import com.aicabinet.trade.mapper.OpsExceptionMapper;
import com.aicabinet.trade.mapper.RepairTicketMapper;
import com.aicabinet.trade.mapper.ShoppingSessionMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

/**
 * 设备“稳定在线自动解锁”：离线自动锁机后，设备恢复在线并稳定运行一段时间，
 * 且无未结算会话、无未完结维修工单时，自动解除销售锁恢复营业。
 * <p>默认关闭，通过系统参数 {@link SystemConfigService#DEVICE_STABLE_ONLINE_AUTO_UNLOCK_ENABLED}
 * 开启；由 XXL-JOB 任务 deviceStableOnlineAutoUnlockJob 周期性触发。</p>
 */
@Service
public class DeviceStableOnlineAutoUnlockService {

    private static final Logger log = LoggerFactory.getLogger(DeviceStableOnlineAutoUnlockService.class);

    /** 会话未结束即存在未结算风险，自动解锁前该设备上必须没有这些状态的会话 */
    private static final List<SessionState> ACTIVE_SESSION_STATES = List.of(
            SessionState.CREATED, SessionState.OPENING, SessionState.SHOPPING,
            SessionState.RECOGNIZING, SessionState.WAITING_UPLOAD, SessionState.SETTLING);

    private static final List<String> OPEN_TICKET_STATES = List.of("OPEN", "IN_PROGRESS");
    private static final List<String> OPEN_FAULT_STATES = List.of("OPEN", "PROCESSING");
    private static final int BATCH_LIMIT = 200;

    private final SystemConfigService systemConfigService;
    private final DeviceInfoMapper deviceRepository;
    private final ShoppingSessionMapper sessionRepository;
    private final RepairTicketMapper ticketRepository;
    private final OpsExceptionMapper exceptionRepository;
    private final DeviceSalesLockService salesLockService;
    private final OpsExceptionService opsExceptionService;
    private final AdminAuditService auditService;

    public DeviceStableOnlineAutoUnlockService(SystemConfigService systemConfigService,
                                               DeviceInfoMapper deviceRepository,
                                               ShoppingSessionMapper sessionRepository,
                                               RepairTicketMapper ticketRepository,
                                               OpsExceptionMapper exceptionRepository,
                                               DeviceSalesLockService salesLockService,
                                               OpsExceptionService opsExceptionService,
                                               AdminAuditService auditService) {
        this.systemConfigService = systemConfigService;
        this.deviceRepository = deviceRepository;
        this.sessionRepository = sessionRepository;
        this.ticketRepository = ticketRepository;
        this.exceptionRepository = exceptionRepository;
        this.salesLockService = salesLockService;
        this.opsExceptionService = opsExceptionService;
        this.auditService = auditService;
    }

    /** 扫描锁机中且稳定在线超过配置分钟数的设备并自动解锁，返回本次解锁台数。 */
    @Transactional
    public int autoUnlockStableOnlineDevices() {
        boolean enabled = systemConfigService.getBoolean(
                SystemConfigService.DEVICE_STABLE_ONLINE_AUTO_UNLOCK_ENABLED, false);
        int stableMinutes = systemConfigService.getInt(
                SystemConfigService.DEVICE_STABLE_ONLINE_AUTO_UNLOCK_MINUTES, 15);
        if (!enabled || stableMinutes <= 0) {
            log.debug("device stable-online auto unlock disabled, enabled={}, minutes={}",
                    enabled, stableMinutes);
            return 0;
        }

        Instant cutoff = Instant.now().minus(stableMinutes, ChronoUnit.MINUTES);
        List<DeviceInfo> candidates = deviceRepository
                .findByOnlineStatusAndSalesLockedTrueAndOnlineSinceBefore("ONLINE", cutoff, BATCH_LIMIT);
        int unlocked = 0;
        for (DeviceInfo device : candidates) {
            if (!safeToUnlock(device)) {
                continue;
            }
            try {
                unlock(device);
                unlocked++;
            } catch (Exception e) {
                log.warn("stable-online auto unlock failed device={} err={}",
                        device.getDeviceId(), e.toString());
            }
        }
        if (unlocked > 0) {
            log.info("stable-online auto unlock done, unlocked={}", unlocked);
        }
        return unlocked;
    }

    /**
     * 自动解锁安全校验：
     * 1) 只处理存在未解决 OFFLINE_TIMEOUT 故障（DEVICE_FAULT）的设备，人工锁机不会被自动解锁；
     * 2) 无未完结维修工单；
     * 3) 无未结算购物会话。
     */
    private boolean safeToUnlock(DeviceInfo device) {
        Optional<OpsException> fault = exceptionRepository
                .findFirstByExceptionTypeAndDeviceIdAndStatusIn(
                        "DEVICE_FAULT", device.getDeviceId(), OPEN_FAULT_STATES);
        Optional<OpsException> offline = exceptionRepository
                .findFirstByExceptionTypeAndDeviceIdAndStatusIn(
                        "DEVICE_OFFLINE", device.getDeviceId(), OPEN_FAULT_STATES);
        if (fault.isEmpty() && offline.isEmpty()) {
            log.info("skip auto unlock device={} reason=no-open-offline-or-fault", device.getDeviceId());
            return false;
        }
        Long openTickets = ticketRepository.selectCount(Wrappers.<RepairTicket>lambdaQuery()
                .eq(RepairTicket::getDeviceId, device.getDeviceId())
                .in(RepairTicket::getStatus, OPEN_TICKET_STATES));
        if (openTickets != null && openTickets > 0) {
            log.info("skip auto unlock device={} reason=open-repair-ticket", device.getDeviceId());
            return false;
        }
        Long activeSessions = sessionRepository.selectCount(Wrappers.<ShoppingSession>lambdaQuery()
                .eq(ShoppingSession::getDeviceId, device.getDeviceId())
                .in(ShoppingSession::getState, ACTIVE_SESSION_STATES));
        if (activeSessions != null && activeSessions > 0) {
            log.info("skip auto unlock device={} reason=active-session", device.getDeviceId());
            return false;
        }
        return true;
    }

    private void unlock(DeviceInfo device) {
        salesLockService.applySalesLock(0L, device, false, "stable-online-auto-unlock", true);
        opsExceptionService.resolveSystem("DEVICE_FAULT", device.getDeviceId(),
                "设备恢复稳定在线后自动解锁起售");
        opsExceptionService.resolveSystem("DEVICE_OFFLINE", device.getDeviceId(),
                "设备恢复稳定在线后自动解锁起售");
        auditService.record(0L, "DEVICE_AUTO_UNLOCK_STABLE_ONLINE", "DEVICE", device.getDeviceId(),
                "恢复在线超过配置分钟数且无未结算会话/维修工单，自动解锁恢复营业");
        log.info("device auto unlocked after stable online device={}", device.getDeviceId());
    }
}
