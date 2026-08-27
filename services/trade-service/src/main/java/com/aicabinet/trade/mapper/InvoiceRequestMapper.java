package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.InvoiceRequest;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface InvoiceRequestMapper extends BaseTradeMapper<InvoiceRequest> {

    InvoiceRequest findByIdForUpdateRaw(@Param("invoiceId") Long invoiceId);

    default Optional<InvoiceRequest> findByIdForUpdate(Long invoiceId) {
        return Optional.ofNullable(findByIdForUpdateRaw(invoiceId));
    }

    InvoiceRequest findActiveByOrderIdForUpdateRaw(@Param("orderId") String orderId);

    default Optional<InvoiceRequest> findActiveByOrderIdForUpdate(String orderId) {
        return Optional.ofNullable(findActiveByOrderIdForUpdateRaw(orderId));
    }

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

    /** page 为 0-based。 */
    default Page<InvoiceRequest> search(String status, int page, int size) {
        var q = Wrappers.<InvoiceRequest>lambdaQuery().orderByDesc(InvoiceRequest::getCreatedAt);
        if (status != null && !status.isBlank()) {
            q.eq(InvoiceRequest::getStatus, status.trim().toUpperCase());
        }
        return selectPage(new Page<>(page + 1L, size), q);
    }
}
