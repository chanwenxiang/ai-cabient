package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.PayScoreOrder;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface PayScoreOrderMapper extends BaseTradeMapper<PayScoreOrder> {

    default List<PayScoreOrder> findByOrderId(String orderId) {
        if (orderId == null || orderId.isBlank()) {
            return List.of();
        }
        return selectList(Wrappers.<PayScoreOrder>lambdaQuery()
                .eq(PayScoreOrder::getOrderId, orderId.trim())
                .orderByAsc(PayScoreOrder::getCreatedAt));
    }
}
