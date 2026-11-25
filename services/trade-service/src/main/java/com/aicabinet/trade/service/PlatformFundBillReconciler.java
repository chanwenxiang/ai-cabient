package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.PlatformFundAccount;
import com.aicabinet.trade.domain.PlatformFundFlow;
import com.aicabinet.trade.mapper.PlatformFundAccountMapper;
import com.aicabinet.trade.mapper.PlatformFundFlowMapper;
import com.aicabinet.trade.reconciliation.WeChatFundBillCsvParser;
import com.aicabinet.trade.reconciliation.WeChatFundBillService;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;

@Service
public class PlatformFundBillReconciler {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final String REF_TYPE = "WECHAT_FUND_BILL";

    private final WeChatFundBillService fundBillService;
    private final PlatformFundAccountMapper accountMapper;
    private final PlatformFundFlowMapper flowMapper;
    private final OpsAlertDispatcher alertDispatcher;
    private final Clock clock;
    private final PlatformFundBillReconciler self;

    @org.springframework.beans.factory.annotation.Autowired
    public PlatformFundBillReconciler(WeChatFundBillService fundBillService,
                                      PlatformFundAccountMapper accountMapper,
                                      PlatformFundFlowMapper flowMapper,
                                      OpsAlertDispatcher alertDispatcher,
                                      @Lazy PlatformFundBillReconciler self) {
        this(fundBillService, accountMapper, flowMapper, alertDispatcher,
                Clock.systemDefaultZone(), self);
    }

    PlatformFundBillReconciler(WeChatFundBillService fundBillService,
                               PlatformFundAccountMapper accountMapper,
                               PlatformFundFlowMapper flowMapper,
                               OpsAlertDispatcher alertDispatcher,
                               Clock clock,
                               @Lazy PlatformFundBillReconciler self) {
        this.fundBillService = fundBillService;
        this.accountMapper = accountMapper;
        this.flowMapper = flowMapper;
        this.alertDispatcher = alertDispatcher;
        this.clock = clock;
        this.self = self;
    }

    public ReconResult reconcile(LocalDate date) {
        Optional<PlatformFundAccount> found = accountMapper.findByChannel("微信");
        if (found.isEmpty()) {
            found = accountMapper.findByChannel("WECHAT");
        }
        if (found.isEmpty() || !"ACTIVE".equals(found.get().getStatus())) {
            return new ReconResult(date, false, 0L, null, null, "未登记 ACTIVE 微信运营账户，跳过");
        }
        WeChatFundBillCsvParser.FundBill bill = fundBillService.fetchOperatingAccount(date);
        if (bill.endingBalanceCents() == null) {
            return new ReconResult(date, false, 0L, null, null, "账单无资金流水，跳过余额核对");
        }
        return self.applyRecon(date, found.get().getAccountId(), bill.endingBalanceCents());
    }

    @Transactional
    public ReconResult applyRecon(LocalDate date, long accountId, long billEndingBalanceCents) {
        // CONCURRENCY: 资金账户行 FOR UPDATE 后再读区间流水与余额，串行化同日对账调整。
        String refId = date.toString();
        PlatformFundAccount account = accountMapper.findByIdForUpdate(accountId)
                .orElseThrow(() -> new IllegalStateException("平台资金账户不存在：" + accountId));
        if (flowMapper.countByRefTypeAndRefId(REF_TYPE, refId) > 0) {
            return new ReconResult(date, false, 0L, billEndingBalanceCents, null, "当日已核对");
        }
        Instant end = date.plusDays(1).atStartOfDay(ZONE).toInstant();
        long postDate = flowMapper.sumAmountByAccountIdBetween(accountId, end, clock.instant());
        long localEndingBalance = account.getBalanceCents() - postDate;
        long diff = billEndingBalanceCents - localEndingBalance;
        if (diff == 0L) {
            return new ReconResult(date, false, 0L, billEndingBalanceCents, localEndingBalance, "对账平");
        }
        if (!accountMapper.adjustBalance(accountId, diff)) {
            throw new IllegalStateException("平台资金账户调整失败：" + accountId);
        }
        PlatformFundFlow flow = new PlatformFundFlow();
        flow.setAccountId(accountId);
        flow.setFlowType(PlatformFundFlow.TYPE_ADJUST);
        flow.setAmountCents(diff);
        flow.setBalanceAfterCents(accountMapper.findById(accountId)
                .map(PlatformFundAccount::getBalanceCents).orElse(localEndingBalance + diff));
        flow.setRefType(REF_TYPE);
        flow.setRefId(refId);
        flow.setOperatorId(0L);
        flow.setRemark("微信运营账户资金账单自动调整");
        flow.setCreatedAt(Instant.now());
        flowMapper.insert(flow);
        alertDispatcher.trySend(PlatformFundService.ALERT_RECON, "运营账户资金账单差异已调整",
                "date=" + date + " billEndingCents=" + billEndingBalanceCents
                        + " localEndingCents=" + localEndingBalance + " diffCents=" + diff,
                Map.of("date", date.toString(), "diffCents", diff));
        return new ReconResult(date, true, diff, billEndingBalanceCents, localEndingBalance,
                "账单差异 " + diff + " 分已自动调整");
    }

    public record ReconResult(LocalDate date, boolean adjusted, long diffCents,
                              Long billEndingCents, Long localEndingCents, String message) {}
}
