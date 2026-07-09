package com.aicabinet.trade.repository;

import com.aicabinet.trade.domain.OrderRevenueSplit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface OrderRevenueSplitRepository extends JpaRepository<OrderRevenueSplit, String> {

    Optional<OrderRevenueSplit> findByOrderId(String orderId);

    long countByStatusIn(Collection<String> statuses);

    Page<OrderRevenueSplit> findByMerchantIdOrderByCreatedAtDesc(String merchantId, Pageable pageable);

    Page<OrderRevenueSplit> findAllByOrderByCreatedAtDesc(Pageable pageable);

    Page<OrderRevenueSplit> findByMerchantIdInOrderByCreatedAtDesc(
            Collection<String> merchantIds, Pageable pageable);

    Page<OrderRevenueSplit> findByStatusInOrderByCreatedAtDesc(
            Collection<String> statuses, Pageable pageable);

    Page<OrderRevenueSplit> findByMerchantIdAndStatusInOrderByCreatedAtDesc(
            String merchantId, Collection<String> statuses, Pageable pageable);

    Page<OrderRevenueSplit> findByMerchantIdInAndStatusInOrderByCreatedAtDesc(
            Collection<String> merchantIds, Collection<String> statuses, Pageable pageable);

    List<OrderRevenueSplit> findTop20ByStatusOrderByCreatedAtAsc(String status);
}
