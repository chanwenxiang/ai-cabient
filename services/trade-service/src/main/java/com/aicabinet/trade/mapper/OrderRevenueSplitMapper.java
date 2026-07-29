package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.OrderRevenueSplit;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

@Mapper
public interface OrderRevenueSplitMapper extends BaseTradeMapper<OrderRevenueSplit> {

    default Optional<OrderRevenueSplit> findByOrderId(String orderId) {
    return Optional.ofNullable(selectOne(Wrappers.<OrderRevenueSplit>lambdaQuery().eq(OrderRevenueSplit::getOrderId, orderId)));
    }

    default long countByStatusIn(Collection<String> statuses) {
    Long c = selectCount(Wrappers.<OrderRevenueSplit>lambdaQuery().in(OrderRevenueSplit::getStatus, statuses));
    return c == null ? 0 : c;
    }

    default Page<OrderRevenueSplit> findByMerchantIdOrderByCreatedAtDesc(String merchantId, Pageable pageable) {
    var mpPage = new com.baomidou.mybatisplus.extension.plugins.pagination.Page<OrderRevenueSplit>(
            pageable.getPageNumber() + 1L, pageable.getPageSize());
    var result = selectPage(mpPage, Wrappers.<OrderRevenueSplit>lambdaQuery().eq(OrderRevenueSplit::getMerchantId, merchantId).orderByDesc(OrderRevenueSplit::getCreatedAt));
    return new org.springframework.data.domain.PageImpl<>(result.getRecords(), pageable, result.getTotal());
    }

    default Page<OrderRevenueSplit> findAllByOrderByCreatedAtDesc(Pageable pageable) {
    var mpPage = new com.baomidou.mybatisplus.extension.plugins.pagination.Page<OrderRevenueSplit>(
            pageable.getPageNumber() + 1L, pageable.getPageSize());
    var result = selectPage(mpPage, Wrappers.<OrderRevenueSplit>lambdaQuery().orderByDesc(OrderRevenueSplit::getCreatedAt));
    return new org.springframework.data.domain.PageImpl<>(result.getRecords(), pageable, result.getTotal());
    }

    default Page<OrderRevenueSplit> findByMerchantIdInOrderByCreatedAtDesc( Collection<String> merchantIds, Pageable pageable) {
    var mpPage = new com.baomidou.mybatisplus.extension.plugins.pagination.Page<OrderRevenueSplit>(
            pageable.getPageNumber() + 1L, pageable.getPageSize());
    var result = selectPage(mpPage, Wrappers.<OrderRevenueSplit>lambdaQuery().in(OrderRevenueSplit::getMerchantId, merchantIds).orderByDesc(OrderRevenueSplit::getCreatedAt));
    return new org.springframework.data.domain.PageImpl<>(result.getRecords(), pageable, result.getTotal());
    }

    default Page<OrderRevenueSplit> findByStatusInOrderByCreatedAtDesc( Collection<String> statuses, Pageable pageable) {
    var mpPage = new com.baomidou.mybatisplus.extension.plugins.pagination.Page<OrderRevenueSplit>(
            pageable.getPageNumber() + 1L, pageable.getPageSize());
    var result = selectPage(mpPage, Wrappers.<OrderRevenueSplit>lambdaQuery().in(OrderRevenueSplit::getStatus, statuses).orderByDesc(OrderRevenueSplit::getCreatedAt));
    return new org.springframework.data.domain.PageImpl<>(result.getRecords(), pageable, result.getTotal());
    }

    default Page<OrderRevenueSplit> findByMerchantIdAndStatusInOrderByCreatedAtDesc( String merchantId, Collection<String> statuses, Pageable pageable) {
    var mpPage = new com.baomidou.mybatisplus.extension.plugins.pagination.Page<OrderRevenueSplit>(
            pageable.getPageNumber() + 1L, pageable.getPageSize());
    var result = selectPage(mpPage, Wrappers.<OrderRevenueSplit>lambdaQuery().eq(OrderRevenueSplit::getMerchantId, merchantId).in(OrderRevenueSplit::getStatus, statuses).orderByDesc(OrderRevenueSplit::getCreatedAt));
    return new org.springframework.data.domain.PageImpl<>(result.getRecords(), pageable, result.getTotal());
    }

    default Page<OrderRevenueSplit> findByMerchantIdInAndStatusInOrderByCreatedAtDesc( Collection<String> merchantIds, Collection<String> statuses, Pageable pageable) {
    var mpPage = new com.baomidou.mybatisplus.extension.plugins.pagination.Page<OrderRevenueSplit>(
            pageable.getPageNumber() + 1L, pageable.getPageSize());
    var result = selectPage(mpPage, Wrappers.<OrderRevenueSplit>lambdaQuery().in(OrderRevenueSplit::getMerchantId, merchantIds).in(OrderRevenueSplit::getStatus, statuses).orderByDesc(OrderRevenueSplit::getCreatedAt));
    return new org.springframework.data.domain.PageImpl<>(result.getRecords(), pageable, result.getTotal());
    }

    default List<OrderRevenueSplit> findTop20ByStatusOrderByCreatedAtAsc(String status) {
    return selectList(Wrappers.<OrderRevenueSplit>lambdaQuery().eq(OrderRevenueSplit::getStatus, status).orderByAsc(OrderRevenueSplit::getCreatedAt).last("LIMIT 20"));
    }

    default long countByMerchantIdInAndStatusIn(Collection<String> merchantIds, Collection<String> statuses) {
    Long c = selectCount(Wrappers.<OrderRevenueSplit>lambdaQuery().in(OrderRevenueSplit::getMerchantId, merchantIds).in(OrderRevenueSplit::getStatus, statuses));
    return c == null ? 0 : c;
    }

        long sumMerchantCentsByMerchantIdInSince( @org.springframework.data.repository.query.Param("merchantIds") Collection<String> merchantIds, @org.springframework.data.repository.query.Param("since") java.time.Instant since);


        long sumMerchantCentsByMerchantIdIn( @org.springframework.data.repository.query.Param("merchantIds") Collection<String> merchantIds);


    default List<OrderRevenueSplit> findByMerchantIdInAndCreatedAtAfter( Collection<String> merchantIds, java.time.Instant since) {
    return selectList(Wrappers.<OrderRevenueSplit>lambdaQuery().in(OrderRevenueSplit::getMerchantId, merchantIds).gt(OrderRevenueSplit::getCreatedAt, since));
    }

        List<OrderRevenueSplit> _searchByMerchantsAll(@org.springframework.data.repository.query.Param("merchantIds") Collection<String> merchantIds, @org.springframework.data.repository.query.Param("status") String status, @org.springframework.data.repository.query.Param("from") java.time.Instant from, @org.springframework.data.repository.query.Param("to") java.time.Instant to);


    default Page<OrderRevenueSplit> searchByMerchants( @org.springframework.data.repository.query.Param("merchantIds") Collection<String> merchantIds, @org.springframework.data.repository.query.Param("status") String status, @org.springframework.data.repository.query.Param("from") java.time.Instant from, @org.springframework.data.repository.query.Param("to") java.time.Instant to, Pageable pageable) {
    var all = _searchByMerchantsAll(merchantIds, status, from, to);
    int start = (int) pageable.getOffset();
    int end = Math.min(start + pageable.getPageSize(), all.size());
    var slice = start >= all.size() ? java.util.List.<OrderRevenueSplit>of() : all.subList(start, end);
    return new org.springframework.data.domain.PageImpl<>(slice, pageable, all.size());
    }

        long sumMerchantCentsByMerchantIdInAndStatusIn( @org.springframework.data.repository.query.Param("merchantIds") Collection<String> merchantIds, @org.springframework.data.repository.query.Param("statuses") Collection<String> statuses);


        long sumSuccessMerchantCentsByMerchantIdInSince( @org.springframework.data.repository.query.Param("merchantIds") Collection<String> merchantIds, @org.springframework.data.repository.query.Param("since") java.time.Instant since);


    default List<OrderRevenueSplit> findTop5ByMerchantIdInAndStatusInOrderByCreatedAtDesc( Collection<String> merchantIds, Collection<String> statuses) {
    return selectList(Wrappers.<OrderRevenueSplit>lambdaQuery().in(OrderRevenueSplit::getMerchantId, merchantIds).in(OrderRevenueSplit::getStatus, statuses).orderByDesc(OrderRevenueSplit::getCreatedAt).last("LIMIT 5"));
    }

    default List<OrderRevenueSplit> findByMerchantIdInAndSettlementBatchNoOrderByCreatedAtDesc( Collection<String> merchantIds, String settlementBatchNo) {
    return selectList(Wrappers.<OrderRevenueSplit>lambdaQuery().in(OrderRevenueSplit::getMerchantId, merchantIds).eq(OrderRevenueSplit::getSettlementBatchNo, settlementBatchNo).orderByDesc(OrderRevenueSplit::getCreatedAt));
    }

    java.util.List<java.util.LinkedHashMap<String, Object>> _aggregateDailyByMerchants(
            @org.springframework.data.repository.query.Param("merchantIds") Collection<String> merchantIds,
            @org.springframework.data.repository.query.Param("from") Instant from,
            @org.springframework.data.repository.query.Param("to") Instant to);

    default List<Object[]> aggregateDailyByMerchants(Collection<String> merchantIds, Instant from, Instant to) {
        return ColumnMapRows.toObjectRows(_aggregateDailyByMerchants(merchantIds, from, to), 8);
    }

    java.util.List<java.util.LinkedHashMap<String, Object>> _aggregateBatchByMerchants(
            @org.springframework.data.repository.query.Param("merchantIds") Collection<String> merchantIds,
            @org.springframework.data.repository.query.Param("from") Instant from,
            @org.springframework.data.repository.query.Param("to") Instant to);

    default List<Object[]> aggregateBatchByMerchants(Collection<String> merchantIds, Instant from, Instant to) {
        return ColumnMapRows.toObjectRows(_aggregateBatchByMerchants(merchantIds, from, to), 11);
    }

}
