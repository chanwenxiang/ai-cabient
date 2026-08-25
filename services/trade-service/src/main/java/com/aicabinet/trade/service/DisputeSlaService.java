package com.aicabinet.trade.service;

import com.aicabinet.trade.config.DisputeSlaProperties;
import com.aicabinet.trade.mapper.DisputeTicketMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Service
public class DisputeSlaService {

    private final DisputeTicketMapper disputeRepository;
    private final DisputeSlaProperties disputeSlaProperties;

    @Autowired
    private SystemConfigService systemConfigService;

    public DisputeSlaService(DisputeTicketMapper disputeRepository,
                             DisputeSlaProperties disputeSlaProperties) {
        this.disputeRepository = disputeRepository;
        this.disputeSlaProperties = disputeSlaProperties;
    }

    @Transactional(readOnly = true)
    public long countOverdue() {
        return disputeRepository.countOverdue(Instant.now());
    }

    @Transactional(readOnly = true)
    public long countNearSla() {
        Instant now = Instant.now();
        Instant threshold = now.plus(
                systemConfigService.getInt(SystemConfigService.DISPUTE_SLA_REMINDER_HOURS,
                        disputeSlaProperties.reminderHoursBefore()),
                ChronoUnit.HOURS);
        return disputeRepository.countNearSla(now, threshold);
    }

    @Transactional(readOnly = true)
    public double slaComplianceRate24h() {
        Instant since = Instant.now().minus(24, ChronoUnit.HOURS);
        long resolved = disputeRepository.countResolvedSince(since);
        if (resolved == 0) {
            // 无已解决争议时不展示 100%，避免空样本误导（与开门成功率口径一致）
            return 0.0;
        }
        long within = disputeRepository.countResolvedWithinSlaSince(since);
        return (double) within / resolved;
    }
}
