package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.PayScoreOrder;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface PayScoreOrderMapper extends BaseTradeMapper<PayScoreOrder> {

    /**
     * 开门期建单后一直没关联柜机订单的支付分单（超过阈值即取消链断了）。
     * 只告警不自动改单：微信侧可能仍在扣款中，程序取消会越过渠道状态机。
     */
    default List<PayScoreOrder> findUnlinkedBefore(java.time.Instant cutoff, int limit) {
        return selectList(Wrappers.<PayScoreOrder>lambdaQuery()
                .isNull(PayScoreOrder::getOrderId)
                .lt(PayScoreOrder::getCreatedAt, cutoff)
                .orderByAsc(PayScoreOrder::getCreatedAt)
                .last("LIMIT " + Math.max(1, Math.min(limit, 100))));
    }

    /** 开门期支付分单的 out_order_no = sessionId（V496 后可能还没有 orderId）。 */
    default List<PayScoreOrder> findByOutOrderNo(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return List.of();
        }
        return selectList(Wrappers.<PayScoreOrder>lambdaQuery()
                .eq(PayScoreOrder::getOutOrderNo, sessionId.trim())
                .orderByAsc(PayScoreOrder::getCreatedAt));
    }

    default List<PayScoreOrder> findByOrderId(String orderId) {
        if (orderId == null || orderId.isBlank()) {
            return List.of();
        }
        return selectList(Wrappers.<PayScoreOrder>lambdaQuery()
                .eq(PayScoreOrder::getOrderId, orderId.trim())
                .orderByAsc(PayScoreOrder::getCreatedAt));
    }
}
