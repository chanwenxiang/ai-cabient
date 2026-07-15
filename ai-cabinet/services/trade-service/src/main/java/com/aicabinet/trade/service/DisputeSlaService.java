package com.aicabinet.trade.service;

import com.aicabinet.trade.config.DisputeSlaProperties;
import com.aicabinet.trade.repository.DisputeTicketRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Service
public class DisputeSlaService {

    private final DisputeTicketRepository disputeRepository;
    private final DisputeSlaProperties disputeSlaProperties;

    public DisputeSlaService(DisputeTicketRepository disputeRepository,
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
        Instant threshold = now.plus(disputeSlaProperties.reminderHoursBefore(), ChronoUnit.HOURS);
        return disputeRepository.countNearSla(now, threshold);
    }

    @Transactional(readOnly = true)
    public double slaComplianceRate24h() {
        Instant since = Instant.now().minus(24, ChronoUnit.HOURS);
        long resolved = disputeRepository.countResolvedSince(since);
        if (resolved == 0) {
            return 1.0;
        }
        long within = disputeRepository.countResolvedWithinSlaSince(since);
        return (double) within / resolved;
    }
}
