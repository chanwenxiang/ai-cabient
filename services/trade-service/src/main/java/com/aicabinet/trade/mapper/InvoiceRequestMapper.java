package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.InvoiceRequest;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface InvoiceRequestMapper extends BaseTradeMapper<InvoiceRequest> {

    default List<InvoiceRequest> findByUserIdOrderByCreatedAtDesc(Long userId) {
        return selectList(Wrappers.<InvoiceRequest>lambdaQuery()
                .eq(InvoiceRequest::getUserId, userId)
                .orderByDesc(InvoiceRequest::getCreatedAt));
    }

    default Optional<InvoiceRequest> findActiveByOrderId(String orderId) {
        return Optional.ofNullable(selectOne(Wrappers.<InvoiceRequest>lambdaQuery()
                .eq(InvoiceRequest::getOrderId, orderId)
                .in(InvoiceRequest::getStatus, "PENDING", "ISSUED")
                .last("LIMIT 1")));
    }

    default List<InvoiceRequest> findByStatusOrderByCreatedAtDesc(String status, int limit) {
        var q = Wrappers.<InvoiceRequest>lambdaQuery().orderByDesc(InvoiceRequest::getCreatedAt);
        if (status != null && !status.isBlank()) {
            q.eq(InvoiceRequest::getStatus, status.trim().toUpperCase());
        }
        return selectList(q.last("LIMIT " + Math.max(1, Math.min(limit, 200))));
    }
}
