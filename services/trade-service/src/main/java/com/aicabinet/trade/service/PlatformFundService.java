package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.PlatformFundAccount;
import com.aicabinet.trade.domain.PlatformFundFlow;
import com.aicabinet.trade.mapper.MerchantWithdrawRequestMapper;
import com.aicabinet.trade.mapper.OrderRevenueSplitMapper;
import com.aicabinet.trade.mapper.PlatformFundAccountMapper;
import com.aicabinet.trade.mapper.PlatformFundFlowMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

/**
 * 平台资金池台账（CB-033）。
 *
 * <p><b>解决的问题</b>：商户提现的真实出款钱从平台侧渠道账户（微信运营账户/支付宝/对公）
 * 出，渠道「余额不足即付款失败」（微信官方口径）。旧系统靠 NOTENOUGH 短信人肉补救，
 * 本服务把「公司存钱」变成系统化台账：<b>登记余额 → 提现前校验 → 缺口告警 → 归集核对</b>。</p>
 *
 * <p><b>三条边界（读 CB-033 结论 1/2/3）</b>：</p>
 * <ol>
 *   <li><b>软启动</b>：渠道未登记台账账户 ⇒ 只告警不拦截（否则上线首日所有提现被拦死）。
 *       登记后校验即为硬门禁。</li>
 *   <li><b>拦截不置失败</b>：备付不足时打款<b>静默跳过</b>（单据保持 APPROVED，可充值后重试
 *       打款），绝不置 FAILED/解冻 —— 那会制造「申请成功却收不到钱」的商户体验，也正是
 *       旧系统「失败→释放→重提→再失败」循环的根源。跳过必须留审计+告警。</li>
 *   <li><b>PAYOUT 流水永不阻断打款主链</b>：登记失败只记日志+告警，打款结果照常落账
 *       （打款是既成事实，台账坏了不能反过来否认它）。</li>
 * </ol>
 *
 * <p>归集核对口径：本月 Σ平台分成（{@code order_revenue_split.platform_cents}，排除
 * VOIDED/REVERSED）vs 本月 Σ SWEEP 流水，差异超容差告警；本月尚无归集登记则只在
 * 日检消息里提醒（软启动，避免上线首日就红）。</p>
 */
@Service
public class PlatformFundService {

    private static final Logger log = LoggerFactory.getLogger(PlatformFundService.class);

    /** 告警类型（OpsAlertDispatcher 分发到飞书/钉钉/企微/webhook）。 */
    public static final String ALERT_SHORT = "PLATFORM_FUND_SHORT";
    public static final String ALERT_PLAN = "PLATFORM_FUND_PLAN";
    public static final String ALERT_RECON = "PLATFORM_FUND_RECON";

    static final String CFG_PLAN_BUFFER_YUAN = SystemConfigService.PLATFORM_FUND_PLAN_BUFFER_YUAN;
    static final String CFG_RECON_TOLERANCE_YUAN = SystemConfigService.PLATFORM_FUND_RECON_TOLERANCE_YUAN;

    public static final String FLOW_REF_MERCHANT_WITHDRAW = "MERCHANT_WITHDRAW";

    private final PlatformFundAccountMapper accountMapper;
    private final PlatformFundFlowMapper flowMapper;
    private final MerchantWithdrawRequestMapper withdrawMapper;
    private final OrderRevenueSplitMapper splitMapper;
    private final SystemConfigService systemConfigService;
    private final OpsAlertDispatcher alertDispatcher;
    private final Clock clock;

    @Autowired // 双构造器必须显式指定 Spring 注入点：用 6 参版（内部造默认 Clock），Clock bean 未注册不能注 7 参版
    public PlatformFundService(PlatformFundAccountMapper accountMapper,
                               PlatformFundFlowMapper flowMapper,
                               MerchantWithdrawRequestMapper withdrawMapper,
                               OrderRevenueSplitMapper splitMapper,
                               SystemConfigService systemConfigService,
                               OpsAlertDispatcher alertDispatcher) {
        this(accountMapper, flowMapper, withdrawMapper, splitMapper, systemConfigService,
                alertDispatcher, Clock.systemDefaultZone());
    }

    public PlatformFundService(PlatformFundAccountMapper accountMapper,
                               PlatformFundFlowMapper flowMapper,
                               MerchantWithdrawRequestMapper withdrawMapper,
                               OrderRevenueSplitMapper splitMapper,
                               SystemConfigService systemConfigService,
                               OpsAlertDispatcher alertDispatcher,
                               Clock clock) {
        this.accountMapper = accountMapper;
        this.flowMapper = flowMapper;
        this.withdrawMapper = withdrawMapper;
        this.splitMapper = splitMapper;
        this.systemConfigService = systemConfigService;
        this.alertDispatcher = alertDispatcher;
        this.clock = clock;
    }

    // ── 台账登记（运营台） ────────────────────────────────────────────────

    @Transactional
    public PlatformFundAccount createAccount(String channel, String accountName, long initialBalanceCents,
                                             Long operatorId, String remark) {
        String ch = normalizeChannel(channel);
        if (accountMapper.findByChannel(ch).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "该渠道已登记平台资金账户，请直接在原账户上登记充值");
        }
        PlatformFundAccount account = new PlatformFundAccount();
        account.setChannel(ch);
        account.setAccountName(trim(accountName, "账户名"));
        account.setBalanceCents(0L);
        account.setRemark(trim(remark));
        account.setCreatedAt(Instant.now());
        account.setUpdatedAt(Instant.now());
        accountMapper.insert(account);
        if (initialBalanceCents > 0) {
            recordFlow(account, PlatformFundFlow.TYPE_RECHARGE, initialBalanceCents,
                    null, null, operatorId, "建账初始余额");
        } else if (initialBalanceCents < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "建账初始余额不能为负");
        }
        return account;
    }

    /** 充值登记（财务往渠道账户打钱后登记，正数）。 */
    @Transactional
    public PlatformFundAccount recharge(long accountId, long amountCents, Long operatorId, String remark) {
        return manualFlow(accountId, PlatformFundFlow.TYPE_RECHARGE, amountCents, operatorId, remark);
    }

    /** 归集登记（渠道账户 → 公司对公账户，出账填正数由本方法取负）。 */
    @Transactional
    public PlatformFundAccount sweep(long accountId, long amountCents, Long operatorId, String remark) {
        if (amountCents <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "归集金额必须大于 0");
        }
        return manualFlow(accountId, PlatformFundFlow.TYPE_SWEEP, -amountCents, operatorId, remark);
    }

    /** 人工调整（纠正渠道侧不可见动作：分账冻结/退款回退等造成的台账偏差，带符号）。 */
    @Transactional
    public PlatformFundAccount adjust(long accountId, long deltaCents, Long operatorId, String remark) {
        if (deltaCents == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "调整金额不能为 0");
        }
        if (remark == null || remark.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "人工调整必须填写原因");
        }
        return manualFlow(accountId, PlatformFundFlow.TYPE_ADJUST, deltaCents, operatorId, remark);
    }

    private PlatformFundAccount manualFlow(long accountId, String flowType, long amountCents,
                                           Long operatorId, String remark) {
        PlatformFundAccount account = requireAccount(accountId);
        if (amountCents == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "金额不能为 0");
        }
        recordFlow(account, flowType, amountCents, null, null, operatorId, remark);
        return accountMapper.findById(accountId).orElse(account);
    }

    private PlatformFundAccount requireAccount(long accountId) {
        return accountMapper.findById(accountId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "平台资金账户不存在"));
    }

    private void recordFlow(PlatformFundAccount account, String flowType, long amountCents,
                            String refType, String refId, Long operatorId, String remark) {
        if (!accountMapper.adjustBalance(account.getAccountId(), amountCents)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "平台资金账户不存在或已被删除");
        }
        PlatformFundFlow flow = new PlatformFundFlow();
        flow.setAccountId(account.getAccountId());
        flow.setFlowType(flowType);
        flow.setAmountCents(amountCents);
        flow.setBalanceAfterCents(accountMapper.findById(account.getAccountId())
                .map(PlatformFundAccount::getBalanceCents).orElse(0L));
        flow.setRefType(refType);
        flow.setRefId(refId);
        flow.setOperatorId(operatorId);
        flow.setRemark(trim(remark));
        flow.setCreatedAt(Instant.now());
        flowMapper.insert(flow);
    }

    public List<PlatformFundAccount> listAccounts() {
        return accountMapper.selectList(null);
    }

    public List<PlatformFundFlow> listFlows(long accountId, int limit) {
        return flowMapper.findByAccountIdOrderByCreatedDesc(accountId, Math.min(Math.max(limit, 1), 200));
    }

    // ── 备付校验（提现打款前硬门禁） ──────────────────────────────────────

    /**
     * 打款前备付校验。
     *
     * @return 空表示放行；非空为跳过打款的原因文案（单据保持原状态，可充值后重试）
     */
    public Optional<String> gatePayout(String channel, long netCents, Long excludeRequestId) {
        String ch = channel == null ? "" : channel.trim();
        PlatformFundAccount account = accountMapper.findByChannel(ch).orElse(null);
        if (account == null || !"ACTIVE".equals(account.getStatus())) {
            // 软启动：未登记台账 ⇒ 不拦截（否则上线首日拦死全部提现），但提醒登记。
            alertDispatcher.trySend(ALERT_SHORT, "平台资金池未登记",
                    "打款通道 " + ch + " 尚未登记平台资金台账账户，备付校验未生效。"
                            + "请尽快在运营台「资金账单-平台资金池」登记账户与余额", java.util.Map.of());
            return Optional.empty();
        }
        long pending = withdrawMapper.sumNetPendingByChannel(ch, excludeRequestId);
        long available = account.getBalanceCents() - pending;
        if (available >= netCents) {
            return Optional.empty();
        }
        String reason = "平台备付金不足：渠道 " + ch + " 台账余额 "
                + yuan(account.getBalanceCents()) + " 元 − 在途待付 "
                + yuan(pending) + " 元 < 本次 " + yuan(netCents)
                + " 元，请先充值登记后再打款";
        alertDispatcher.trySend(ALERT_SHORT, "提现打款被备付校验拦截",
                reason + "；请财务向渠道账户充值并在「平台资金池」登记后重试打款", java.util.Map.of());
        return Optional.of(reason);
    }

    /** 提现打款成功后登记流出。永不抛异常（见类注释边界 3）。 */
    public void recordPayoutQuietly(String channel, long netCents, long requestId) {
        try {
            PlatformFundAccount account = accountMapper.findByChannel(
                    channel == null ? "" : channel.trim()).orElse(null);
            if (account == null) {
                log.warn("platform fund account missing for payout, flow not recorded: channel={}, requestId={}",
                        channel, requestId);
                alertDispatcher.trySend(ALERT_SHORT, "平台资金池未登记（打款未入账）",
                        "打款成功但通道 " + channel + " 未登记台账，资金池余额未扣减；"
                                + "请登记账户后用人工调整补平", java.util.Map.of());
                return;
            }
            recordFlow(account, PlatformFundFlow.TYPE_PAYOUT, -netCents,
                    FLOW_REF_MERCHANT_WITHDRAW, String.valueOf(requestId), null, "商户提现打款");
        } catch (Exception e) {
            // 🔴 台账写失败绝不能把已成功的打款改成失败（那等于否认资金事实）——只告警由人工补
            log.error("platform fund payout flow failed: channel={}, requestId={}", channel, requestId, e);
            alertDispatcher.trySend(ALERT_SHORT, "平台资金池流水登记失败",
                    "打款成功但台账登记异常：channel=" + channel + " requestId=" + requestId
                            + "，请人工核对补录；异常=" + e.getMessage(), java.util.Map.of());
        }
    }

    // ── 每日任务：备付计划 + 归集核对 ─────────────────────────────────────

    /**
     * 平台资金日检（platform-fund-daily，每日 08:10）：
     * ① 各渠道「余额 vs 在途待付+buffer」缺口告警（替代旧系统「公司每天存钱」的人肉记忆）；
     * ② 归集核对：本月 Σ平台分成 vs Σ归集登记，差异超容差告警。
     *
     * @return 人读摘要（运营台任务历史可见）
     */
    public String runDailyCheck() {
        Instant now = clock.instant();
        ZoneId zone = ScheduleZoneHolder.ZONE;
        LocalDate today = LocalDate.ofInstant(now, zone);
        Instant monthStart = today.withDayOfMonth(1).atStartOfDay(zone).toInstant();
        StringBuilder summary = new StringBuilder();

        // ① 备付计划
        long bufferCents = yuanToCents(systemConfigService.getDouble(CFG_PLAN_BUFFER_YUAN, 500.0));
        List<PlatformFundAccount> accounts = listAccounts();
        if (accounts.isEmpty()) {
            summary.append("平台资金池未登记任何账户（软启动，备付校验未生效）");
            alertDispatcher.trySend(ALERT_PLAN, "平台资金池未登记",
                    "尚未登记任何平台资金账户，备付校验与日检未生效。请登记微信运营账户等台账", java.util.Map.of());
        }
        for (PlatformFundAccount account : accounts) {
            if (!"ACTIVE".equals(account.getStatus())) {
                continue;
            }
            long pending = withdrawMapper.sumNetPendingByChannel(account.getChannel(), null);
            long need = pending + bufferCents;
            if (account.getBalanceCents() < need) {
                alertDispatcher.trySend(ALERT_PLAN, "备付金缺口",
                        "渠道 " + account.getChannel() + "（" + account.getAccountName()
                                + "）：余额 " + yuan(account.getBalanceCents())
                                + " 元 < 在途待付 " + yuan(pending) + " 元 + 备付缓冲 "
                                + yuan(bufferCents) + " 元，请今日充值", java.util.Map.of());
                summary.append("[").append(account.getChannel()).append("] 缺口 ")
                        .append(yuan(need - account.getBalanceCents())).append(" 元；");
            } else {
                summary.append("[").append(account.getChannel()).append("] 充足（余额 ")
                        .append(yuan(account.getBalanceCents())).append(" 元）；");
            }
        }

        // ② 归集核对（本月）
        long monthPlatform = splitMapper.sumPlatformCentsBetween(monthStart, now);
        long sweepCount = flowMapper.countByTypeBetween(PlatformFundFlow.TYPE_SWEEP, monthStart, now);
        if (sweepCount > 0) {
            long monthSweep = flowMapper.sumAmountByTypeBetween(PlatformFundFlow.TYPE_SWEEP, monthStart, now);
            long tolerance = yuanToCents(systemConfigService.getDouble(CFG_RECON_TOLERANCE_YUAN, 10.0));
            long diff = Math.abs(monthPlatform - monthSweep);
            if (diff > tolerance) {
                alertDispatcher.trySend(ALERT_RECON, "归集核对不平",
                        "本月平台分成 " + yuan(monthPlatform) + " 元 vs 归集登记 "
                                + yuan(monthSweep) + " 元，差异 " + yuan(diff)
                                + " 元超容差 " + yuan(tolerance) + " 元；"
                                + "请核对归集登记是否有遗漏（渠道侧分账冻结/退款回退可用人工调整纠偏）", java.util.Map.of());
                summary.append(" 归集核对差异 ").append(yuan(diff)).append(" 元（超容差）");
            } else {
                summary.append(" 归集核对平（差异 ").append(yuan(diff)).append(" 元）");
            }
        } else if (monthPlatform > 0) {
            summary.append(" 本月平台分成 ").append(yuan(monthPlatform))
                    .append(" 元尚未登记归集（提醒）");
        }
        return summary.toString();
    }

    private static long yuanToCents(double yuan) {
        return Math.max(0L, Math.round(yuan * 100.0));
    }

    private static String yuan(long cents) {
        return String.format(java.util.Locale.ROOT, "%.2f", cents / 100.0);
    }

    private static String normalizeChannel(String channel) {
        if (channel == null || channel.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "渠道不能为空");
        }
        return channel.trim().toUpperCase(java.util.Locale.ROOT);
    }

    private static String trim(String s) {
        return s == null ? null : s.trim();
    }

    private static String trim(String s, String label) {
        String t = trim(s);
        if (t == null || t.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, label + "不能为空");
        }
        return t;
    }

    /** 仅用于把 ZoneId 常量从本类解耦（避免与 ScheduleZones 循环依赖的错觉，实际无依赖）。 */
    private static final class ScheduleZoneHolder {
        static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    }
}
