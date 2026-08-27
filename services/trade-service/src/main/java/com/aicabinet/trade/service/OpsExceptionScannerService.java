package com.aicabinet.trade.service;

import com.aicabinet.common.enums.SessionState;
import com.aicabinet.trade.config.OpsMonitoringProperties;
import com.aicabinet.trade.domain.ShoppingSession;
import com.aicabinet.trade.mapper.ShoppingSessionMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Service
public class OpsExceptionScannerService {
    private static final String OPS_EXCEPTION_SCANNER = "ops-exception-scanner";

    /** 单次扫描每种状态最多处理条数，避免积压时一次打爆库。 */
    private static final int SCAN_BATCH = 500;

    private final ShoppingSessionMapper sessionRepository;
    private final OpsExceptionService exceptionService;
    private final OpsMonitoringProperties properties;
    private final ScheduledTaskService taskService;
    private final SystemConfigService systemConfigService;

    public OpsExceptionScannerService(ShoppingSessionMapper sessionRepository,
                                      OpsExceptionService exceptionService,
                                      OpsMonitoringProperties properties,
                                      ScheduledTaskService taskService,
                                      SystemConfigService systemConfigService) {
        this.sessionRepository = sessionRepository;
        this.exceptionService = exceptionService;
        this.properties = properties;
        this.taskService = taskService;
        this.systemConfigService = systemConfigService;
    }

    @Scheduled(fixedDelayString = "${aicabinet.ops-monitoring.scan-interval-ms:30000}")
    public void scan() {
        long start = System.nanoTime();
        if (!taskService.tryBegin(OPS_EXCEPTION_SCANNER, 600)) {
            return;
        }
        boolean failed = false;
        String summary = "运维监控未启用";
        try {
        if (!properties.enabled()) {
            summary = "运维监控未启用";
            return;
        }
        int door = scanDoorOpen();
            int upload = scanUpdatedState(SessionState.WAITING_UPLOAD,
                    systemConfigService.getInt(SystemConfigService.OPS_SCAN_UPLOAD_STUCK_MINUTES,
                            properties.uploadStuckMinutes()),
                    "UPLOAD_STUCK", "HIGH", "视频上传滞留");
            int recognizing = scanUpdatedState(SessionState.RECOGNIZING,
                    systemConfigService.getInt(SystemConfigService.OPS_SCAN_RECOGNITION_STUCK_MINUTES,
                            properties.recognitionStuckMinutes()),
                    "RECOGNITION_STUCK", "HIGH", "商品识别滞留");
            int settling = scanUpdatedState(SessionState.SETTLING,
                    systemConfigService.getInt(SystemConfigService.OPS_SCAN_SETTLEMENT_STUCK_MINUTES,
                            properties.settlementStuckMinutes()),
                    "SETTLEMENT_STUCK", "CRITICAL", "订单结算滞留");
            summary = "开门滞留 " + door + "，上传 " + upload + "，识别 " + recognizing + "，结算 " + settling;
    } catch (Exception e) {
        failed = true;
        taskService.finish(OPS_EXCEPTION_SCANNER, "FAILED", e.getMessage(), start);
        throw e;
    } finally {
        if (!failed) {
            taskService.finish(OPS_EXCEPTION_SCANNER, "SUCCESS", summary, start);
        }
    }
    }

    private int scanDoorOpen() {
        Instant cutoff = Instant.now().minus(
                systemConfigService.getInt(SystemConfigService.OPS_SCAN_DOOR_OPEN_MINUTES,
                        properties.doorOpenMinutes()),
                ChronoUnit.MINUTES);
        int n = 0;
        for (ShoppingSession session : sessionRepository.findByStateAndOpenTimeBefore(
                SessionState.SHOPPING, cutoff, SCAN_BATCH)) {
            report(session, "DOOR_OPEN_TOO_LONG", "CRITICAL",
                    "柜门长时间未关闭", "柜门开启超过 " + properties.doorOpenMinutes() + " 分钟");
            n++;
        }
        return n;
    }

    private int scanUpdatedState(SessionState state, int minutes, String type,
                                  String severity, String title) {
        Instant cutoff = Instant.now().minus(minutes, ChronoUnit.MINUTES);
        int n = 0;
        for (ShoppingSession session : sessionRepository.findByStateAndUpdatedAtBefore(
                state, cutoff, SCAN_BATCH)) {
            report(session, type, severity, title,
                    "会话在 " + state.name() + " 状态停留超过 " + minutes + " 分钟");
            n++;
        }
        return n;
    }

    private void report(ShoppingSession session, String type, String severity,
                        String title, String detail) {
        exceptionService.report(type, severity, new OpsExceptionService.ExceptionReport.ExceptionRefs(session.getDeviceId(), session.getSessionId(), session.getOrderId(), session.getUserId()), title, detail);
    }
}
