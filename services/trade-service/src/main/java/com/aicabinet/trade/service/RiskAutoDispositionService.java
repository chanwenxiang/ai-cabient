package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.RiskEvent;
import com.aicabinet.trade.mapper.RiskEventMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 低优：风控事件自动处置——INFO 自动结清、WARN 超时自动确认留痕，减轻人工积压。
 */
@Service
public class RiskAutoDispositionService {

    private static final Logger log = LoggerFactory.getLogger(RiskAutoDispositionService.class);

    private final RiskEventMapper riskEventRepository;
    private final SystemConfigService systemConfigService;

    public RiskAutoDispositionService(RiskEventMapper riskEventRepository,
                                      SystemConfigService systemConfigService) {
        this.riskEventRepository = riskEventRepository;
        this.systemConfigService = systemConfigService;
    }

    @Scheduled(fixedDelayString = "${aicabinet.risk.auto-disposition-ms:900000}", initialDelay = 120_000)
    @Transactional
    public void runScheduled() {
        int cleared = autoClearInfo();
        int acked = autoAckWarn();
        if (cleared > 0 || acked > 0) {
            log.info("risk auto-disposition cleared={} acked={}", cleared, acked);
        }
    }

    /** INFO 超龄 → AUTO_CLEARED */
    public int autoClearInfo() {
        int hours = systemConfigService.getInt("risk.auto_clear_info_hours", 72);
        if (hours <= 0) {
            return 0;
        }
        Instant before = Instant.now().minus(hours, ChronoUnit.HOURS);
        List<RiskEvent> open = riskEventRepository.selectList(Wrappers.<RiskEvent>lambdaQuery()
                .eq(RiskEvent::getDispositionStatus, "OPEN")
                .eq(RiskEvent::getSeverity, "INFO")
                .lt(RiskEvent::getCreatedAt, before)
                .last("LIMIT 200"));
        Instant now = Instant.now();
        for (RiskEvent e : open) {
            e.setDispositionStatus("AUTO_CLEARED");
            e.setDispositionAt(now);
            e.setDispositionNote("INFO 超 " + hours + "h 自动结清");
            riskEventRepository.updateById(e);
        }
        return open.size();
    }

    /** WARN 超龄 → ACKED（不拉黑，仅留痕） */
    public int autoAckWarn() {
        int hours = systemConfigService.getInt("risk.auto_ack_warn_hours", 168);
        if (hours <= 0) {
            return 0;
        }
        Instant before = Instant.now().minus(hours, ChronoUnit.HOURS);
        List<RiskEvent> open = riskEventRepository.selectList(Wrappers.<RiskEvent>lambdaQuery()
                .eq(RiskEvent::getDispositionStatus, "OPEN")
                .eq(RiskEvent::getSeverity, "WARN")
                .lt(RiskEvent::getCreatedAt, before)
                .last("LIMIT 200"));
        Instant now = Instant.now();
        for (RiskEvent e : open) {
            e.setDispositionStatus("ACKED");
            e.setDispositionAt(now);
            e.setDispositionNote("WARN 超 " + hours + "h 自动确认");
            riskEventRepository.updateById(e);
        }
        return open.size();
    }
}
