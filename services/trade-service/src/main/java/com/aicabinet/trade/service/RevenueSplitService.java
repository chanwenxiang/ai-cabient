package com.aicabinet.trade.service;

import com.aicabinet.trade.util.BizIds;
import com.aicabinet.trade.domain.CabinetOrder;
import com.aicabinet.trade.domain.DeviceInfo;
import com.aicabinet.trade.domain.Merchant;
import com.aicabinet.trade.domain.OrderRevenueSplit;
import com.aicabinet.trade.payment.WeChatProfitSharingService;
import com.aicabinet.trade.payment.WeChatProfitSharingService.ReturnSubmitOutcome;
import com.aicabinet.trade.mapper.DeviceInfoMapper;
import com.aicabinet.trade.mapper.MerchantMapper;
import com.aicabinet.trade.mapper.OrderRevenueSplitMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.Optional;
@Service
public class RevenueSplitService {
    private static final String WECHAT_SUBMITTED = "WECHAT_SUBMITTED";
    private static final String LEDGER_ONLY = "LEDGER_ONLY";
    private static final String STATUS_SUCCESS = "SUCCESS";
    private static final String SETTLED = "SETTLED";
    private static final String ACCRUED = "ACCRUED";
    private static final String VOIDED = "VOIDED";


    private static final Logger log = LoggerFactory.getLogger(RevenueSplitService.class);

    private final OrderRevenueSplitMapper splitRepository;
    private final DeviceInfoMapper deviceRepository;
    private final MerchantMapper merchantRepository;
    private final WeChatProfitSharingService profitSharingService;
    private final MerchantWalletService merchantWalletService;
    private final ProfitSharingReturnCompensationService returnCompensationService;
    private final ProfitSharingReturnAlertService profitSharingAlertService;
    private final DistributedLockService distributedLockService;

    public RevenueSplitService(OrderRevenueSplitMapper splitRepository,
                               DeviceInfoMapper deviceRepository,
                               MerchantMapper merchantRepository,
                               WeChatProfitSharingService profitSharingService,
                               MerchantWalletService merchantWalletService,
                               ProfitSharingReturnCompensationService returnCompensationService,
                               ProfitSharingReturnAlertService profitSharingAlertService,
                               DistributedLockService distributedLockService) {
        this.splitRepository = splitRepository;
        this.deviceRepository = deviceRepository;
        this.merchantRepository = merchantRepository;
        this.profitSharingService = profitSharingService;
        this.merchantWalletService = merchantWalletService;
        this.returnCompensationService = returnCompensationService;
        this.profitSharingAlertService = profitSharingAlertService;
        this.distributedLockService = distributedLockService;
    }

    @Transactional
    public Optional<OrderRevenueSplit> recordSplit(CabinetOrder order) {
        if (order == null || order.getOrderId() == null || order.getOrderId().isBlank()) {
            return Optional.empty();
        }
        return runWithOrderSplitLock(order.getOrderId(), () -> doRecordSplit(order));
    }

    private Optional<OrderRevenueSplit> doRecordSplit(CabinetOrder order) {
        Optional<OrderRevenueSplit> existing = splitRepository.findByOrderIdForUpdate(order.getOrderId());
        if (existing.isPresent()) {
            return existing;
        }
        DeviceInfo device = deviceRepository.findById(order.getDeviceId()).orElse(null);
        if (device == null || device.getMerchantId() == null || device.getMerchantId().isBlank()) {
            log.debug("skip revenue split: no merchant on device {}", order.getDeviceId());
            return Optional.empty();
        }
        Merchant merchant = merchantRepository.findById(device.getMerchantId()).orElse(null);
        if (merchant == null || !"ACTIVE".equalsIgnoreCase(merchant.getStatus())) {
            log.warn("skip revenue split: merchant missing or inactive {}", device.getMerchantId());
            return Optional.empty();
        }

        long gross = order.getTotalAmountCents();
        long platform = gross * merchant.getPlatformRateBps() / 10_000L;
        long merchantShare = gross - platform;

        OrderRevenueSplit split = new OrderRevenueSplit();
        split.setSplitId(BizIds.nextNumeric());
        split.setOrderId(order.getOrderId());
        split.setMerchantId(merchant.getMerchantId());
        split.setDeviceId(order.getDeviceId());
        split.setGrossCents(gross);
        split.setPlatformCents(platform);
        split.setMerchantCents(merchantShare);
        split.setSettleAfter(LocalDate.now().plusDays(1));
        split.setSettlementBatchNo("MS-" + LocalDate.now() + "-" + merchant.getMerchantId());
        if (merchant.getWechatReceiverId() == null || merchant.getWechatReceiverId().isBlank()) {
            split.setStatus(LEDGER_ONLY);
        } else if (!profitSharingService.isApiReady()) {
            split.setStatus(ACCRUED);
        } else {
            split.setStatus(ACCRUED);
        }
        try {
            splitRepository.save(split);
        } catch (DuplicateKeyException e) {
            log.info("split insert raced order={} splitId={}", order.getOrderId(), split.getSplitId());
            return splitRepository.findByOrderIdForUpdate(order.getOrderId());
        }
        creditWalletIfLedgerOnly(split);
        maybeAutoSubmitAccruedSplit(order, split, merchant, 0);
        log.info("分账记账 order={} merchant={} gross={} platform={} merchantShare={}",
                order.getOrderId(), merchant.getMerchantId(), gross, platform, merchantShare);
        return Optional.of(split);
    }

    /**
     * 订单全额退款后冲正分账：已有分账记录改为 VOIDED，避免账本仍显示应分金额。
     */
    @Transactional
    public void voidSplitOnFullRefund(String orderId) {
        if (orderId == null || orderId.isBlank()) {
            return;
        }
        runWithOrderSplitLock(orderId, () -> doVoidSplitOnFullRefund(orderId));
    }

    private void doVoidSplitOnFullRefund(String orderId) {
        splitRepository.findByOrderIdForUpdate(orderId).ifPresent(split -> {
            if (VOIDED.equalsIgnoreCase(split.getStatus()) || "REVERSED".equalsIgnoreCase(split.getStatus())) {
                return;
            }
            attemptWeChatReturnOnVoid(split);
            reverseWalletCredit(split);
            split.setStatus(VOIDED);
            split.setFailureReason(null);
            splitRepository.save(split);
            log.info("分账已冲正（全额退款） order={} splitId={}", orderId, split.getSplitId());
        });
    }

    /**
     * 争议改单/部分退后按应付变化调整分账（增额重算、减额部分冲正/回退）。
     */
    @Transactional
    public void adjustSplitAfterOrderChange(CabinetOrder order, int priorPayableCents) {
        if (order == null || order.getOrderId() == null || order.getOrderId().isBlank()) {
            return;
        }
        runWithOrderSplitLock(order.getOrderId(), () -> doAdjustSplitAfterOrderChange(order, priorPayableCents));
    }

    private void doAdjustSplitAfterOrderChange(CabinetOrder order, int priorPayableCents) {
        int newPayable = Math.max(0, order.getTotalAmountCents());
        if (newPayable <= 0) {
            doVoidSplitOnFullRefund(order.getOrderId());
            return;
        }
        if (newPayable >= priorPayableCents) {
            doResyncSplitForOrder(order);
            return;
        }
        doAdjustSplitAfterPartialRefund(order, false);
    }

    /**
     * 部分退款/改单后按新旧商户分成差额冲正钱包（仅已入账的 LEDGER_ONLY / SETTLED）。
     */
    private void clawbackWalletDelta(OrderRevenueSplit split, long oldMerchantCents, long newMerchantCents) {
        if (split == null || split.getMerchantId() == null || split.getSplitId() == null) {
            return;
        }
        String status = split.getStatus() == null ? "" : split.getStatus().toUpperCase();
        if (!LEDGER_ONLY.equals(status) && !SETTLED.equals(status)) {
            return;
        }
        long delta = oldMerchantCents - newMerchantCents;
        if (delta <= 0) {
            return;
        }
        String refId = split.getSplitId() + ":g" + newMerchantCents;
        boolean debited = merchantWalletService.debitIfAbsent(
                split.getMerchantId(),
                delta,
                "SPLIT_PARTIAL_REVERSE",
                "SPLIT_PARTIAL_REV",
                refId,
                "分账部分冲正 " + split.getOrderId());
        if (debited) {
            log.info("商户钱包部分冲正 merchant={} amount={} splitId={} newMerchant={}",
                    split.getMerchantId(), delta, split.getSplitId(), newMerchantCents);
        }
    }

    /**
     * 部分/全额退款后调整分账：账本重算、钱包冲正，已提交微信分账则本地重算并标记需人工回退。
     */
    @Transactional
    public void adjustSplitAfterPartialRefund(CabinetOrder order, boolean fullRefund) {
        if (order == null || order.getOrderId() == null || order.getOrderId().isBlank()) {
            return;
        }
        runWithOrderSplitLock(order.getOrderId(), () -> doAdjustSplitAfterPartialRefund(order, fullRefund));
    }

    private void doAdjustSplitAfterPartialRefund(CabinetOrder order, boolean fullRefund) {
        if (fullRefund) {
            doVoidSplitOnFullRefund(order.getOrderId());
            return;
        }
        var existing = splitRepository.findByOrderIdForUpdate(order.getOrderId());
        if (existing.isEmpty()) {
            return;
        }
        OrderRevenueSplit split = existing.get();
        String status = split.getStatus() == null ? "" : split.getStatus().toUpperCase();
        if (WECHAT_SUBMITTED.equals(status) || STATUS_SUCCESS.equals(status)) {
            resyncSubmittedSplitAfterPartialRefund(order, split);
            return;
        }
        doResyncSplitForOrder(order);
    }

    private void resyncSubmittedSplitAfterPartialRefund(CabinetOrder order, OrderRevenueSplit split) {
        long oldMerchantCents = Math.max(0, split.getMerchantCents());
        Merchant merchant = merchantRepository.findById(split.getMerchantId()).orElse(null);
        int rateBps = merchant != null ? merchant.getPlatformRateBps() : 0;
        long gross = Math.max(0, order.getTotalAmountCents());
        long platform = gross * rateBps / 10_000L;
        long merchantShare = gross - platform;
        long returnCents = Math.max(0, oldMerchantCents - merchantShare);
        if (returnCents > 0 && merchant != null) {
            String outReturnNo = "PSR" + split.getSplitId() + ":g" + merchantShare;
            applyWeChatReturnOutcome(split, outReturnNo, returnCents,
                    profitSharingService.returnMerchantShare(
                            split, merchant, returnCents, outReturnNo, "部分退款分账回退 " + order.getOrderId()),
                    "部分退款后分账金额已重算，分账回退未成功需人工处理");
        }
        split.setGrossCents(gross);
        split.setPlatformCents(platform);
        split.setMerchantCents(merchantShare);
        if (gross <= 0) {
            split.setStatus(VOIDED);
            split.setFailureReason("全额退款后分账作废");
        } else if (returnCents > 0 && split.getFailureReason() != null) {
            // failureReason 已由 applyWeChatReturnOutcome 设置
        } else {
            split.setFailureReason(null);
        }
        splitRepository.save(split);
        log.warn("split adjusted after partial refund (submitted wechat) order={} splitId={} oldMerchant={} newMerchant={} returnCents={} pendingReturn={}",
                order.getOrderId(), split.getSplitId(), oldMerchantCents, merchantShare, returnCents,
                split.getWechatPendingReturnNo());
    }

    /** 争议改单增额：已提交微信分账仅本地重算并标记需人工补分账。 */
    private void resyncSubmittedSplitAfterIncrease(CabinetOrder order, OrderRevenueSplit split) {
        long oldMerchantCents = Math.max(0, split.getMerchantCents());
        Merchant merchant = merchantRepository.findById(split.getMerchantId()).orElse(null);
        int rateBps = merchant != null ? merchant.getPlatformRateBps() : 0;
        long gross = Math.max(0, order.getTotalAmountCents());
        long platform = gross * rateBps / 10_000L;
        long merchantShare = gross - platform;
        if (merchantShare <= oldMerchantCents) {
            log.info("skip submitted split increase resync order={} oldMerchant={} newMerchant={}",
                    order.getOrderId(), oldMerchantCents, merchantShare);
            return;
        }
        split.setGrossCents(gross);
        split.setPlatformCents(platform);
        split.setMerchantCents(merchantShare);
        split.setFailureReason("改单增额后分账金额已重算，需人工补分账");
        splitRepository.save(split);
        profitSharingAlertService.sendManualSupplementRequired(split, oldMerchantCents, merchantShare);
        log.warn("submitted split recalculated after order increase order={} splitId={} oldMerchant={} newMerchant={}",
                order.getOrderId(), split.getSplitId(), oldMerchantCents, merchantShare);
    }

    private void attemptWeChatReturnOnVoid(OrderRevenueSplit split) {
        if (split == null || split.getMerchantId() == null) {
            return;
        }
        String status = split.getStatus() == null ? "" : split.getStatus().toUpperCase();
        if (!WECHAT_SUBMITTED.equals(status) && !STATUS_SUCCESS.equals(status)) {
            return;
        }
        long amount = Math.max(0, split.getMerchantCents());
        if (amount <= 0) {
            return;
        }
        Merchant merchant = merchantRepository.findById(split.getMerchantId()).orElse(null);
        if (merchant == null) {
            return;
        }
        String outReturnNo = "PSR-FULL-" + split.getSplitId();
        applyWeChatReturnOutcome(split, outReturnNo, amount,
                profitSharingService.returnMerchantShare(
                        split, merchant, amount, outReturnNo, "全额退款分账回退 " + split.getOrderId()),
                "全额退款分账回退未成功，需人工处理");
        if (split.getFailureReason() != null) {
            log.warn("wechat profit sharing full return failed splitId={} order={}", split.getSplitId(), split.getOrderId());
        }
    }

    private void applyWeChatReturnOutcome(OrderRevenueSplit split,
                                          String outReturnNo,
                                          long returnCents,
                                          ReturnSubmitOutcome outcome,
                                          String failureMessage) {
        if (split == null || returnCents <= 0 || outcome == null) {
            return;
        }
        switch (outcome) {
            case SUCCESS -> {
                split.setWechatPendingReturnNo(null);
                split.setWechatPendingReturnCents(null);
                split.setFailureReason(null);
            }
            case PROCESSING -> {
                split.setWechatPendingReturnNo(outReturnNo);
                split.setWechatPendingReturnCents(returnCents);
                split.setFailureReason(null);
            }
            case FAILED -> {
                split.setWechatPendingReturnNo(outReturnNo);
                split.setWechatPendingReturnCents(returnCents);
                split.setFailureReason(failureMessage);
                profitSharingAlertService.sendReturnSubmitFailed(split, outReturnNo, returnCents, failureMessage);
                returnCompensationService.scheduleReturnRetry(split, 60);
            }
        }
    }

    /**
     * 运营确认仅记账完结：无微信分账接收方时商户份额已入钱包，运营确认后不再占用「分账待跟进」。
     * 幂等：已 SETTLED / SUCCESS 直接返回。
     */
    @Transactional
    public OrderRevenueSplit confirmLedgerOnly(OrderRevenueSplit split) {
        if (split == null) {
            throw new IllegalArgumentException("split required");
        }
        if (split.getOrderId() == null || split.getOrderId().isBlank()) {
            throw new IllegalArgumentException("split orderId required");
        }
        return runWithOrderSplitLock(split.getOrderId(),
                () -> doConfirmLedgerOnly(split.getSplitId(), split.getOrderId()));
    }

    private OrderRevenueSplit doConfirmLedgerOnly(String splitId, String orderId) {
        OrderRevenueSplit split = splitRepository.findByOrderIdForUpdate(orderId)
                .orElseThrow(() -> new IllegalStateException("分账不存在"));
        if (!splitId.equals(split.getSplitId())) {
            throw new IllegalStateException("分账记录已变更");
        }
        String status = split.getStatus() == null ? "" : split.getStatus().toUpperCase();
        if (SETTLED.equals(status) || STATUS_SUCCESS.equals(status)) {
            return split;
        }
        if (!LEDGER_ONLY.equals(status)) {
            throw new IllegalStateException("仅 LEDGER_ONLY 可确认完结，当前=" + split.getStatus());
        }
        creditWalletIfLedgerOnly(split);
        split.setStatus(SETTLED);
        split.setSettledAt(java.time.Instant.now());
        split.setFailureReason(null);
        return splitRepository.save(split);
    }

    /** 账本型分账（无微信分账接收方）同步入商户可提现钱包，幂等按 splitId。 */
    private void creditWalletIfLedgerOnly(OrderRevenueSplit split) {
        if (split == null || !LEDGER_ONLY.equalsIgnoreCase(split.getStatus())) {
            return;
        }
        long amount = Math.max(0, split.getMerchantCents());
        if (amount <= 0 || split.getMerchantId() == null || split.getSplitId() == null) {
            return;
        }
        boolean credited = merchantWalletService.creditIfAbsent(
                split.getMerchantId(),
                amount,
                "SPLIT_CREDIT",
                "SPLIT",
                split.getSplitId(),
                "分账入账 " + split.getOrderId());
        if (credited) {
            log.info("商户钱包入账 merchant={} amount={} splitId={}",
                    split.getMerchantId(), amount, split.getSplitId());
        }
    }

    private void reverseWalletCredit(OrderRevenueSplit split) {
        if (split == null || split.getMerchantId() == null || split.getSplitId() == null) {
            return;
        }
        long amount = Math.max(0, split.getMerchantCents());
        if (amount <= 0) {
            return;
        }
        boolean reversed = merchantWalletService.reverseCreditIfPresent(
                split.getMerchantId(),
                amount,
                "SPLIT_REVERSE",
                "SPLIT_REV",
                split.getSplitId(),
                "SPLIT",
                split.getSplitId(),
                "分账冲正 " + split.getOrderId());
        if (reversed) {
            log.info("商户钱包冲正 merchant={} amount={} splitId={}",
                    split.getMerchantId(), amount, split.getSplitId());
        }
    }

    /**
     * 争议改单后按新订单金额重算分账（仅未终态账本可改；已提交微信/已冲正则跳过）。
     */
    @Transactional
    public void resyncSplitForOrder(CabinetOrder order) {
        if (order == null || order.getOrderId() == null || order.getOrderId().isBlank()) {
            return;
        }
        runWithOrderSplitLock(order.getOrderId(), () -> doResyncSplitForOrder(order));
    }

    private void doResyncSplitForOrder(CabinetOrder order) {
        var existing = splitRepository.findByOrderIdForUpdate(order.getOrderId());
        if (existing.isEmpty()) {
            if (order.getTotalAmountCents() > 0) {
                doRecordSplit(order);
            }
            return;
        }
        OrderRevenueSplit split = existing.get();
        String status = split.getStatus() == null ? "" : split.getStatus().toUpperCase();
        if (VOIDED.equals(status) || "REVERSED".equals(status)) {
            log.info("skip split resync order={} status={}", order.getOrderId(), split.getStatus());
            return;
        }
        if (WECHAT_SUBMITTED.equals(status) || STATUS_SUCCESS.equals(status)) {
            resyncSubmittedSplitAfterIncrease(order, split);
            return;
        }
        long oldMerchantCents = Math.max(0, split.getMerchantCents());
        Merchant merchant = merchantRepository.findById(split.getMerchantId()).orElse(null);
        int rateBps = merchant != null ? merchant.getPlatformRateBps() : 0;
        long gross = Math.max(0, order.getTotalAmountCents());
        long platform = gross * rateBps / 10_000L;
        long merchantShare = gross - platform;
        clawbackWalletDelta(split, oldMerchantCents, merchantShare);
        creditWalletDelta(split, oldMerchantCents, merchantShare);
        split.setGrossCents(gross);
        split.setPlatformCents(platform);
        split.setMerchantCents(merchantShare);
        if (gross <= 0) {
            split.setStatus(VOIDED);
        }
        splitRepository.save(split);
        maybeAutoSubmitAccruedSplit(order, split, merchant, oldMerchantCents);
        log.info("分账已按改单重算 order={} gross={} platform={} merchantShare={}",
                order.getOrderId(), gross, platform, merchantShare);
    }

    private void creditWalletDelta(OrderRevenueSplit split, long oldMerchantCents, long newMerchantCents) {
        if (split == null || split.getMerchantId() == null || split.getSplitId() == null) {
            return;
        }
        String status = split.getStatus() == null ? "" : split.getStatus().toUpperCase();
        if (!LEDGER_ONLY.equals(status) && !SETTLED.equals(status)) {
            return;
        }
        long delta = newMerchantCents - oldMerchantCents;
        if (delta <= 0) {
            return;
        }
        String refId = split.getSplitId() + ":g" + newMerchantCents;
        boolean credited = merchantWalletService.creditIfAbsent(
                split.getMerchantId(),
                delta,
                "SPLIT_PARTIAL_CREDIT",
                "SPLIT_PARTIAL",
                refId,
                "分账部分增额 " + split.getOrderId());
        if (credited) {
            log.info("商户钱包部分增额 merchant={} amount={} splitId={} newMerchant={}",
                    split.getMerchantId(), delta, split.getSplitId(), newMerchantCents);
        }
    }

    /**
     * 争议改单增额后：ACCRUED 且已有微信支付交易号时自动提交分账。
     */
    private void maybeAutoSubmitAccruedSplit(CabinetOrder order,
                                            OrderRevenueSplit split,
                                            Merchant merchant,
                                            long oldMerchantCents) {
        if (order == null || split == null || merchant == null) {
            return;
        }
        String status = split.getStatus() == null ? "" : split.getStatus().toUpperCase();
        if (!ACCRUED.equals(status) || split.getMerchantCents() <= 0) {
            return;
        }
        if (split.getMerchantCents() <= oldMerchantCents) {
            return;
        }
        if (merchant.getWechatReceiverId() == null || merchant.getWechatReceiverId().isBlank()) {
            return;
        }
        if (!profitSharingService.isApiReady()) {
            return;
        }
        String wxTxn = resolveWxTransactionId(order, split);
        if (wxTxn == null) {
            return;
        }
        profitSharingService.submitSplit(split, merchant, wxTxn);
        log.info("auto submit profit sharing after order increase order={} splitId={} wxTxn={}",
                order.getOrderId(), split.getSplitId(), wxTxn);
    }

    private static String resolveWxTransactionId(CabinetOrder order, OrderRevenueSplit split) {
        if (order != null && order.getPayTradeNo() != null && !order.getPayTradeNo().isBlank()) {
            return order.getPayTradeNo().trim();
        }
        if (split != null && split.getWechatTransactionId() != null && !split.getWechatTransactionId().isBlank()) {
            return split.getWechatTransactionId().trim();
        }
        return null;
    }

    static String orderSplitLockKey(String orderId) {
        return "order:split:" + orderId;
    }

    @FunctionalInterface
    private interface LockedSplitSupplier<T> {
        T get() throws Exception;
    }

    @FunctionalInterface
    private interface LockedSplitAction {
        void run() throws Exception;
    }

    private void runWithOrderSplitLock(String orderId, LockedSplitAction action) {
        runWithOrderSplitLock(orderId, () -> {
            action.run();
            return null;
        });
    }

    private <T> T runWithOrderSplitLock(String orderId, LockedSplitSupplier<T> action) {
        if (!distributedLockService.tryLock(orderSplitLockKey(orderId), 60, 5)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "分账处理中，请稍后重试");
        }
        try {
            return action.get();
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage(), e);
        } finally {
            distributedLockService.unlock(orderSplitLockKey(orderId));
        }
    }
}
