package com.aicabinet.trade.service;

import com.aicabinet.common.dto.PaymentPlatformBillLineDto;
import com.aicabinet.common.dto.PaymentReconciliationDetailDto;
import com.aicabinet.common.dto.PaymentReconciliationDto;
import com.aicabinet.trade.domain.PaymentPlatformBillLine;
import com.aicabinet.trade.domain.PaymentReconciliation;
import com.aicabinet.trade.reconciliation.PlatformBillLine;
import com.aicabinet.trade.reconciliation.PlatformBillProviderRegistry;
import com.aicabinet.trade.metrics.CabinetMetrics;
import com.aicabinet.trade.repository.CabinetOrderRepository;
import com.aicabinet.trade.repository.PaymentPlatformBillLineRepository;
import com.aicabinet.trade.repository.PaymentReconciliationRepository;
import com.aicabinet.trade.repository.RechargeOrderRepository;
import com.aicabinet.trade.support.ApiMessages;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    private final PaymentReconciliationRepository reconRepository;
    private final PaymentPlatformBillLineRepository billLineRepository;
    private final CabinetOrderRepository orderRepository;
    private final RechargeOrderRepository rechargeRepository;
    private final PlatformBillProviderRegistry billProviderRegistry;
    private final ObjectMapper objectMapper;
    private final CabinetMetrics cabinetMetrics;

    public ReconciliationService(PaymentReconciliationRepository reconRepository,
                                 PaymentPlatformBillLineRepository billLineRepository,
                                 CabinetOrderRepository orderRepository,
                                 RechargeOrderRepository rechargeRepository,
                                 PlatformBillProviderRegistry billProviderRegistry,
                                 ObjectMapper objectMapper,
                                 CabinetMetrics cabinetMetrics) {
        this.reconRepository = reconRepository;
        this.billLineRepository = billLineRepository;
        this.orderRepository = orderRepository;
        this.rechargeRepository = rechargeRepository;
        this.billProviderRegistry = billProviderRegistry;
        this.objectMapper = objectMapper;
        this.cabinetMetrics = cabinetMetrics;
    }

    @Transactional(readOnly = true)
    public List<PaymentReconciliationDto> list(Long operatorId, LocalDate from, LocalDate to) {
        LocalDate end = to != null ? to : LocalDate.now();
        LocalDate start = from != null ? from : end.minusDays(30);
        return reconRepository.findByReconDateBetweenOrderByReconDateDesc(start, end).stream()
                .map(this::toDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public PaymentReconciliationDetailDto getDetail(Long operatorId, Long reconId) {
        PaymentReconciliation recon = reconRepository.findById(reconId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, ApiMessages.RECONCILIATION_NOT_FOUND));
        List<PaymentPlatformBillLineDto> lines = billLineRepository.findByReconId(reconId).stream()
                .map(this::toLineDto)
                .toList();
        return new PaymentReconciliationDetailDto(toDto(recon), recon.getDetail(), lines);
    }

    @Transactional
    public PaymentReconciliationDto runDaily(Long operatorId, LocalDate date, String channel) {
        String ch = channel != null ? channel.toUpperCase() : "WECHAT";
        return reconRepository.findByReconDateAndChannel(date, ch)
                .map(this::toDto)
                .orElseGet(() -> toDto(doReconcile(date, ch)));
    }

    private PaymentReconciliation doReconcile(LocalDate date, String channel) {
        ZoneId zone = ZoneId.systemDefault();
        Instant start = date.atStartOfDay(zone).toInstant();
        Instant end = date.plusDays(1).atStartOfDay(zone).toInstant();

        long ledgerTotal = sumLedger(start, end, channel);
        List<PlatformBillLine> platformLines = billProviderRegistry.fetchBill(channel, date);
        long platformTotal = platformLines.stream().mapToLong(PlatformBillLine::amountCents).sum();

        Set<String> ledgerOrderIds = collectLedgerOrderIds(start, end, channel);
        int matched = 0;
        int unmatched = 0;

        PaymentReconciliation recon = new PaymentReconciliation();
        recon.setReconDate(date);
        recon.setChannel(channel);
        recon.setLedgerTotal(ledgerTotal);
        recon.setPlatformTotal(platformTotal);
        recon.setDiffCents(platformTotal - ledgerTotal);
        recon = reconRepository.save(recon);

        for (PlatformBillLine line : platformLines) {
            boolean isMatched = line.merchantOrderNo() != null
                    && ledgerOrderIds.contains(line.merchantOrderNo());
            if (isMatched) {
                matched++;
            } else {
                unmatched++;
            }
            PaymentPlatformBillLine entity = new PaymentPlatformBillLine();
            entity.setReconId(recon.getReconId());
            entity.setChannel(channel);
            entity.setPlatformTradeNo(line.platformTradeNo());
            entity.setMerchantOrderNo(line.merchantOrderNo());
            entity.setAmountCents(line.amountCents());
            entity.setTradeTime(line.tradeTime());
            entity.setTradeType(line.tradeType());
            entity.setMatched(isMatched);
            entity.setRawDetail(line.rawDetail());
            billLineRepository.save(entity);
        }

        recon.setMatchedCount(matched);
        recon.setUnmatchedCount(unmatched);
        recon.setStatus(recon.getDiffCents() == 0 && unmatched == 0 ? "MATCHED" : "MISMATCH");
        if ("MISMATCH".equals(recon.getStatus())) {
            cabinetMetrics.recordReconciliationMismatch();
        }
        recon.setCompletedAt(Instant.now());
        try {
            Map<String, Object> detail = new HashMap<>();
            detail.put("platformLineCount", platformLines.size());
            detail.put("ledgerOrderCount", ledgerOrderIds.size());
            recon.setDetail(objectMapper.writeValueAsString(detail));
        } catch (Exception e) {
            log.warn("recon detail json failed", e);
        }
        log.info("reconciliation date={} channel={} platform={} ledger={} diff={} matched={} unmatched={}",
                date, channel, platformTotal, ledgerTotal, recon.getDiffCents(), matched, unmatched);
        return reconRepository.save(recon);
    }

    private long sumLedger(Instant start, Instant end, String channel) {
        long total = orderRepository.sumTotalAmountBetween(start, end);
        if ("WECHAT".equals(channel) || "MOCK".equals(channel) || "ALIPAY".equals(channel)) {
            total += rechargeRepository.sumPaidAmountBetween(start, end);
        }
        return total;
    }

    private Set<String> collectLedgerOrderIds(Instant start, Instant end, String channel) {
        Set<String> ids = new HashSet<>(orderRepository.findOrderIdsBetween(start, end));
        if ("WECHAT".equals(channel) || "MOCK".equals(channel) || "ALIPAY".equals(channel)) {
            ids.addAll(rechargeRepository.findPaidOrderIdsBetween(start, end));
        }
        return ids;
    }

    private PaymentPlatformBillLineDto toLineDto(PaymentPlatformBillLine line) {
        return new PaymentPlatformBillLineDto(
                line.getLineId(), line.getPlatformTradeNo(), line.getMerchantOrderNo(),
                line.getAmountCents(), line.getTradeTime(), line.getTradeType(), line.isMatched()
        );
    }

    private PaymentReconciliationDto toDto(PaymentReconciliation r) {
        return new PaymentReconciliationDto(
                r.getReconId(), r.getReconDate(), r.getChannel(),
                r.getPlatformTotal(), r.getLedgerTotal(), r.getDiffCents(),
                r.getMatchedCount(), r.getUnmatchedCount(), r.getStatus(), r.getCompletedAt()
        );
    }
}
