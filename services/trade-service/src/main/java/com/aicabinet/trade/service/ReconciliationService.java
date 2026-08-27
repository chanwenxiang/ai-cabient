package com.aicabinet.trade.service;
import com.aicabinet.common.constants.CabinetConstants;

import com.aicabinet.common.dto.PaymentPlatformBillLineDto;
import com.aicabinet.common.dto.PaymentReconciliationDetailDto;
import com.aicabinet.common.dto.PaymentReconciliationDto;
import com.aicabinet.common.dto.PageResult;
import com.aicabinet.trade.domain.PaymentPlatformBillLine;
import com.aicabinet.trade.domain.PaymentReconciliation;
import com.aicabinet.trade.reconciliation.PlatformBillLine;
import com.aicabinet.trade.reconciliation.PlatformBillProviderRegistry;
import com.aicabinet.trade.service.support.ReconciliationServiceSupport;
import com.aicabinet.trade.mapper.PaymentOperationMapper;
import com.aicabinet.trade.mapper.PaymentPlatformBillLineMapper;
import com.aicabinet.trade.mapper.PaymentReconciliationMapper;
import com.aicabinet.trade.mapper.RechargeOrderMapper;
import com.aicabinet.trade.support.ApiMessages;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
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

    private final PaymentReconciliationMapper reconRepository;
    private final ReconciliationServiceSupport support;
    private final ReconciliationService self;

    public ReconciliationService(PaymentReconciliationMapper reconRepository,
                                 ReconciliationServiceSupport support,
                                 @Lazy ReconciliationService self) {
        this.reconRepository = reconRepository;
        this.support = support;
        this.self = self;
    }

    @Transactional(readOnly = true)
    public List<PaymentReconciliationDto> list(Long operatorId, LocalDate from, LocalDate to, String channel) {
        return self.list(operatorId, from, to, channel, null, null);
    }

    @Transactional(readOnly = true)
    public List<PaymentReconciliationDto> list(Long operatorId, LocalDate from, LocalDate to,
                                             String channel, String status, String keyword) {
        LocalDate end = to != null ? to : LocalDate.now();
        LocalDate start = from != null ? from : end.minusDays(30);
        String ch = channel != null && !channel.isBlank() ? channel.trim().toUpperCase() : null;
        return reconRepository.findByReconDateBetweenOrderByReconDateDesc(start, end).stream()
                .filter(r -> ch == null || ch.equalsIgnoreCase(r.getChannel()))
                .filter(r -> status == null || status.isBlank()
                        || status.trim().equalsIgnoreCase(r.getStatus()))
                .filter(r -> matchesReconKeyword(r, keyword))
                .map(this::toDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public PageResult<PaymentReconciliationDto> listPage(Long operatorId, ReconListPageQuery query) {
        LocalDate end = query.to() != null ? query.to() : LocalDate.now();
        LocalDate start = query.from() != null ? query.from() : end.minusDays(30);
        int p = Math.max(query.page(), 0);
        int s = Math.min(Math.max(query.size(), 1), 100);
        var result = reconRepository.searchPage(start, end, query.channel(), query.status(), query.keyword(), p, s);
        List<PaymentReconciliationDto> items = result.getRecords().stream().map(this::toDto).toList();
        return new PageResult<>(items, p, s, result.getTotal());
    }

    public record ReconListPageQuery(
            LocalDate from, LocalDate to, String channel, String status, String keyword, int page, int size) {}

    private static boolean matchesReconKeyword(PaymentReconciliation r, String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return true;
        }
        String kw = keyword.trim().toLowerCase();
        return String.valueOf(r.getReconId()).contains(kw)
                || (r.getReconDate() != null && r.getReconDate().toString().contains(kw))
                || (r.getChannel() != null && r.getChannel().toLowerCase().contains(kw));
    }

    @Transactional(readOnly = true)
    public PaymentReconciliationDetailDto getDetail(Long operatorId, Long reconId) {
        PaymentReconciliation recon = reconRepository.findById(reconId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, ApiMessages.RECONCILIATION_NOT_FOUND));
        List<PaymentPlatformBillLineDto> lines = support.billLineRepository().findByReconId(reconId).stream()
                .map(this::toLineDto)
                .toList();
        return new PaymentReconciliationDetailDto(toDto(recon), recon.getDetail(), lines);
    }

    @Transactional
    public PaymentReconciliationDto runDaily(Long operatorId, LocalDate date, String channel) {
        String ch = channel != null ? channel.toUpperCase() : CabinetConstants.PAY_CHANNEL_WECHAT;
        return runWithDailyLock(date, ch, () -> {
            reconRepository.findByReconDateAndChannel(date, ch).ifPresent(existing -> {
                support.billLineRepository().deleteByReconId(existing.getReconId());
                reconRepository.delete(existing);
                reconRepository.flush();
            });
            return toDto(doReconcile(date, ch));
        });
    }

    static String dailyReconciliationLockKey(LocalDate date, String channel) {
        return "reconciliation:run:" + date + ":" + channel;
    }

    private PaymentReconciliationDto runWithDailyLock(LocalDate date, String channel,
                                                      java.util.function.Supplier<PaymentReconciliationDto> action) {
        if (!support.distributedLockService().tryLock(dailyReconciliationLockKey(date, channel), 120, 5)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "对账任务处理中，请稍后重试");
        }
        try {
            return action.get();
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage(), e);
        } finally {
            support.distributedLockService().unlock(dailyReconciliationLockKey(date, channel));
        }
    }

    private PaymentReconciliation doReconcile(LocalDate date, String channel) {
        ZoneId zone = ZoneId.systemDefault();
        Instant start = date.atStartOfDay(zone).toInstant();
        Instant end = date.plusDays(1).atStartOfDay(zone).toInstant();

        long ledgerTotal = sumLedger(start, end, channel);
        List<PlatformBillLine> platformLines = support.billProviderRegistry().fetchBill(channel, date);
        long platformTotal = platformLines.stream().mapToLong(PlatformBillLine::amountCents).sum();

        Set<String> ledgerOrderIds = collectLedgerOrderIds(start, end, channel);
        Set<String> platformOrderIds = new HashSet<>();
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
            if (line.merchantOrderNo() != null && !line.merchantOrderNo().isBlank()) {
                platformOrderIds.add(line.merchantOrderNo());
            }
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
            support.billLineRepository().save(entity);
        }

        Set<String> ledgerOnlyOrderIds = new HashSet<>(ledgerOrderIds);
        ledgerOnlyOrderIds.removeAll(platformOrderIds);
        Map<String, Object> categories = classifyMismatch(recon.getDiffCents(), unmatched, ledgerOnlyOrderIds.size());
        recon.setMatchedCount(matched);
        recon.setUnmatchedCount(unmatched);
        recon.setStatus(recon.getDiffCents() == 0 && unmatched == 0 && ledgerOnlyOrderIds.isEmpty()
                ? "MATCHED" : "MISMATCH");
        if ("MISMATCH".equals(recon.getStatus())) {
            support.cabinetMetrics().recordReconciliationMismatch();
        }
        recon.setCompletedAt(Instant.now());
        try {
            Map<String, Object> detail = new HashMap<>();
            detail.put("platformLineCount", platformLines.size());
            detail.put("ledgerOrderCount", ledgerOrderIds.size());
            detail.put("platformUnmatchedCount", unmatched);
            detail.put("ledgerOnlyCount", ledgerOnlyOrderIds.size());
            detail.put("ledgerOnlyOrderIds", ledgerOnlyOrderIds.stream().sorted().limit(50).toList());
            detail.put("mismatchCategories", categories);
            detail.put("reviewAction", categories.isEmpty()
                    ? "NONE"
                    : "FILTER_DETAIL_AND_RERUN_AFTER_GATEWAY_OR_LEDGER_FIX");
            recon.setDetail(support.objectMapper().writeValueAsString(detail));
        } catch (Exception e) {
            log.warn("recon detail json failed", e);
        }
        log.info("reconciliation date={} channel={} platform={} ledger={} diff={} matched={} unmatched={}",
                date, channel, platformTotal, ledgerTotal, recon.getDiffCents(), matched, unmatched);
        return reconRepository.save(recon);
    }

    private Map<String, Object> classifyMismatch(long diffCents, int platformUnmatched, int ledgerOnly) {
        Map<String, Object> categories = new HashMap<>();
        if (platformUnmatched > 0) {
            categories.put("PLATFORM_ONLY", platformUnmatched);
        }
        if (ledgerOnly > 0) {
            categories.put("LEDGER_ONLY", ledgerOnly);
        }
        if (diffCents != 0) {
            categories.put(diffCents > 0 ? "PLATFORM_AMOUNT_GREATER" : "LEDGER_AMOUNT_GREATER", diffCents);
        }
        return categories;
    }

    private long sumLedger(Instant start, Instant end, String channel) {
        long total = support.paymentOperationRepository().sumNetCashflowBetween(start, end, channel);
        if (CabinetConstants.PAY_CHANNEL_WECHAT.equals(channel) || "MOCK".equals(channel) || "ALIPAY".equals(channel)) {
            total += support.rechargeRepository().sumPaidAmountBetween(start, end);
        }
        return total;
    }

    private Set<String> collectLedgerOrderIds(Instant start, Instant end, String channel) {
        Set<String> ids = new HashSet<>(support.paymentOperationRepository().findDistinctCabinetOrderIdsBetween(
                start, end, channel));
        if (CabinetConstants.PAY_CHANNEL_WECHAT.equals(channel) || "MOCK".equals(channel) || "ALIPAY".equals(channel)) {
            ids.addAll(support.rechargeRepository().findPaidOrderIdsBetween(start, end));
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
                r.getMatchedCount(), r.getUnmatchedCount(), r.getStatus(),
                r.getCreatedAt(), r.getCompletedAt()
        );
    }
}
