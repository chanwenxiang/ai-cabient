package com.aicabinet.trade.service;
import com.aicabinet.common.constants.CabinetConstants;

import com.aicabinet.common.dto.PaymentPlatformBillLineDto;
import com.aicabinet.common.dto.PaymentReconciliationDetailDto;
import com.aicabinet.common.dto.PaymentReconciliationDto;
import com.aicabinet.common.dto.PageResult;
import com.aicabinet.trade.domain.PaymentPlatformBillLine;
import com.aicabinet.trade.domain.PaymentReconciliation;
import com.aicabinet.trade.reconciliation.PlatformBillLine;
import com.aicabinet.trade.service.support.ReconciliationServiceSupport;
import com.aicabinet.trade.mapper.PaymentReconciliationMapper;
import com.aicabinet.trade.support.ApiMessages;
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

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    private final PaymentReconciliationMapper reconRepository;
    private final ReconciliationServiceSupport support;
    private final ReconciliationService self;
    private final OpsAlertDispatcher alertDispatcher;
    /** CB-020③：读配置通道费率（bps）做实结/配置差异告警；null 容错走默认 60bps（同 FundBillService）。 */
    private final SystemConfigService systemConfigService;

    /** 费率差异告警的绝对阈值（bps）：实结费率与配置费率差超过 1% 即提示复核。 */
    static final long FEE_RATE_ALERT_ABS_BPS = 100;
    /** 费率差异告警的相对阈值：超过配置费率的 25%（低费率下绝对 100bps 过钝的补充）。 */
    static final double FEE_RATE_ALERT_REL_RATIO = 0.25d;

    public ReconciliationService(PaymentReconciliationMapper reconRepository,
                                 ReconciliationServiceSupport support,
                                 @Lazy ReconciliationService self,
                                 OpsAlertDispatcher alertDispatcher,
                                 SystemConfigService systemConfigService) {
        this.reconRepository = reconRepository;
        this.support = support;
        this.self = self;
        this.alertDispatcher = alertDispatcher;
        this.systemConfigService = systemConfigService;
    }

    @Transactional(readOnly = true)
    public List<PaymentReconciliationDto> list(Long operatorId, LocalDate from, LocalDate to, String channel) {
        return self.list(operatorId, from, to, channel, null, null);
    }

    @Transactional(readOnly = true)
    public List<PaymentReconciliationDto> list(Long operatorId, LocalDate from, LocalDate to,
                                             String channel, String status, String keyword) {
        LocalDate end = to != null ? to : LocalDate.now(ZONE);
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
        LocalDate end = query.to() != null ? query.to() : LocalDate.now(ZONE);
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
        Instant start = date.atStartOfDay(ZONE).toInstant();
        Instant end = date.plusDays(1).atStartOfDay(ZONE).toInstant();

        long ledgerTotal = sumLedger(start, end, channel);
        List<PlatformBillLine> platformLines = support.billProviderRegistry().fetchBill(channel, date);
        long platformTotal = platformLines.stream().mapToLong(PlatformBillLine::amountCents).sum();
        // CB-020③：实结手续费聚合。feeProvided=false（通道不提供，如 Mock/支付宝未映射）⇒ 存 null
        // 与 0 区分——上层 FundBillService 据此走估算兜底而非误显示「实结 0 元」。
        long feeTotalCents = platformLines.stream()
                .map(PlatformBillLine::feeCents)
                .filter(java.util.Objects::nonNull)
                .mapToLong(Long::longValue)
                .sum();
        boolean feeProvided = platformLines.stream().anyMatch(l -> l.feeCents() != null);

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
        recon.setChannelFeeCents(feeProvided ? feeTotalCents : null);
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
            entity.setFeeCents(line.feeCents());
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
            // V1：仅打指标运营无感知——补发运营告警（dispatcher 投递失败内部自吞，不影响主流程）
            if (alertDispatcher != null) {
                try {
                    alertDispatcher.send("RECON_MISMATCH", "对账差异待人工处置",
                            "date=" + date + " channel=" + channel
                                    + " platformTotal=" + platformTotal
                                    + " ledgerTotal=" + ledgerTotal
                                    + " diffCents=" + recon.getDiffCents()
                                    + " unmatched=" + unmatched
                                    + "，请到运营后台对账页查看明细");
                } catch (Exception alertEx) {
                    log.warn("recon mismatch alert failed", alertEx);
                }
            }
        }
        // CB-020③：费率异常检测（金额对平≠费率正常）。只告警不改 status——MISMATCH 语义留给金额/单据不平，
        // 费率异常复用 RECON_MISMATCH 告警通道投递（台账结论：差异告警复用 RECON_MISMATCH）。
        if (feeProvided) {
            checkChannelFeeAnomaly(recon, date, channel, platformTotal, feeTotalCents);
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
            detail.put("channelFeeCents", feeProvided ? feeTotalCents : null);
            detail.put("channelFeeProvided", feeProvided);
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

    /**
     * CB-020③：账单实结费率 vs 运营台配置费率的差异检测。
     * <p>口径：{@code actualBps = feeTotal / platformTotal}（platformTotal 为当日账单净额，含退款负行，
     * 与手续费同源同号）。告警条件（<b>同时</b>满足，双阈值互补防噪）：
     * 绝对差 >{@link #FEE_RATE_ALERT_ABS_BPS}（1%，低配置费率下防 0.2% 级噪音）<b>且</b>
     * 相对差 >{@link #FEE_RATE_ALERT_REL_RATIO}（配置的 25%，高配置费率下绝对阈值防钝）。
     * <p>不比较 ledger 口径——费率是「渠道收了多少」的属性，与账本无关；platformTotal<=0（当日净退款）
     * 费率无意义，跳过。阈值硬编码常量首版即定，detail/告警记录两侧 bps 供人工复核，配置化留后续需要。
     */
    private void checkChannelFeeAnomaly(PaymentReconciliation recon, LocalDate date, String channel,
                                        long platformTotal, long feeTotalCents) {
        if (platformTotal <= 0) {
            return;
        }
        long actualBps = Math.round((double) feeTotalCents * 10_000d / (double) platformTotal);
        long configBps = FundBillService.resolveChannelFeeBps(systemConfigService);
        long absDiff = Math.abs(actualBps - configBps);
        boolean anomalous = absDiff > FEE_RATE_ALERT_ABS_BPS
                && absDiff > Math.round(configBps * FEE_RATE_ALERT_REL_RATIO);
        log.info("reconciliation fee rate date={} channel={} actualBps={} configBps={}",
                date, channel, actualBps, configBps);
        if (!anomalous) {
            return;
        }
        support.cabinetMetrics().recordReconciliationMismatch();
        if (alertDispatcher != null) {
            try {
                alertDispatcher.send("RECON_MISMATCH", "渠道费率与配置差异待复核",
                        "date=" + date + " channel=" + channel
                                + " actualFeeBps=" + actualBps
                                + " configFeeBps=" + configBps
                                + " feeTotalCents=" + feeTotalCents
                                + " platformTotalCents=" + platformTotal
                                + "，请核对渠道账单手续费列与运营台费率配置");
            } catch (Exception alertEx) {
                log.warn("recon fee rate alert failed", alertEx);
            }
        }
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

    /**
     * 本账合计：渠道净现金流入 + 充值毛额 − 网关充值退款。
     * <p>
     * 充值原单多记在 BALANCE 入账流水，{@code sumNetCashflowBetween(MOCK/WECHAT)} 不含毛额，
     * 须另加 {@code sumPaidAmountBetween}；退款常记在 WECHAT/ALIPAY，Mock 账单会纳入负向，
     * 故对本账统一扣网关 {@code RECHARGE_REFUND}，并加回已计入本渠道净流入的部分，避免 WECHAT 对账双扣。
     */
    private long sumLedger(Instant start, Instant end, String channel) {
        long total = support.paymentOperationRepository().sumNetCashflowBetween(start, end, channel);
        if (!isGatewayReconChannel(channel)) {
            return total;
        }
        long rechargeGross = support.rechargeRepository().sumPaidAmountBetween(start, end);
        long gatewayRefunds = support.paymentOperationRepository()
                .sumGatewayRechargeRefundBetween(start, end);
        long refundsAlreadyInChannel = support.paymentOperationRepository()
                .sumRechargeRefundByChannel(start, end, channel);
        return total + rechargeGross - gatewayRefunds + refundsAlreadyInChannel;
    }

    private static boolean isGatewayReconChannel(String channel) {
        return CabinetConstants.PAY_CHANNEL_WECHAT.equals(channel)
                || "MOCK".equals(channel)
                || "ALIPAY".equals(channel);
    }

    private Set<String> collectLedgerOrderIds(Instant start, Instant end, String channel) {
        Set<String> ids = new HashSet<>(support.paymentOperationRepository().findDistinctCabinetOrderIdsBetween(
                start, end, channel));
        if (isGatewayReconChannel(channel)) {
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
