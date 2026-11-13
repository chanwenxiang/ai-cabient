package com.aicabinet.trade.payment;

import com.aicabinet.trade.config.ProfitSharingProperties;
import com.aicabinet.trade.config.WeChatPayProperties;
import com.aicabinet.trade.domain.Merchant;
import com.aicabinet.trade.domain.OrderRevenueSplit;
import com.aicabinet.trade.domain.OrderSplitReturnLeg;
import com.aicabinet.trade.mapper.OrderRevenueSplitMapper;
import com.aicabinet.trade.service.ProfitSharingReturnLegService;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.Getter;
import lombok.Setter;

/**
 * 微信分账 API v3 骨架。购物订单当前走余额扣款，默认仅记账本（LEDGER_ONLY）；
 * 运营可在绑定微信支付交易号后手动/自动提交分账。
 */
@Service
@Getter
@Setter
public class WeChatProfitSharingService {
    private static final String WECHAT_SUBMITTED = "WECHAT_SUBMITTED";
    private static final String LEDGER_ONLY = "LEDGER_ONLY";


    private static final Logger log = LoggerFactory.getLogger(WeChatProfitSharingService.class);

    private final ProfitSharingProperties profitSharingProperties;
    private final WeChatPayProperties weChatPayProperties;
    private final WeChatPayV3Client weChatPayV3Client;
    private final OrderRevenueSplitMapper splitRepository;
    private final ProfitSharingReturnLegService returnLegService;
    private final WeChatProfitSharingService self;

    public WeChatProfitSharingService(ProfitSharingProperties profitSharingProperties,
                                      WeChatPayProperties weChatPayProperties,
                                      WeChatPayV3Client weChatPayV3Client,
                                      OrderRevenueSplitMapper splitRepository,
                                      ProfitSharingReturnLegService returnLegService,
                                      @Lazy WeChatProfitSharingService self) {
        this.profitSharingProperties = profitSharingProperties;
        this.weChatPayProperties = weChatPayProperties;
        this.weChatPayV3Client = weChatPayV3Client;
        this.splitRepository = splitRepository;
        this.returnLegService = returnLegService;
        this.self = self;
    }

    public boolean isApiReady() {
        if (!profitSharingProperties.enabled()) {
            return false;
        }
        return profitSharingProperties.mockEnabled() || weChatPayProperties.isConfigured();
    }

    public boolean isMockMode() {
        return profitSharingProperties.enabled() && profitSharingProperties.mockEnabled();
    }

    /**
     * 提交分账（审计 B2，2026-10-16）：三段式编排，HTTP 调用**不在任何 DB 事务内**执行。
     * <ul>
     *   <li>tx1 {@link #prepareSubmit}：校验 + 短路（LEDGER_ONLY / 已提交 / mock）+ 持久化 out_order_no
     *       （先落库再打微信，崩溃后重试仍复用同一 out_order_no，微信侧按其幂等）；</li>
     *   <li>无事务：HTTP POST /v3/profitsharing/orders；</li>
     *   <li>tx2 {@link #finalizeSubmitted} / {@link #finalizeFailed}：落 WECHAT_SUBMITTED / WECHAT_FAILED。</li>
     * </ul>
     * 对外契约不变（调用方与测试均钉此签名）。跨阶段串行化依赖调用方的订单分账分布式锁
     * （MerchantService.runWithOrderSplitLock）；finalize 以 splitId 重读最新行落状态，
     * 避免跨事务持有过期实体。若 tx2 前进程崩溃：微信已受理但本地仍为旧状态，
     * 重试走同一 out_order_no，微信按其去重——与旧实现（事务提交失败）风险面相同。
     */
    public OrderRevenueSplit submitSplit(OrderRevenueSplit split, Merchant merchant, String wxTransactionId) {
        PreparedSubmission prep = self.prepareSubmit(split, merchant, wxTransactionId);
        if (prep.terminal()) {
            return prep.split();
        }
        try {
            JsonNode resp = weChatPayV3Client.post("/v3/profitsharing/orders", prep.body());
            return self.finalizeSubmitted(prep.split(), prep.wxTransactionId(), resp.path("order_id").asText());
        } catch (Exception e) {
            return self.finalizeFailed(prep.split(), prep.wxTransactionId(), e);
        }
    }

    /** tx1：校验、短路、持久化 out_order_no、组装请求体。 */
    @Transactional
    public PreparedSubmission prepareSubmit(OrderRevenueSplit split, Merchant merchant, String wxTransactionId) {
        if (merchant.getWechatReceiverId() == null || merchant.getWechatReceiverId().isBlank()) {
            throw new IllegalStateException("merchant has no wechatReceiverId");
        }
        if (wxTransactionId == null || wxTransactionId.isBlank()) {
            throw new IllegalStateException("wxTransactionId is required for WeChat profit sharing");
        }
        if (!isApiReady()) {
            throw new IllegalStateException("WeChat profit sharing is not enabled or pay is not configured");
        }
        if (split.getMerchantCents() <= 0) {
            split.setStatus(LEDGER_ONLY);
            split.setFailureReason(null);
            splitRepository.save(split);
            // M01：updateById 忽略 null 列，清列须 wrapper 显式 set(null)
            splitRepository.update(null, com.baomidou.mybatisplus.core.toolkit.Wrappers.<OrderRevenueSplit>lambdaUpdate()
                    .eq(OrderRevenueSplit::getSplitId, split.getSplitId())
                    .set(OrderRevenueSplit::getFailureReason, null));
            return PreparedSubmission.terminal(split);
        }
        if (WECHAT_SUBMITTED.equals(split.getStatus())) {
            return PreparedSubmission.terminal(split);
        }
        if (split.getPlatformCents() <= 0) {
            // 拍板 2026-10-17「平台分账先不收」：0 抽成的单 merchantCents 就是订单毛额，
            // 全额分账是否被微信服务商允许**未实证**（CB-032 的 30% 单订单上限仅 ⚪ 旁证），
            // 而且这笔钱已由 RevenueSplitService 入商户钱包 ⇒ 再发分账就是双付。
            // 三个入口（改单自动提交 / 运营手工提交 / 失败重试）都汇到这里，闸放在唯一出口上。
            // 状态**不动**：这里没有钱包服务，翻了状态反而把钱留在无人入账的中间态。
            log.warn("profit sharing skipped: platform share is zero (rate 0) splitId={} orderId={} gross={}",
                    split.getSplitId(), split.getOrderId(), split.getGrossCents());
            return PreparedSubmission.terminal(split);
        }

        String outOrderNo = split.getWechatOutOrderNo();
        if (outOrderNo == null || outOrderNo.isBlank()) {
            outOrderNo = split.getSplitId();
            split.setWechatOutOrderNo(outOrderNo);
            // B2：out_order_no 先落库再打微信——重试/崩溃恢复时幂等键不丢
            splitRepository.update(null, com.baomidou.mybatisplus.core.toolkit.Wrappers
                    .<OrderRevenueSplit>lambdaUpdate()
                    .eq(OrderRevenueSplit::getSplitId, split.getSplitId())
                    .set(OrderRevenueSplit::getWechatOutOrderNo, outOrderNo));
        }

        String trimmedWxTxn = wxTransactionId.trim();

        if (profitSharingProperties.mockEnabled()) {
            split.setWechatTransactionId(trimmedWxTxn);
            split.setStatus(WECHAT_SUBMITTED);
            split.setFailureReason(null);
            log.info("mock profit sharing submitted splitId={} orderId={} wxTxn={}",
                    split.getSplitId(), split.getOrderId(), trimmedWxTxn);
            splitRepository.save(split);
            // M01：updateById 忽略 null 列，清列须 wrapper 显式 set(null)
            splitRepository.update(null, com.baomidou.mybatisplus.core.toolkit.Wrappers.<OrderRevenueSplit>lambdaUpdate()
                    .eq(OrderRevenueSplit::getSplitId, split.getSplitId())
                    .set(OrderRevenueSplit::getFailureReason, null));
            return PreparedSubmission.terminal(split);
        }

        Map<String, Object> receiver = new LinkedHashMap<>();
        receiver.put("type", "MERCHANT_ID");
        receiver.put("account", merchant.getWechatReceiverId().trim());
        receiver.put("amount", split.getMerchantCents());
        receiver.put("description", "商户分账-" + split.getOrderId());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("appid", weChatPayProperties.appId());
        body.put("transaction_id", trimmedWxTxn);
        body.put("out_order_no", outOrderNo);
        body.put("receivers", List.of(receiver));
        body.put("unfreeze_unsplit", true);
        return new PreparedSubmission(split, body, trimmedWxTxn, false);
    }

    /** tx2：微信受理成功，落 WECHAT_SUBMITTED（以 splitId 重读最新行，不携带跨事务旧实体）。 */
    @Transactional
    public OrderRevenueSplit finalizeSubmitted(OrderRevenueSplit prep, String wxTransactionId, String wxOrderId) {
        OrderRevenueSplit fresh = splitRepository.findById(prep.getSplitId()).orElse(prep);
        fresh.setWechatTransactionId(wxTransactionId);
        fresh.setStatus(WECHAT_SUBMITTED);
        fresh.setFailureReason(null);
        log.info("wechat profit sharing submitted splitId={} orderId={} wxOrderId={}",
                fresh.getSplitId(), fresh.getOrderId(), wxOrderId);
        splitRepository.save(fresh);
        // 审计批次4（M01）：重提成功后 failure_reason 须真清列——updateById 忽略 null，
        // 不补 wrapper 则上次失败文案残留在库。
        splitRepository.update(null, com.baomidou.mybatisplus.core.toolkit.Wrappers
                .<OrderRevenueSplit>lambdaUpdate()
                .eq(OrderRevenueSplit::getSplitId, fresh.getSplitId())
                .set(OrderRevenueSplit::getFailureReason, null));
        return fresh;
    }

    /** tx2'：微信受理失败，落 WECHAT_FAILED（异常在此消化，不回滚调用方业务）。 */
    @Transactional
    public OrderRevenueSplit finalizeFailed(OrderRevenueSplit prep, String wxTransactionId, Exception cause) {
        OrderRevenueSplit fresh = splitRepository.findById(prep.getSplitId()).orElse(prep);
        fresh.setStatus("WECHAT_FAILED");
        fresh.setWechatTransactionId(wxTransactionId);
        fresh.setFailureReason(truncate(cause.getMessage()));
        log.warn("wechat profit sharing failed splitId={}", fresh.getSplitId(), cause);
        splitRepository.save(fresh);
        return fresh;
    }

    /** prepareSubmit 的产出：terminal=true 表示无需 HTTP（短路/mock 已在 tx1 落库）。 */
    public record PreparedSubmission(OrderRevenueSplit split, Map<String, Object> body,
                                     String wxTransactionId, boolean terminal) {
        static PreparedSubmission terminal(OrderRevenueSplit split) {
            return new PreparedSubmission(split, null, null, true);
        }
    }

    /**
     * 分账回退提交结果。
     */
    public enum ReturnSubmitOutcome {
        SUCCESS, PROCESSING, FAILED
    }

    /**
     * 分账回退：部分退款/全额退款时从接收方回退已分金额。
     * 纯渠道调用，不写库——不要加事务（调用方若在事务内，HTTP 仍会占用该事务，见 P2-7）。
     */
    public ReturnSubmitOutcome returnMerchantShare(OrderRevenueSplit split,
                                                   Merchant merchant,
                                                   long returnCents,
                                                   String outReturnNo,
                                                   String description) {
        if (split == null || merchant == null || returnCents <= 0) {
            return ReturnSubmitOutcome.FAILED;
        }
        if (merchant.getWechatReceiverId() == null || merchant.getWechatReceiverId().isBlank()) {
            log.warn("profit sharing return skipped: merchant missing wechatReceiverId splitId={}", split.getSplitId());
            return ReturnSubmitOutcome.FAILED;
        }
        String outOrderNo = split.getWechatOutOrderNo();
        if (outOrderNo == null || outOrderNo.isBlank()) {
            outOrderNo = split.getSplitId();
        }
        String returnNo = requireStableReturnNo(outReturnNo);
        if (profitSharingProperties.mockEnabled()) {
            log.info("mock profit sharing return splitId={} orderId={} amount={} outReturnNo={}",
                    split.getSplitId(), split.getOrderId(), returnCents, returnNo);
            return ReturnSubmitOutcome.SUCCESS;
        }
        if (!isApiReady()) {
            return ReturnSubmitOutcome.FAILED;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("out_order_no", outOrderNo);
        body.put("out_return_no", returnNo);
        body.put("return_mchid", merchant.getWechatReceiverId().trim());
        body.put("amount", returnCents);
        body.put("description", truncate(description != null ? description : "分账回退"));
        try {
            JsonNode resp = weChatPayV3Client.post("/v3/profitsharing/return-orders", body);
            ReturnSubmitOutcome outcome = mapReturnResult(resp.path("result").asText(""));
            log.info("wechat profit sharing return submitted splitId={} outReturnNo={} amount={} outcome={}",
                    split.getSplitId(), returnNo, returnCents, outcome);
            return outcome;
        } catch (Exception e) {
            log.warn("wechat profit sharing return failed splitId={} outReturnNo={}",
                    split.getSplitId(), returnNo, e);
            return ReturnSubmitOutcome.FAILED;
        }
    }

    /**
     * 分账回退单号必须由调用方给出并持久化（{@code wechat_pending_return_no}）。
     *
     * <p>缺失即拒绝，<b>不得</b>现场生成（lessons #97/#225）：重试换号会让渠道侧
     * 无法识别为同一笔回退 ⇒ 重复回退窗口。
     */
    static String requireStableReturnNo(String outReturnNo) {
        if (outReturnNo == null || outReturnNo.isBlank()) {
            throw new IllegalArgumentException("缺少稳定分账回退单号（outReturnNo）");
        }
        return outReturnNo.trim();
    }

    /**
     * 查询分账回退单状态。
     */
    public ReturnSubmitOutcome queryReturnOutcome(String outReturnNo) {
        if (outReturnNo == null || outReturnNo.isBlank()) {
            return ReturnSubmitOutcome.FAILED;
        }
        if (profitSharingProperties.mockEnabled()) {
            return ReturnSubmitOutcome.SUCCESS;
        }
        if (!isApiReady()) {
            return ReturnSubmitOutcome.FAILED;
        }
        try {
            String encoded = URLEncoder.encode(outReturnNo.trim(), StandardCharsets.UTF_8);
            JsonNode resp = weChatPayV3Client.get("/v3/profitsharing/return-orders/" + encoded);
            return mapReturnResult(resp.path("result").asText(""));
        } catch (Exception e) {
            log.warn("wechat profit sharing return query failed outReturnNo={}", outReturnNo, e);
            return ReturnSubmitOutcome.FAILED;
        }
    }

    /**
     * 轮询待确认的分账回退：微信查单 HTTP 在事务外，落库走短事务。
     * R20-9 / CB-085：单号与金额只从「腿」读，split 上的两列只是派生投影。
     */
    public boolean refreshPendingReturn(OrderRevenueSplit split, OrderSplitReturnLeg leg) {
        if (split == null || leg == null || !ProfitSharingReturnLegService.isOpen(leg.getStatus())) {
            return false;
        }
        ReturnSubmitOutcome outcome = queryReturnOutcome(leg.getOutReturnNo());
        return self.applyReturnOutcome(split, leg, outcome);
    }

    /** 存量兼容：按 split 取最老一条未完结腿再查单；生产路径一律走带 leg 的重载。 */
    public boolean refreshPendingReturn(OrderRevenueSplit split) {
        if (split == null) {
            return false;
        }
        List<OrderSplitReturnLeg> open = returnLegService.openLegs(split.getSplitId());
        return !open.isEmpty() && refreshPendingReturn(split, open.get(0));
    }

    /** 批量轮询；单条异常只记日志，不能让一条坏行中断整批。 */
    public int pollPendingReturns(List<OrderRevenueSplit> splits) {
        if (!isApiReady() || splits == null || splits.isEmpty()) {
            return 0;
        }
        List<OrderSplitReturnLeg> legs = splits.stream()
                .flatMap(s -> returnLegService.openLegs(s.getSplitId()).stream())
                .toList();
        return pollOpenReturnLegs(legs);
    }

    /** R20-9：定时兜底按腿轮询，一次批量取回全部相关 split（不按腿逐个查库）。 */
    public int pollOpenReturnLegs(List<OrderSplitReturnLeg> legs) {
        if (!isApiReady() || legs == null || legs.isEmpty()) {
            return 0;
        }
        Map<String, OrderRevenueSplit> splits = loadSplitsForLegs(legs);
        int updated = 0;
        for (OrderSplitReturnLeg leg : legs) {
            OrderRevenueSplit split = splits.get(leg.getSplitId());
            if (split == null) {
                continue;
            }
            try {
                if (self.refreshPendingReturn(split, leg)) {
                    updated++;
                }
            } catch (Exception e) {
                log.warn("wechat profit sharing return poll failed splitId={} legId={}",
                        split.getSplitId(), leg.getLegId(), e);
            }
        }
        return updated;
    }

    /** 一次 selectBatchIds + LinkedHashMap 分组，避免按腿 N+1（铁律 88②）。 */
    private Map<String, OrderRevenueSplit> loadSplitsForLegs(List<OrderSplitReturnLeg> legs) {
        Set<String> ids = new LinkedHashSet<>();
        for (OrderSplitReturnLeg leg : legs) {
            if (leg != null && leg.getSplitId() != null) {
                ids.add(leg.getSplitId());
            }
        }
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<String, OrderRevenueSplit> byId = new LinkedHashMap<>();
        for (OrderRevenueSplit split : splitRepository.selectBatchIds(ids)) {
            byId.put(split.getSplitId(), split);
        }
        return byId;
    }

    /**
     * 重试失败的分账回退。批方法不加事务：HTTP 在事务外，单条结果由
     * {@link #applyReturnOutcome(OrderRevenueSplit, OrderSplitReturnLeg, ReturnSubmitOutcome)} 短事务落库。
     */
    public int retryFailedReturns(List<OrderRevenueSplit> splits, Map<String, Merchant> merchantsById) {
        if (!isApiReady() || splits == null || splits.isEmpty()) {
            return 0;
        }
        List<OrderSplitReturnLeg> legs = splits.stream()
                .flatMap(s -> returnLegService.openLegs(s.getSplitId()).stream())
                .filter(l -> ProfitSharingReturnLegService.STATUS_FAILED.equals(l.getStatus()))
                .toList();
        return retryFailedReturnLegs(legs, merchantsById);
    }

    /** R20-9：按腿重提，金额与单号都取腿，绝不从 split 单值读。 */
    public int retryFailedReturnLegs(List<OrderSplitReturnLeg> legs, Map<String, Merchant> merchantsById) {
        if (!isApiReady() || legs == null || legs.isEmpty()) {
            return 0;
        }
        Map<String, OrderRevenueSplit> splits = loadSplitsForLegs(legs);
        int retried = 0;
        for (OrderSplitReturnLeg leg : legs) {
            OrderRevenueSplit split = splits.get(leg.getSplitId());
            if (split == null) {
                continue;
            }
            try {
                if (retrySingleFailedReturn(split, leg, merchantsById)) {
                    retried++;
                }
            } catch (Exception e) {
                log.warn("wechat profit sharing return retry failed splitId={} legId={}",
                        split.getSplitId(), leg.getLegId(), e);
            }
        }
        return retried;
    }

    private boolean retrySingleFailedReturn(OrderRevenueSplit split,
                                            OrderSplitReturnLeg leg,
                                            Map<String, Merchant> merchantsById) {
        if (leg == null || !ProfitSharingReturnLegService.isOpen(leg.getStatus()) || leg.getCents() <= 0) {
            return false;
        }
        Merchant merchant = merchantsById.get(split.getMerchantId());
        if (merchant == null) {
            return false;
        }
        // P3-待定③（CB-053 官方口径「先查单、同号重试防重复退款」，拍板材料 2026-11-13）：
        // 重试前先查单——微信侧已受理（PROCESSING/SUCCESS）不再重提，防同号重复回退；
        // 查单 FAILED（真失败或查询通道异常）才走同号重提（同号幂等兜底，outReturnNo 稳定存腿行）。
        ReturnSubmitOutcome queried = queryReturnOutcome(leg.getOutReturnNo());
        if (queried != ReturnSubmitOutcome.FAILED) {
            self.applyReturnOutcome(split, leg, queried);
            return true;
        }
        ReturnSubmitOutcome outcome = self.returnMerchantShare(
                split,
                merchant,
                leg.getCents(),
                leg.getOutReturnNo(),
                leg.getFailureReason() != null ? leg.getFailureReason() : "分账回退重试");
        self.applyReturnOutcome(split, leg, outcome);
        return outcome != ReturnSubmitOutcome.FAILED;
    }

    /** 短事务：只把查单/回退结果落到腿 + 投影，不包含任何渠道 HTTP。 */
    @Transactional
    public boolean applyReturnOutcome(OrderRevenueSplit split,
                                      OrderSplitReturnLeg leg,
                                      ReturnSubmitOutcome outcome) {
        if (split == null || leg == null || outcome == null) {
            return false;
        }
        String legReason = outcome == ReturnSubmitOutcome.FAILED
                ? (leg.getFailureReason() != null ? leg.getFailureReason() : "分账回退未成功需人工处理")
                : null;
        returnLegService.recordOutcome(split, leg.getOutReturnNo(), leg.getCents(), outcome, legReason);
        String splitReason = outcome == ReturnSubmitOutcome.FAILED ? "分账回退未成功需人工处理" : null;
        split.setFailureReason(splitReason);
        // M01：updateById 忽略 null 列，清列须 wrapper 显式 set(null)
        splitRepository.update(null, com.baomidou.mybatisplus.core.toolkit.Wrappers.<OrderRevenueSplit>lambdaUpdate()
                .eq(OrderRevenueSplit::getSplitId, split.getSplitId())
                .set(OrderRevenueSplit::getFailureReason, splitReason));
        if (outcome == ReturnSubmitOutcome.SUCCESS) {
            log.info("profit sharing return confirmed splitId={} legId={} order={}",
                    split.getSplitId(), leg.getLegId(), split.getOrderId());
            return true;
        }
        return outcome == ReturnSubmitOutcome.FAILED;
    }

    /** 存量兼容入口：按 split 的最老开放腿落结果。 */
    @Transactional
    public boolean applyReturnOutcome(OrderRevenueSplit split, ReturnSubmitOutcome outcome) {
        if (split == null || outcome == null) {
            return false;
        }
        List<OrderSplitReturnLeg> open = returnLegService.openLegs(split.getSplitId());
        return !open.isEmpty() && applyReturnOutcome(split, open.get(0), outcome);
    }

    private static ReturnSubmitOutcome mapReturnResult(String result) {
        if (result == null || result.isBlank()) {
            return ReturnSubmitOutcome.PROCESSING;
        }
        return switch (result.toUpperCase()) {
            case "SUCCESS" -> ReturnSubmitOutcome.SUCCESS;
            case "FAIL", "FAILED" -> ReturnSubmitOutcome.FAILED;
            default -> ReturnSubmitOutcome.PROCESSING;
        };
    }

    /**
     * 查询微信分账单状态并同步本地记录：查单 HTTP 在事务外，落库走短事务。
     */
    public OrderRevenueSplit refreshSplitStatus(OrderRevenueSplit split) {
        if (!isApiReady()) {
            throw new IllegalStateException("WeChat profit sharing is not enabled or pay is not configured");
        }
        String wxTxn = split.getWechatTransactionId();
        String outOrderNo = split.getWechatOutOrderNo();
        if (wxTxn == null || wxTxn.isBlank() || outOrderNo == null || outOrderNo.isBlank()) {
            throw new IllegalStateException("split missing wxTransactionId or wechatOutOrderNo");
        }
        if (LEDGER_ONLY.equals(split.getStatus()) || split.getMerchantCents() <= 0) {
            return split;
        }
        if (profitSharingProperties.mockEnabled()) {
            applyRemoteState(split, "FINISHED", null);
            log.info("mock profit sharing refreshed splitId={} status={}", split.getSplitId(), split.getStatus());
            return self.persistSplit(split);
        }
        try {
            String encodedOut = URLEncoder.encode(outOrderNo.trim(), StandardCharsets.UTF_8);
            String path = "/v3/profitsharing/orders/" + encodedOut
                    + "?transaction_id=" + URLEncoder.encode(wxTxn.trim(), StandardCharsets.UTF_8);
            JsonNode resp = weChatPayV3Client.get(path);
            String state = resp.path("state").asText("");
            applyRemoteState(split, state, resp.path("fail_reason").asText(null));
            return self.persistSplit(split);
        } catch (Exception e) {
            split.setFailureReason(truncate(e.getMessage()));
            log.warn("wechat profit sharing query failed splitId={}", split.getSplitId(), e);
            return self.persistSplit(split);
        }
    }

    /** 短事务：只落 split 状态，不包含任何渠道 HTTP。 */
    @Transactional
    public OrderRevenueSplit persistSplit(OrderRevenueSplit split) {
        OrderRevenueSplit saved = splitRepository.save(split);
        if (saved.getFailureReason() == null && saved.getSplitId() != null) {
            // 审计 R11-B-N1（M01 同家族第 7 处）：updateById/save 默认 NOT_NULL 策略跳 null ——
            // applyRemoteState 把 FAILED 单刷新成 FINISHED/PROCESSING 时 setFailureReason(null)
            // 不会清列，旧失败文案残留库中与 WECHAT_SUBMITTED 自相矛盾；照 markLedgerOnly 模板补真清列。
            splitRepository.update(null, com.baomidou.mybatisplus.core.toolkit.Wrappers
                    .<OrderRevenueSplit>lambdaUpdate()
                    .eq(OrderRevenueSplit::getSplitId, saved.getSplitId())
                    .set(OrderRevenueSplit::getFailureReason, null));
        }
        return saved;
    }

    /**
     * 重试失败分账。B2：不再包整体事务——旧实现把所有重试连在一个事务里，
     * HTTP 也在其中；现在每次 submitSplit 自管三段式事务，单条失败不牵连其它。
     */
    public int retryFailedSplits(List<OrderRevenueSplit> splits, Map<String, Merchant> merchantsById) {
        if (!isApiReady() || splits.isEmpty()) {
            return 0;
        }
        int retried = 0;
        for (OrderRevenueSplit split : splits) {
            // 单条坏行只跳过自己：旧实现让异常冒到调度器，整批回滚且后面的失败分账永不重试。
            try {
                Merchant merchant = merchantsById.get(split.getMerchantId());
                String wxTxn = split.getWechatTransactionId();
                if (merchant != null && wxTxn != null && !wxTxn.isBlank()) {
                    self.submitSplit(split, merchant, wxTxn);
                    retried++;
                } else {
                    log.warn("profit sharing retry skipped splitId={} merchant={} wxTxnBlank={}",
                            split.getSplitId(), split.getMerchantId(), wxTxn == null || wxTxn.isBlank());
                }
            } catch (Exception e) {
                log.warn("profit sharing retry failed splitId={}", split.getSplitId(), e);
            }
        }
        return retried;
    }

    private void applyRemoteState(OrderRevenueSplit split, String state, String failReason) {
        if (state == null || state.isBlank()) {
            return;
        }
        switch (state.toUpperCase()) {
            case "FINISHED", "PROCESSING" -> {
                split.setStatus(WECHAT_SUBMITTED);
                split.setFailureReason(null);
            }
            case "CLOSED" -> {
                if (failReason != null && !failReason.isBlank()) {
                    split.setStatus("WECHAT_FAILED");
                    split.setFailureReason(truncate(failReason));
                } else {
                    split.setStatus(WECHAT_SUBMITTED);
                    split.setFailureReason(null);
                }
            }
            default -> log.debug("unknown wechat profit sharing state={} splitId={}", state, split.getSplitId());
        }
    }

    @Transactional
    public void markLedgerOnly(OrderRevenueSplit split, String reason) {
        split.setStatus(LEDGER_ONLY);
        split.setFailureReason(reason != null ? truncate(reason) : null);
        splitRepository.save(split);
        if (split.getFailureReason() == null) {
            // 审计批次4（M01）：同上，置 null 时补 wrapper 真清列。
            splitRepository.update(null, com.baomidou.mybatisplus.core.toolkit.Wrappers
                    .<OrderRevenueSplit>lambdaUpdate()
                    .eq(OrderRevenueSplit::getSplitId, split.getSplitId())
                    .set(OrderRevenueSplit::getFailureReason, null));
        }
    }

    private static String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() > 500 ? message.substring(0, 500) : message;
    }
}
