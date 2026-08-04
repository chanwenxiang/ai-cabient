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
    /** 单次扫描每种状态最多处理条数，避免积压时一次打爆库。 */
    private static final int SCAN_BATCH = 500;

    private final ShoppingSessionMapper sessionRepository;
    private final OpsExceptionService exceptionService;
    private final OpsMonitoringProperties properties;

    public OpsExceptionScannerService(ShoppingSessionMapper sessionRepository,
                                      OpsExceptionService exceptionService,
                                      OpsMonitoringProperties properties) {
        this.sessionRepository = sessionRepository;
        this.exceptionService = exceptionService;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${aicabinet.ops-monitoring.scan-interval-ms:30000}")
    public void scan() {
        if (!properties.enabled()) return;
        scanDoorOpen();
        scanUpdatedState(SessionState.WAITING_UPLOAD, properties.uploadStuckMinutes(),
                "UPLOAD_STUCK", "HIGH", "视频上传滞留");
        scanUpdatedState(SessionState.RECOGNIZING, properties.recognitionStuckMinutes(),
                "RECOGNITION_STUCK", "HIGH", "商品识别滞留");
        scanUpdatedState(SessionState.SETTLING, properties.settlementStuckMinutes(),
                "SETTLEMENT_STUCK", "CRITICAL", "订单结算滞留");
    }

    private void scanDoorOpen() {
        Instant cutoff = Instant.now().minus(properties.doorOpenMinutes(), ChronoUnit.MINUTES);
        for (ShoppingSession session : sessionRepository.findByStateAndOpenTimeBefore(
                SessionState.SHOPPING, cutoff, SCAN_BATCH)) {
            report(session, "DOOR_OPEN_TOO_LONG", "CRITICAL",
                    "柜门长时间未关闭", "柜门开启超过 " + properties.doorOpenMinutes() + " 分钟");
        }
    }

    private void scanUpdatedState(SessionState state, int minutes, String type,
                                  String severity, String title) {
        Instant cutoff = Instant.now().minus(minutes, ChronoUnit.MINUTES);
        for (ShoppingSession session : sessionRepository.findByStateAndUpdatedAtBefore(
                state, cutoff, SCAN_BATCH)) {
            report(session, type, severity, title,
                    "会话在 " + state.name() + " 状态停留超过 " + minutes + " 分钟");
        }
    }

    private void report(ShoppingSession session, String type, String severity,
                        String title, String detail) {
        exceptionService.report(type, severity, session.getDeviceId(), session.getSessionId(),
                session.getOrderId(), session.getUserId(), title, detail);
    }
}
