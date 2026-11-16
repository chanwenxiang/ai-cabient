package com.aicabinet.trade.service;

import com.aicabinet.trade.util.BizIds;
import com.aicabinet.trade.domain.CabinetOrder;
import com.aicabinet.trade.domain.DeviceInfo;
import com.aicabinet.trade.domain.Merchant;
import com.aicabinet.trade.domain.OrderRevenueSplit;
import com.aicabinet.trade.payment.WeChatProfitSharingService;
import com.aicabinet.trade.payment.WeChatProfitSharingService.ReturnSubmitOutcome;
import com.aicabinet.trade.mapper.CabinetOrderMapper;
import com.aicabinet.trade.mapper.DeviceInfoMapper;
import com.aicabinet.trade.mapper.MerchantMapper;
import com.aicabinet.trade.mapper.OrderRevenueSplitMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
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

    // R20-2：入账时点判据单源在 OrderCollectionGate（与 SettlementOrderFinalizeService 共用，
    // 防「分账挪到实收、积分还留在结算」这种半修状态）。


    private static final Logger log = LoggerFactory.getLogger(RevenueSplitService.class);

    private final OrderRevenueSplitMapper splitRepository;
    private final DeviceInfoMapper deviceRepository;
    private final MerchantMapper merchantRepository;
    private final WeChatProfitSharingService profitSharingService;
    private final MerchantWalletService merchantWalletService;
    private final ProfitSharingReturnCompensationService returnCompensationService;
    private final ProfitSharingReturnAlertService profitSharingAlertService;
    private final DistributedLockService distributedLockService;
    /** R20-9：分账回退按腿立账的唯一写入口，禁止再直接改 split 的两列 pending。 */
    private final ProfitSharingReturnLegService returnLegService;
    /**
     * R20-2：只为「运营确认完结」这一条手动入账通道读订单状态。
     * 门必须落在<b>钱动作本体</b>这一侧而不是调用方——调用方今天只有 MerchantService，
     * 明天再多一个入口就会绕过；放在这里任何调用者都得过同一道实收判据。
     */
    private final CabinetOrderMapper orderRepository;

    /**
     * MA-276：事务边界必须整体落在锁内 ⇒ recordSplit（无注解，持锁）经 self 代理调
     * {@link #doRecordSplitInTx}（REQUIRES_NEW）。自引用走 @Lazy 代理，避免构造环。
     */
    private final RevenueSplitService self;

    public RevenueSplitService(OrderRevenueSplitMapper splitRepository,
                               DeviceInfoMapper deviceRepository,
                               MerchantMapper merchantRepository,
                               WeChatProfitSharingService profitSharingService,
                               MerchantWalletService merchantWalletService,
                               ProfitSharingReturnCompensationService returnCompensationService,
                               ProfitSharingReturnAlertService profitSharingAlertService,
                               DistributedLockService distributedLockService,
                               ProfitSharingReturnLegService returnLegService,
                               CabinetOrderMapper orderRepository,
                               @org.springframework.context.annotation.Lazy RevenueSplitService self) {
        this.splitRepository = splitRepository;
        this.deviceRepository = deviceRepository;
        this.merchantRepository = merchantRepository;
        this.profitSharingService = profitSharingService;
        this.merchantWalletService = merchantWalletService;
        this.returnCompensationService = returnCompensationService;
        this.profitSharingAlertService = profitSharingAlertService;
        this.distributedLockService = distributedLockService;
        this.returnLegService = returnLegService;
        this.orderRepository = orderRepository;
        this.self = self;
    }

    /**
     * MA-276（C15/R23Q-15）：本方法**刻意不带** {@code @Transactional}。
     * 旧形态是「事务包住锁」：@Transactional 在方法入口开事务，锁在事务内获取、finally
     * 里提交前释放 ⇒ 锁失效窗口横跨「解锁 → 事务提交」，第二个持有者读不到未提交的分账行
     * 会再插一次；且 PostgreSQL 里 DuplicateKeyException 之后同事务任何查询都是 25P02，
     * catch 分支的重查根本跑不通。现在是「锁包住事务」：锁内经 self 代理进
     * {@link #doRecordSplitInTx}（REQUIRED——2026-11-16 修订：REQUIRES_NEW 会看不见结算
     * 编排外层事务里未提交的订单行导致 FK violation；重复插入防护由唯一键等待 + DKE 兜底，
     * 见内层方法 javadoc），DKE 分支是 join 形态下的真实兜底路径。
     */
    public Optional<OrderRevenueSplit> recordSplit(CabinetOrder order) {
        if (order == null || order.getOrderId() == null || order.getOrderId().isBlank()) {
            return Optional.empty();
        }
        // AU-182：guard 同步改净额口径（frozen=0 时回落 total，markPaid 时点行为等价；
        // 净额<=0 的全额退款残单不值得建 0 值分账行）
        if (CommissionBaseCalc.netCents(order) <= 0) {
            log.debug("skip revenue split: zero net order {}", order.getOrderId());
            return Optional.empty();
        }
        return runWithOrderSplitLock(order.getOrderId(), () -> self.doRecordSplitInTx(order));
    }

    /**
     * MA-276（2026-11-16 修订：REQUIRES_NEW → **REQUIRED**）。
     *
     * <p>修订原因（CI 全量 E2E 实锤，批 15）：结算编排 {@code SettlementSettleOrchestrator
     * .processRecognitionResultInTx} 是外层事务——先建 cabinet_order 行（**未提交**）再调
     * recordSplit。REQUIRES_NEW 的独立事务看不见外层未提交的订单行 ⇒ 分账行 FK
     * （order_revenue_split_order_id_fkey）必然 violation。FK 可见性是硬约束，分账事务必须
     * 能看见同一工作单元里的订单行 ⇒ REQUIRED：有外层 join，无外层自开短事务。</p>
     *
     * <p>锁语义变化（如实）：join 外层时提交点落回外层方法结束，「锁横跨提交」只在自开
     * 事务的路径（调用方无事务）上成立。join 路径的重复插入防护交给唯一键 uq_split_order：
     * 后来者在 INSERT 上等待前者提交——前者提交则 DKE → findSplitAfterRace（REQUIRES_NEW）
     * 能看见已提交行并返回；前者回滚则 INSERT 成功。等待 + DKE 兜底替代了锁横跨提交。</p>
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRED)
    public Optional<OrderRevenueSplit> doRecordSplitInTx(CabinetOrder order) {
        return doRecordSplit(order);
    }

    /**
     * MA-276（2026-11-16 修正）：插入分账行走 **NESTED**（JDBC savepoint）。
     *
     * <p>唯一键冲突只回滚到 savepoint，外层事务仍然可用；如果直接在 REQUIRED 事务里
     * 让唯一键异常冒出去，PostgreSQL 会把整个事务打成 aborted（25P02），调用方后续的
     * 会话保存/入账全部失败。DKE 后由 {@link #findSplitAfterRace} 在独立事务里读回
     * 已提交的分账行。</p>
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NESTED)
    public void insertSplitNested(OrderRevenueSplit split) {
        splitRepository.save(split);
    }

    /**
     * MA-276：DKE catch 分支的重查必须走**新事务**——PG 里同事务内 INSERT 撞唯一键后
     * 整个事务已 aborted，原重查必抛 25P02。锁现在横跨提交，本方法正常情况下不可达，
     * 仅作锁失效/人为并发时的兜底。
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public Optional<OrderRevenueSplit> findSplitAfterRace(String orderId) {
        return splitRepository.findByOrderIdForUpdate(orderId);
    }

    /** 按订单查分账状态码；无分账记录返回 empty。 */
    @Transactional(readOnly = true)
    public Optional<String> findStatusByOrderId(String orderId) {
        if (orderId == null || orderId.isBlank()) {
            return Optional.empty();
        }
        return splitRepository.findByOrderId(orderId.trim())
                .map(OrderRevenueSplit::getStatus)
                .filter(s -> s != null && !s.isBlank());
    }

    /** package-private：同包夹具把 self 代理委托到这里，走与生产一致的真实逻辑。 */
    Optional<OrderRevenueSplit> doRecordSplit(CabinetOrder order) {
        Optional<OrderRevenueSplit> existing = splitRepository.findByOrderIdForUpdate(order.getOrderId());
        if (existing.isPresent()) {
            // R20-2：这条早退支同时是「实收后再进来一次」的自愈入口——分账行可能是未实收时建的
            // （支付分腿 PENDING），DONE 回调或补缴成功后重调本方法，在此补入账。
            // creditIfAbsent 以 splitId 为幂等键，重复调用只可能命中同一流水，不会双记。
            if (OrderCollectionGate.collected(order)) {
                creditWalletIfLedgerOnly(existing.get());
            }
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

        // AU-182（2026-10-19 拍板）：分账基数与佣金同源——走 CommissionBaseCalc 净额
        // （冻结毛额−refunded）。此前用 totalAmountCents：金额退不改写 total ⇒ 部分退后
        // 分账不冲减（原 1000 退 700 仍按 1000 拆分）；按行退又会把冻结值再减一遍 total。
        long gross = CommissionBaseCalc.netCents(order);
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
        split.setSettlementBatchNo(BizIds.nextNumeric());
        if (platform <= 0 || merchant.getWechatReceiverId() == null || merchant.getWechatReceiverId().isBlank()) {
            // 拍板 2026-10-17「平台分账先不收」：rate=0 ⇒ merchantCents==订单毛额，
            // 微信服务商**全额分账是否允许未实证**（CB-032 的 30% 单订单上限仅 ⚪ 旁证）
            // ⇒ 0 抽成直接落仅记账，商户份额整笔入钱包，不发分账请求。
            split.setStatus(LEDGER_ONLY);
        } else if (!profitSharingService.isApiReady()) {
            split.setStatus(ACCRUED);
        } else {
            split.setStatus(ACCRUED);
        }
        try {
            self.insertSplitNested(split);
        } catch (DuplicateKeyException e) {
            log.info("split insert raced order={} splitId={}", order.getOrderId(), split.getSplitId());
            // MA-276：NESTED 插入撞唯一键只回滚到 savepoint，外层事务仍可继续；
            // 重查走 REQUIRES_NEW（读完即提交），避免在同事务里做 25P02 查询。
            return self.findSplitAfterRace(order.getOrderId());
        }
        if (OrderCollectionGate.collected(order)) {
            creditWalletIfLedgerOnly(split);
        } else {
            // 未实收：只建分账行不入钱包，等 DONE 回调/补缴成功重进本方法时由早退支补入账。
            log.warn("revenue split recorded but NOT credited (uncollected order) order={} status={} merchantShare={}",
                    order.getOrderId(), order.getStatus(), merchantShare);
        }
        maybeAutoSubmitAccruedSplit(order, split, merchant, 0);
        log.info("分账记账 order={} merchant={} gross={} platform={} merchantShare={} collected={}",
                order.getOrderId(), merchant.getMerchantId(), gross, platform, merchantShare,
                OrderCollectionGate.collected(order));
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
            // M01：updateById 忽略 null 列，清列须 wrapper 显式 set(null)
            splitRepository.update(null, com.baomidou.mybatisplus.core.toolkit.Wrappers.<OrderRevenueSplit>lambdaUpdate()
                    .eq(OrderRevenueSplit::getSplitId, split.getSplitId())
                    .set(OrderRevenueSplit::getFailureReason, null));
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
        // 审计 T4（2026-10-15）：退款冲正是对账动作而非消费扣款——费用日摊允许余额为负，
        // 旧 debitIfAbsent 的余额闸（412）会让负余额商户的退款冲正永远失败（钱多留、上游事务中断）。
        // 改 AllowNegative：冲正可把余额扣成负（挂账），与费用欠缴同口径；幂等键不变。
        boolean debited = merchantWalletService.debitIfAbsentAllowNegative(
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
        if (merchant == null || !"ACTIVE".equalsIgnoreCase(merchant.getStatus())) {
            // 审计批次4：与首记口径一致——商户缺失/停用不做 0 费率重算（会把平台抽成静默
            // 清零、商户多分），保留原拆分并留痕待人工。
            log.warn("skip split resync (partial refund): merchant missing or inactive {}", split.getMerchantId());
            return;
        }
        int rateBps = merchant.getPlatformRateBps();
        // AU-182（2026-10-19 拍板）：基数与佣金同源走 CommissionBaseCalc 净额（冻结毛额−refunded），
        // 金额退/按行退按 refunded 冲减。🔴 冻结毛额由 V384 在首次扣款时点固定，退款/按行退/
        // 争议改单一律不得改写 ⇒ 争议 ADJUST 加价（ADJUST_CHARGE）不进基数，属已知形态
        // （DataConsistencyService#checkPaidGrossConsistency 登记为「佣金基数偏小」）。
        long gross = CommissionBaseCalc.netCents(order);
        long platform = gross * rateBps / 10_000L;
        long merchantShare = gross - platform;
        long returnCents = Math.max(0, oldMerchantCents - merchantShare);
        if (returnCents > 0 && merchant != null) {
            // 微信分账回退 out_return_no：协议幂等键，勿改成纯数字业务单号（lesson #223）
            String outReturnNo = "PSR" + split.getSplitId() + ":g" + merchantShare;
            // AU-141：同全额路径——finalize 事务内不提交 HTTP，落 PENDING + 补偿任务异步提交
            applyWeChatReturnOutcome(split, outReturnNo, returnCents,
                    ReturnSubmitOutcome.PROCESSING, "部分退款分账回退 " + order.getOrderId());
            // R20-9：按腿排补偿任务，多轮部分退各占一个调度槽（旧实现按 splitId 去重会吞掉第二轮）。
            returnCompensationService.scheduleReturnRetry(
                    split, returnLegService.openLegs(split.getSplitId()), 30);
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
        // M01：updateById 忽略 null 列，清列须 wrapper 显式 set(null)
        splitRepository.update(null, com.baomidou.mybatisplus.core.toolkit.Wrappers.<OrderRevenueSplit>lambdaUpdate()
                .eq(OrderRevenueSplit::getSplitId, split.getSplitId())
                .set(OrderRevenueSplit::getFailureReason, null));
        log.warn("split adjusted after partial refund (submitted wechat) order={} splitId={} oldMerchant={} newMerchant={} returnCents={} pendingReturn={}",
                order.getOrderId(), split.getSplitId(), oldMerchantCents, merchantShare, returnCents,
                split.getWechatPendingReturnNo());
    }

    /** 争议改单增额：已提交微信分账仅本地重算并标记需人工补分账。 */
    private void resyncSubmittedSplitAfterIncrease(CabinetOrder order, OrderRevenueSplit split) {
        long oldMerchantCents = Math.max(0, split.getMerchantCents());
        Merchant merchant = merchantRepository.findById(split.getMerchantId()).orElse(null);
        if (merchant == null || !"ACTIVE".equalsIgnoreCase(merchant.getStatus())) {
            // 审计批次4：同上——缺失/停用不重算，保留原拆分。
            log.warn("skip split resync (increase): merchant missing or inactive {}", split.getMerchantId());
            return;
        }
        int rateBps = merchant.getPlatformRateBps();
        // AU-182（2026-10-19 拍板）：基数与佣金同源走 CommissionBaseCalc 净额（冻结毛额−refunded），
        // 金额退/按行退按 refunded 冲减。🔴 冻结毛额由 V384 在首次扣款时点固定，退款/按行退/
        // 争议改单一律不得改写 ⇒ 争议 ADJUST 加价（ADJUST_CHARGE）不进基数，属已知形态
        // （DataConsistencyService#checkPaidGrossConsistency 登记为「佣金基数偏小」）。
        long gross = CommissionBaseCalc.netCents(order);
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
        // AU-141（批次 8）：退款 finalize 事务内不再同步提交微信回退 HTTP——直接落一条待确认腿
        // + 按腿排补偿任务（≤30s 异步提交，查单确认 / 重提均在补偿调度器的独立短事务）。
        // 本地商户钱包 clawback 已先行，渠道回退滞后无资损。
        applyWeChatReturnOutcome(split, outReturnNo, amount,
                ReturnSubmitOutcome.PROCESSING, "全额退款分账回退 " + split.getOrderId());
        returnCompensationService.scheduleReturnRetry(
                split, returnLegService.openLegs(split.getSplitId()), 30);
        if (split.getFailureReason() != null) {
            log.warn("wechat profit sharing full return failed splitId={} order={}", split.getSplitId(), split.getOrderId());
        }
    }

    /**
     * R20-9 / CB-085：写入改走「腿表」，不再覆盖 split 上的单值列。
     *
     * <p>改前这里三处 {@code setWechatPendingReturnXxx}，多轮部分退时后一轮把前一轮
     * 整笔抹掉 ⇒ 渠道只回退最后一笔。现在 {@link ProfitSharingReturnLegService#recordOutcome}
     * 按 out_return_no 幂等 upsert 一条腿，并把 split 两列重算成「最老未完结腿」的投影；
     * 补偿任务也按腿排，不再以 splitId 去重。
     */
    private void applyWeChatReturnOutcome(OrderRevenueSplit split,
                                          String outReturnNo,
                                          long returnCents,
                                          ReturnSubmitOutcome outcome,
                                          String failureMessage) {
        if (split == null || returnCents <= 0 || outcome == null) {
            return;
        }
        returnLegService.recordOutcome(split, outReturnNo, returnCents, outcome, failureMessage);
        switch (outcome) {
            case SUCCESS -> split.setFailureReason(null);
            case PROCESSING -> split.setFailureReason(null);
            case FAILED -> {
                split.setFailureReason(failureMessage);
                profitSharingAlertService.sendReturnSubmitFailed(split, outReturnNo, returnCents, failureMessage);
                returnCompensationService.scheduleReturnRetry(
                        split, returnLegService.openLegs(split.getSplitId()), 60);
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
        // R20-2：这条是留在手动通道上的"把手"——自动入账已被实收门拦住，但运营点确认完结
        // 仍会把份额打进商户钱包。未实收（支付分腿 PENDING / 待支付单已取消）一律拒绝，
        // 让操作者看见原因，而不是静默放款之后再靠对账发现。
        // 订单行查不到同样拒绝：这条通道的失败方向必须是"少放款"，不能是"凭空造钱"。
        // 用 ResponseStatusException(CONFLICT) 而不是 IllegalStateException：
        // runWithOrderSplitLock 会把其它 Exception 统一包成 500，只有 ResponseStatusException
        // 原样透出——这里要让运营在界面上看到 409 + 具体原因。
        CabinetOrder order = orderRepository.findById(orderId).orElse(null);
        if (order == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "订单 " + orderId + " 查不到，拒绝确认完结（宁可少入账也不凭空放款）");
        }
        if (!OrderCollectionGate.collected(order)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "订单 " + orderId
                    + " 尚未实收（status=" + order.getStatus() + "），不能确认完结；待渠道到账回调或用户补缴后再操作");
        }
        creditWalletIfLedgerOnly(split);
        split.setStatus(SETTLED);
        split.setSettledAt(java.time.Instant.now());
        split.setFailureReason(null);
        splitRepository.save(split);
        // M01：updateById 忽略 null 列，清列须 wrapper 显式 set(null)
        splitRepository.update(null, com.baomidou.mybatisplus.core.toolkit.Wrappers.<OrderRevenueSplit>lambdaUpdate()
                .eq(OrderRevenueSplit::getSplitId, split.getSplitId())
                .set(OrderRevenueSplit::getFailureReason, null));
        return split;
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
        // AU-181：改 AllowNegative 旁路（与 T4 的 clawbackWalletDelta 同构）——
        // 余额提空/为负商户的退款 finalize 不得被余额闸卡死；refId 不变保幂等
        boolean reversed = merchantWalletService.reverseCreditAllowNegativeIfPresent(
                split.getMerchantId(),
                amount,
                "SPLIT_REVERSE",
                new MerchantWalletService.ReverseCreditCommand(
                        "SPLIT_REV", split.getSplitId(), "SPLIT", split.getSplitId(),
                        "分账冲正 " + split.getOrderId()));
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
        if (merchant == null || !"ACTIVE".equalsIgnoreCase(merchant.getStatus())) {
            // 审计批次4：同上——缺失/停用不按 0 费率重算（钱包 clawback/credit 均会被扭曲），
            // 保留原拆分并留痕待人工。
            log.warn("skip split resync (local): merchant missing or inactive {}", split.getMerchantId());
            return;
        }
        int rateBps = merchant.getPlatformRateBps();
        // AU-182（2026-10-19 拍板）：基数与佣金同源走 CommissionBaseCalc 净额（冻结毛额−refunded），
        // 金额退/按行退按 refunded 冲减。🔴 冻结毛额由 V384 在首次扣款时点固定，退款/按行退/
        // 争议改单一律不得改写 ⇒ 争议 ADJUST 加价（ADJUST_CHARGE）不进基数，属已知形态
        // （DataConsistencyService#checkPaidGrossConsistency 登记为「佣金基数偏小」）。
        long gross = CommissionBaseCalc.netCents(order);
        long platform = gross * rateBps / 10_000L;
        long merchantShare = gross - platform;
        // 拍板 2026-10-17「平台分账先不收」：重算后平台份额为 0 ⇒ 这单不再走微信分账
        // （receiver.amount 会等于订单毛额，全额分账是否被服务商允许**未实证**）。
        // ACCRUED 行既没提交微信也没入过钱包，所以翻成 LEDGER_ONLY 后按**全额**入账，
        // 不能走下面的差额补入（差额口径只认 new−old，会把 oldMerchantCents 少付给商户）。
        boolean convertedToLedgerOnly = platform <= 0 && ACCRUED.equals(status);
        if (!convertedToLedgerOnly) {
            clawbackWalletDelta(split, oldMerchantCents, merchantShare);
            creditWalletDelta(split, oldMerchantCents, merchantShare);
        }
        split.setGrossCents(gross);
        split.setPlatformCents(platform);
        split.setMerchantCents(merchantShare);
        if (gross <= 0) {
            split.setStatus(VOIDED);
        } else if (convertedToLedgerOnly) {
            split.setStatus(LEDGER_ONLY);
        }
        splitRepository.save(split);
        if (convertedToLedgerOnly && gross > 0) {
            if (OrderCollectionGate.collected(order)) {
                creditWalletIfLedgerOnly(split);
            } else {
                log.warn("repriced split converted to LEDGER_ONLY but NOT credited (uncollected order) "
                        + "order={} status={}", order.getOrderId(), order.getStatus());
            }
        }
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
        // 审计 B2（2026-10-16）：分账 HTTP 不得在本事务（争议改单）内执行——
        // 事务提交后再提交分账；失败不回滚改单，submitSplit 自身会落 WECHAT_FAILED 供重试。
        final OrderRevenueSplit splitRef = split;
        final Merchant merchantRef = merchant;
        final String wxTxnRef = wxTxn;
        final String orderIdRef = order.getOrderId();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    profitSharingService.submitSplit(splitRef, merchantRef, wxTxnRef);
                    log.info("auto submit profit sharing after order increase order={} splitId={} wxTxn={}",
                            orderIdRef, splitRef.getSplitId(), wxTxnRef);
                }
            });
        } else {
            profitSharingService.submitSplit(splitRef, merchantRef, wxTxnRef);
            log.info("auto submit profit sharing after order increase order={} splitId={} wxTxn={}",
                    orderIdRef, splitRef.getSplitId(), wxTxnRef);
        }
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
        T get();
    }

    @FunctionalInterface
    private interface LockedSplitAction {
        void run();
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
        } catch (IllegalStateException e) {
            // MA-289：分账状态机的业务冲突（分账不存在 / 记录已变更 / 仅 LEDGER_ONLY 可确认完结）
            // 抛的是 IllegalStateException，旧封装一律包成 500 ⇒ 运营看到「服务器错误」而不是
            // 「请稍后重试/状态冲突」。这类是可预期的状态竞争，语义上是 409。
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage(), e);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage(), e);
        } finally {
            distributedLockService.unlock(orderSplitLockKey(orderId));
        }
    }
}
