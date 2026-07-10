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

    long countByMerchantIdInAndStatusIn(Collection<String> merchantIds, Collection<String> statuses);

    @org.springframework.data.jpa.repository.Query(
            "SELECT COALESCE(SUM(s.merchantCents), 0) FROM OrderRevenueSplit s "
                    + "WHERE s.merchantId IN :merchantIds AND s.createdAt >= :since")
    long sumMerchantCentsByMerchantIdInSince(
            @org.springframework.data.repository.query.Param("merchantIds") Collection<String> merchantIds,
            @org.springframework.data.repository.query.Param("since") java.time.Instant since);

    @org.springframework.data.jpa.repository.Query(
            "SELECT COALESCE(SUM(s.merchantCents), 0) FROM OrderRevenueSplit s "
                    + "WHERE s.merchantId IN :merchantIds")
    long sumMerchantCentsByMerchantIdIn(
            @org.springframework.data.repository.query.Param("merchantIds") Collection<String> merchantIds);

    List<OrderRevenueSplit> findByMerchantIdInAndCreatedAtAfter(
            Collection<String> merchantIds, java.time.Instant since);

    @org.springframework.data.jpa.repository.Query("""
            SELECT s FROM OrderRevenueSplit s
            WHERE s.merchantId IN :merchantIds
              AND s.createdAt >= :from
              AND s.createdAt < :to
              AND (:status = '' OR s.status = :status)
            ORDER BY s.createdAt DESC
            """)
    Page<OrderRevenueSplit> searchByMerchants(
            @org.springframework.data.repository.query.Param("merchantIds") Collection<String> merchantIds,
            @org.springframework.data.repository.query.Param("status") String status,
            @org.springframework.data.repository.query.Param("from") java.time.Instant from,
            @org.springframework.data.repository.query.Param("to") java.time.Instant to,
            Pageable pageable);

    @org.springframework.data.jpa.repository.Query(
            "SELECT COALESCE(SUM(s.merchantCents), 0) FROM OrderRevenueSplit s "
                    + "WHERE s.merchantId IN :merchantIds AND s.status IN :statuses")
    long sumMerchantCentsByMerchantIdInAndStatusIn(
            @org.springframework.data.repository.query.Param("merchantIds") Collection<String> merchantIds,
            @org.springframework.data.repository.query.Param("statuses") Collection<String> statuses);

    @org.springframework.data.jpa.repository.Query(
            "SELECT COALESCE(SUM(s.merchantCents), 0) FROM OrderRevenueSplit s "
                    + "WHERE s.merchantId IN :merchantIds AND s.status = 'SUCCESS' AND s.createdAt >= :since")
    long sumSuccessMerchantCentsByMerchantIdInSince(
            @org.springframework.data.repository.query.Param("merchantIds") Collection<String> merchantIds,
            @org.springframework.data.repository.query.Param("since") java.time.Instant since);

    List<OrderRevenueSplit> findTop5ByMerchantIdInAndStatusInOrderByCreatedAtDesc(
            Collection<String> merchantIds, Collection<String> statuses);

    List<OrderRevenueSplit> findByMerchantIdInAndSettlementBatchNoOrderByCreatedAtDesc(
            Collection<String> merchantIds, String settlementBatchNo);

    @org.springframework.data.jpa.repository.Query(value = """
            SELECT DATE(timezone('Asia/Shanghai', s.created_at)) AS day,
                   CAST(COUNT(*) AS bigint),
                   CAST(COALESCE(SUM(s.gross_cents), 0) AS bigint),
                   CAST(COALESCE(SUM(s.platform_cents), 0) AS bigint),
                   CAST(COALESCE(SUM(s.merchant_cents), 0) AS bigint),
                   CAST(COALESCE(SUM(CASE WHEN s.status = 'SUCCESS' THEN s.merchant_cents ELSE 0 END), 0) AS bigint),
                   CAST(COALESCE(SUM(CASE WHEN s.status NOT IN ('SUCCESS') THEN s.merchant_cents ELSE 0 END), 0) AS bigint),
                   CAST(COALESCE(SUM(CASE WHEN s.status IN ('WECHAT_FAILED', 'FAILED') THEN 1 ELSE 0 END), 0) AS bigint)
            FROM order_revenue_split s
            WHERE s.merchant_id IN (:merchantIds)
              AND s.created_at >= :from
              AND s.created_at < :to
            GROUP BY day
            ORDER BY day DESC
            """, nativeQuery = true)
    List<Object[]> aggregateDailyByMerchants(
            @org.springframework.data.repository.query.Param("merchantIds") Collection<String> merchantIds,
            @org.springframework.data.repository.query.Param("from") java.time.Instant from,
            @org.springframework.data.repository.query.Param("to") java.time.Instant to);

    @org.springframework.data.jpa.repository.Query(value = """
            SELECT s.settlement_batch_no,
                   s.merchant_id,
                   MIN(s.settle_after),
                   MAX(s.settled_at),
                   CAST(COUNT(*) AS bigint),
                   CAST(COALESCE(SUM(s.gross_cents), 0) AS bigint),
                   CAST(COALESCE(SUM(s.platform_cents), 0) AS bigint),
                   CAST(COALESCE(SUM(s.merchant_cents), 0) AS bigint),
                   CAST(COALESCE(SUM(CASE WHEN s.status = 'SUCCESS' THEN s.merchant_cents ELSE 0 END), 0) AS bigint),
                   CAST(COALESCE(SUM(CASE WHEN s.status NOT IN ('SUCCESS') THEN s.merchant_cents ELSE 0 END), 0) AS bigint),
                   CAST(COALESCE(SUM(CASE WHEN s.status IN ('WECHAT_FAILED', 'FAILED') THEN 1 ELSE 0 END), 0) AS bigint)
            FROM order_revenue_split s
            WHERE s.merchant_id IN (:merchantIds)
              AND s.settlement_batch_no IS NOT NULL
              AND s.created_at >= :from
              AND s.created_at < :to
            GROUP BY s.settlement_batch_no, s.merchant_id
            ORDER BY MIN(s.created_at) DESC
            """, nativeQuery = true)
    List<Object[]> aggregateBatchByMerchants(
            @org.springframework.data.repository.query.Param("merchantIds") Collection<String> merchantIds,
            @org.springframework.data.repository.query.Param("from") java.time.Instant from,
            @org.springframework.data.repository.query.Param("to") java.time.Instant to);
}
