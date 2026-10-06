package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.LineWithdrawRequest;
import com.aicabinet.trade.domain.LineWalletLedger;
import com.aicabinet.trade.domain.MerchantWithdrawRequest;
import com.aicabinet.trade.mapper.LineWalletLedgerMapper;
import com.aicabinet.trade.mapper.LineWithdrawRequestMapper;
import com.aicabinet.trade.mapper.MerchantWalletLedgerMapper;
import com.aicabinet.trade.mapper.MerchantWithdrawRequestMapper;
import com.aicabinet.trade.payout.PayoutChannelRegistry;
import com.aicabinet.trade.payout.PayoutConstants;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * V308：<b>出款侧</b>对账（提现打款）。
 *
 * <p>🔴 <b>先说清边界，别把它当渠道对账用</b>：{@link ReconciliationService} 做的是
 * <b>收入侧</b>（收钱）且需要拉取<b>渠道账单</b>逐笔匹配。出款侧目前<b>没有渠道账单可拉</b>——
 * 微信/支付宝/银行三通道的 {@code isReady()} 恒false（未开通产品/未签协议），
 * 拿不到对方流水。因此本服务做的是<b>本地三方自洽核对</b>：
 * <pre>
 *   提现单（PAID）  ⇄  钱包流水（WITHDRAW_PAID）  ⇄  手续费流水（WITHDRAW_FEE）
 * </pre>
 * 三者任一不等，就是<b>本地账已经不平</b>，必须查。它<b>不能</b>回答
 * 「渠道那边实际出了多少钱」——那要等通道接通后补账单拉取。
 *
 * <p><b>恒等式（三个数必须满足）</b>：
 * <pre>
 *   Σ(单.amount) − Σ(单.fee) == Σ|WITHDRAW_PAID|     （出款净额）
 *   Σ(单.fee)                   == Σ|WITHDRAW_FEE|     （手续费收入）
 *   ⇒ 单.amount                  == 两者之和（毛额）
 * </pre>
 * 第一式验证「账实相符到分」，第二式验证「手续费没有漏记/重复记」——
 * 这正是 V308 拆分记账要保住的东西。
 *
 * <p><b>为什么按 {@code paidAt} 而非 {@code createdAt} 分窗</b>：申请与打款跨天是常态
 * （T+1 审核、人工复核、渠道重试），按申请时间会把昨天的单算进今天。
 */
@Service
public class PayoutReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(PayoutReconciliationService.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final String STATUS_PAID = "PAID";
    private static final String LEDGER_PAID = "WITHDRAW_PAID";
    private static final String LEDGER_FEE = "WITHDRAW_FEE";

    private final MerchantWithdrawRequestMapper withdrawMapper;
    private final LineWithdrawRequestMapper lineWithdrawMapper;
    private final MerchantWalletLedgerMapper ledgerMapper;
    private final LineWalletLedgerMapper lineLedgerMapper;
    private final PayoutChannelRegistry channelRegistry;

    public PayoutReconciliationService(MerchantWithdrawRequestMapper withdrawMapper,
                                       LineWithdrawRequestMapper lineWithdrawMapper,
                                       MerchantWalletLedgerMapper ledgerMapper,
                                       LineWalletLedgerMapper lineLedgerMapper,
                                       PayoutChannelRegistry channelRegistry) {
        this.withdrawMapper = withdrawMapper;
        this.lineWithdrawMapper = lineWithdrawMapper;
        this.ledgerMapper = ledgerMapper;
        this.lineLedgerMapper = lineLedgerMapper;
        this.channelRegistry = channelRegistry;
    }

    /**
     * 出款侧对账（本地自洽口径）。
     *
     * @return 对账报告；{@code balanced=false} 表示本地账已不平，需人工介入
     */
    @Transactional(readOnly = true)
    public PayoutReconReport reconcile(LocalDate bizDate) {
        LocalDate date = bizDate == null ? LocalDate.now(ZONE) : bizDate;
        Instant start = date.atStartOfDay(ZONE).toInstant();
        Instant end = date.plusDays(1).atStartOfDay(ZONE).toInstant();

        List<MerchantWithdrawRequest> paid =
                withdrawMapper.findByStatusAndPaidAtBetween(STATUS_PAID, start, end);
        int linePaidCount = lineWithdrawMapper
                .findByStatusAndPaidAtBetween(STATUS_PAID, start, end).size();

        // 提现单侧：毛额 / 手续费 / 净额，并按通道分组
        long grossFromRequests = 0L;
        long feeFromRequests = 0L;
        long netFromRequests = 0L;
        Map<String, long[]> byChannel = new TreeMap<>();
        // 🔴 V308：商户侧与线长侧<b>都要纳入</b> —— 平台的出款总额是两者之和，
        // 只对一侧就是「少算一半」，差异会永远显示为不平。
        for (MerchantWithdrawRequest r : withdrawMapper.findByStatusAndPaidAtBetween(STATUS_PAID, start, end)) {
            accumulate(byChannel, r.getPayChannel(), value(r.getAmountCents()),
                    Math.max(0L, value(r.getFeeCents())));
        }
        for (LineWithdrawRequest r : lineWithdrawMapper.findByStatusAndPaidAtBetween(STATUS_PAID, start, end)) {
            accumulate(byChannel, r.getPayChannel(), value(r.getAmountCents()),
                    Math.max(0L, value(r.getFeeCents())));
        }
        for (long[] acc : byChannel.values()) {
            grossFromRequests += acc[0];
            feeFromRequests += acc[1];
            netFromRequests += acc[2];
        }

        // 钱包流水侧：按 paidAt 同窗取（WITHDRAW_PAID 记负数，取绝对值）
        long netFromLedger = ledgerMapper.sumAmountByEntryTypeBetween(LEDGER_PAID, start, end)
                + lineSumBetween(LEDGER_PAID, start, end);
        long feeFromLedger = ledgerMapper.sumAmountByEntryTypeBetween(LEDGER_FEE, start, end)
                + lineSumBetween(LEDGER_FEE, start, end);

        long netDiff = netFromRequests - netFromLedger;
        long feeDiff = feeFromRequests - feeFromLedger;

        List<String> issues = new ArrayList<>();
        if (netDiff != 0L) {
            issues.add("出款净额不平：提现单 " + netFromRequests + " 分 vs 钱包流水 "
                    + netFromLedger + " 分，差 " + netDiff + " 分");
        }
        if (feeDiff != 0L) {
            issues.add("手续费不平：提现单 " + feeFromRequests + " 分 vs 钱包流水 "
                    + feeFromLedger + " 分，差 " + feeDiff + " 分");
        }

        Map<String, Object> info = new LinkedHashMap<>();
        info.put("bizDate", date.toString());
        info.put("paidCount", (long) paid.size());
        info.put("linePaidCount", (long) linePaidCount);
        info.put("grossCents", grossFromRequests);
        info.put("netCents", netFromRequests);
        info.put("feeCents", feeFromRequests);
        info.put("netDiffCents", netDiff);
        info.put("feeDiffCents", feeDiff);
        info.put("balanced", issues.isEmpty());
        info.put("issues", issues);
        info.put("byChannel", describeByChannel(byChannel));
        // 🔴 诚实标注：本地自洽 ≠ 与渠道对平
        info.put("scope", "LOCAL_SELF_CONSISTENT_ONLY");
        info.put("scopeNote", "本报告只核对本地三方（提现单 / 钱包流水 / 手续费流水）是否自洽；"
                + "**未与渠道账单比对**——三通道 isReady 恒 false，渠道流水拉不到。"
                + "「渠道实际出了多少钱」需等通道接通后补账单对账。");
        info.put("channelReadiness", channelRegistry.readiness());

        if (!issues.isEmpty()) {
            log.warn("payout reconciliation UNBALANCED date={} netDiff={} feeDiff={} issues={}",
                    date, netDiff, feeDiff, issues);
        }
        return new PayoutReconReport(date, paid.size(), grossFromRequests, netFromRequests,
                feeFromRequests, netDiff, feeDiff, issues.isEmpty(), info);
    }

    private Map<String, Object> describeByChannel(Map<String, long[]> byChannel) {
        Map<String, Object> m = new LinkedHashMap<>();
        // 固定顺序：运营看的是「哪条通道出的钱」，不按哈希顺序抖动
        for (String channel : List.of(
                com.aicabinet.common.constants.CabinetConstants.PAY_CHANNEL_WECHAT,
                com.aicabinet.common.constants.CabinetConstants.PAY_CHANNEL_ALIPAY,
                PayoutConstants.PAY_CHANNEL_BANK,
                "MOCK", "UNKNOWN")) {
            long[] acc = byChannel.get(channel);
            if (acc == null) {
                continue;
            }
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("grossCents", acc[0]);
            one.put("feeCents", acc[1]);
            one.put("netCents", acc[2]);
            m.put(channel, one);
        }
        // 出现未登记通道时也要露出来（不能因为「不认识」就丢掉金额）
        for (Map.Entry<String, long[]> e : byChannel.entrySet()) {
            if (!m.containsKey(e.getKey())) {
                Map<String, Object> one = new LinkedHashMap<>();
                one.put("grossCents", e.getValue()[0]);
                one.put("feeCents", e.getValue()[1]);
                one.put("netCents", e.getValue()[2]);
                m.put(e.getKey(), one);
            }
        }
        return m;
    }

    private static long value(Long v) {
        return v == null ? 0L : v;
    }

    /** 累加一笔提现单到「按通道」分组（[0]=毛额[1]=手续费[2]=净额）。 */
    private static void accumulate(Map<String, long[]> byChannel, String channel, long gross, long fee) {
        long net = Math.max(0L, gross - fee);
        String key = channel == null ? "UNKNOWN" : channel;
        long[] acc = byChannel.computeIfAbsent(key, k -> new long[3]);
        acc[0] += gross;
        acc[1] += fee;
        acc[2] += net;
    }

    /**
     * 线长钱包流水的同窗求和（绝对值口径）。
     *
     * <p>⚠️ 线长流水表<b>没有</b> {@code MerchantWalletLedgerMapper} 那套 helper，
     * 这里就地遍历。返回<b>正数</b>（流水里记的是负数）。
     */
    private long lineSumBetween(String entryType, Instant from, Instant to) {
        if (lineLedgerMapper == null) {
            return 0L;
        }
        List<LineWalletLedger> rows = lineLedgerMapper.selectList(
                Wrappers.<LineWalletLedger>lambdaQuery()
                        .eq(LineWalletLedger::getEntryType, entryType)
                        .ge(LineWalletLedger::getCreatedAt, from)
                        .lt(LineWalletLedger::getCreatedAt, to));
        long sum = 0L;
        for (LineWalletLedger row : rows) {
            if (row.getAmountCents() != null) {
                sum += Math.abs(row.getAmountCents());
            }
        }
        return sum;
    }

    /**
     * 出款对账报告。
     *
     * @param netDiffCents 提现单净额 − 钱包出款流水净额；0 = 平
     * @param feeDiffCents 提现单手续费 − 钱包手续费流水；0 = 平
     */
    public record PayoutReconReport(
            LocalDate bizDate,
            int paidCount,
            long grossCents,
            long netCents,
            long feeCents,
            long netDiffCents,
            long feeDiffCents,
            boolean balanced,
            Map<String, Object> detail
    ) {
    }
}