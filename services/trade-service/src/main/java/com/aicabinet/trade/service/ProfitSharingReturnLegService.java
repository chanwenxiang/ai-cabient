package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.OrderRevenueSplit;
import com.aicabinet.trade.domain.OrderSplitReturnLeg;
import com.aicabinet.trade.mapper.OrderRevenueSplitMapper;
import com.aicabinet.trade.mapper.OrderSplitReturnLegMapper;
import com.aicabinet.trade.payment.WeChatProfitSharingService.ReturnSubmitOutcome;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 分账回退「腿」的唯一写入口（R20-9 / CB-085 / V463）。
 *
 * <p>责任边界：① 按 {@code out_return_no} 幂等 upsert 一条腿；② 把 split 上的
 * {@code wechat_pending_return_no / _cents} 重算成「最老一条未完结腿」的派生投影。
 * 除了这里，任何地方都不许写这两列，也不许再拿它当渠道回退金额的真值。
 */
@Service
public class ProfitSharingReturnLegService {

    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_PROCESSING = "PROCESSING";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_PENDING = "PENDING";

    /** 补偿任务 txId 前缀；键是腿不是 split，多腿各有独立调度槽。 */
    public static final String LEG_TX_PREFIX = "LEG:";

    private static final Logger log = LoggerFactory.getLogger(ProfitSharingReturnLegService.class);

    private final OrderSplitReturnLegMapper legRepository;
    private final OrderRevenueSplitMapper splitRepository;

    public ProfitSharingReturnLegService(OrderSplitReturnLegMapper legRepository,
                                         OrderRevenueSplitMapper splitRepository) {
        this.legRepository = legRepository;
        this.splitRepository = splitRepository;
    }

    public static boolean isOpen(String status) {
        return OrderSplitReturnLegMapper.OPEN_STATUSES.contains(status);
    }

    public static String legTxId(long legId) {
        return LEG_TX_PREFIX + legId;
    }

    public List<OrderSplitReturnLeg> openLegs(String splitId) {
        return legRepository.findOpenBySplitId(splitId);
    }

    public List<OrderSplitReturnLeg> openLegs(int limit) {
        return legRepository.findOpenLegs(limit);
    }

    public long sumSuccessCents(String splitId) {
        return legRepository.sumSuccessCents(splitId);
    }

    public long sumOpenCents(String splitId) {
        return legRepository.sumOpenCents(splitId);
    }

    /**
     * 落一条回退结果。{@code out_return_no} 相同 ⇒ 同一条腿（渠道幂等键），
     * 走条件更新而不是再插一行；不同单号 ⇒ 新腿，多轮部分退因此各自留痕。
     *
     * @return 落库后的腿（含 legId，供按腿排补偿任务）
     */
    @Transactional
    public OrderSplitReturnLeg recordOutcome(OrderRevenueSplit split,
                                             String outReturnNo,
                                             long cents,
                                             ReturnSubmitOutcome outcome,
                                             String failureMessage) {
        if (split == null || split.getSplitId() == null || split.getSplitId().isBlank()
                || outReturnNo == null || outReturnNo.isBlank() || cents <= 0 || outcome == null) {
            return null;
        }
        String status = statusFor(outcome);
        String reason = outcome == ReturnSubmitOutcome.FAILED ? failureMessage : null;

        OrderSplitReturnLeg draft = new OrderSplitReturnLeg();
        draft.setSplitId(split.getSplitId());
        draft.setOutReturnNo(outReturnNo.trim());
        draft.setCents(cents);
        draft.setStatus(status);
        draft.setFailureReason(reason);
        legRepository.insertLegIfAbsent(draft);
        OrderSplitReturnLeg stored = legRepository.findByOutReturnNo(outReturnNo.trim());
        if (stored == null) {
            // 理论上不可达：刚 INSERT ON CONFLICT DO NOTHING，同一事务内必然能读回。
            throw new IllegalStateException("分账回退腿写入后读不到 outReturnNo=" + outReturnNo);
        }
        if (stored.getCents() != cents) {
            // 🔴 资金路径 fail-closed：同一渠道单号却报了不同金额，说明单号生成规则撞了。
            // 若继续按新金额覆盖，渠道侧会把两笔不同的回退当成同一笔 ⇒ 双退或少退。
            // 宁可让本次退款事务回滚转人工，也不把两笔金额压进一条腿。
            throw new IllegalStateException("分账回退回单号金额冲突 outReturnNo=" + outReturnNo
                    + " 腿金额=" + stored.getCents() + " 本次=" + cents + " splitId=" + split.getSplitId());
        }
        if (!STATUS_SUCCESS.equals(stored.getStatus())) {
            int updated = legRepository.updateOutcomeIfNotSuccess(stored.getLegId(), status, reason);
            if (updated == 0) {
                log.warn("profit sharing return leg already terminal legId={} outReturnNo={}",
                        stored.getLegId(), stored.getOutReturnNo());
            } else {
                stored.setStatus(status);
                stored.setFailureReason(reason);
            }
        }
        refreshProjection(split);
        return stored;
    }

    /** 结果 → 腿状态：SUCCESS 是唯一终态；PROCESSING/FAILED 都仍需继续查单或重提。 */
    static String statusFor(ReturnSubmitOutcome outcome) {
        return switch (outcome) {
            case SUCCESS -> STATUS_SUCCESS;
            case PROCESSING -> STATUS_PROCESSING;
            case FAILED -> STATUS_FAILED;
        };
    }

    /**
     * 投影 = 最老一条未完结腿。没有开放腿就必须把两列显式清成 NULL
     * （M01：MP updateById 忽略 null 列，清列只能走 wrapper.set(col, null)）。
     */
    private void refreshProjection(OrderRevenueSplit split) {
        List<OrderSplitReturnLeg> open = legRepository.findOpenBySplitId(split.getSplitId());
        OrderSplitReturnLeg oldest = open.isEmpty() ? null : open.get(0);
        String no = oldest == null ? null : oldest.getOutReturnNo();
        Long cents = oldest == null ? null : oldest.getCents();
        splitRepository.update(null, Wrappers.<OrderRevenueSplit>lambdaUpdate()
                .eq(OrderRevenueSplit::getSplitId, split.getSplitId())
                .set(OrderRevenueSplit::getWechatPendingReturnNo, no)
                .set(OrderRevenueSplit::getWechatPendingReturnCents, cents));
        split.setWechatPendingReturnNo(no);
        split.setWechatPendingReturnCents(cents);
    }
}
