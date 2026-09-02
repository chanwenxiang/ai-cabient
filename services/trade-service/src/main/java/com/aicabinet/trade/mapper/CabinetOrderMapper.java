package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.CabinetOrder;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

@Mapper
public interface CabinetOrderMapper extends BaseTradeMapper<CabinetOrder> {

    CabinetOrder findByIdForUpdateRaw(@Param("orderId") String orderId);

    default Optional<CabinetOrder> findByIdForUpdate(String orderId) {
        return Optional.ofNullable(findByIdForUpdateRaw(orderId));
    }

    /** 仅当 pay_trade_no 为空时写入，避免并发回填覆盖。 */
    int backfillPayTradeNoIfAbsent(@Param("orderId") String orderId, @Param("tradeNo") String tradeNo);

    /** 仅当 payment_operation_id 为空时写入，避免并发回填覆盖。 */
    int backfillPaymentOperationIdIfAbsent(@Param("orderId") String orderId, @Param("operationId") String operationId);

    default Optional<CabinetOrder> findBySessionId(String sessionId) {
    return Optional.ofNullable(selectOne(Wrappers.<CabinetOrder>lambdaQuery().eq(CabinetOrder::getSessionId, sessionId)));
    }

    default Page<CabinetOrder> findAllByOrderByCreatedAtDesc(Pageable pageable) {
    var mpPage = new com.baomidou.mybatisplus.extension.plugins.pagination.Page<CabinetOrder>(
            pageable.getPageNumber() + 1L, pageable.getPageSize());
    var result = selectPage(mpPage, Wrappers.<CabinetOrder>lambdaQuery().orderByDesc(CabinetOrder::getCreatedAt));
    return new org.springframework.data.domain.PageImpl<>(result.getRecords(), pageable, result.getTotal());
    }

    default Page<CabinetOrder> findByDeviceIdOrderByCreatedAtDesc(String deviceId, Pageable pageable) {
    var mpPage = new com.baomidou.mybatisplus.extension.plugins.pagination.Page<CabinetOrder>(
            pageable.getPageNumber() + 1L, pageable.getPageSize());
    var result = selectPage(mpPage, Wrappers.<CabinetOrder>lambdaQuery().eq(CabinetOrder::getDeviceId, deviceId).orderByDesc(CabinetOrder::getCreatedAt));
    return new org.springframework.data.domain.PageImpl<>(result.getRecords(), pageable, result.getTotal());
    }

    record OrderFilterCriteria(
            String deviceId,
            Collection<String> deviceIds,
            String status,
            Instant createdBefore,
            Instant createdFrom,
            Instant createdTo,
            String orderId,
            Long userId,
            String sessionId,
            String payTradeNo,
            String payChannel,
            String keyword,
            boolean excludeZeroAmount) {}

    default Page<CabinetOrder> findByFiltersOrderByCreatedAtDesc(
            String deviceId, Collection<String> deviceIds, String status, Pageable pageable) {
        return findByFiltersOrderByCreatedAtDesc(
                new OrderFilterCriteria(deviceId, deviceIds, status, null, null, null,
                        null, null, null, null, null, null, false), pageable);
    }

    default Page<CabinetOrder> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable) {
    var mpPage = new com.baomidou.mybatisplus.extension.plugins.pagination.Page<CabinetOrder>(
            pageable.getPageNumber() + 1L, pageable.getPageSize());
    var result = selectPage(mpPage, Wrappers.<CabinetOrder>lambdaQuery().eq(CabinetOrder::getUserId, userId).orderByDesc(CabinetOrder::getCreatedAt));
    return new org.springframework.data.domain.PageImpl<>(result.getRecords(), pageable, result.getTotal());
    }

    default java.util.List<CabinetOrder> findByCreatedAtAfter(Instant since) {
    return selectList(Wrappers.<CabinetOrder>lambdaQuery().gt(CabinetOrder::getCreatedAt, since));
    }

    default long countByCreatedAtAfter(Instant since) {
    Long c = selectCount(Wrappers.<CabinetOrder>lambdaQuery().gt(CabinetOrder::getCreatedAt, since));
    return c == null ? 0 : c;
    }

    long sumTotalAmountSince(@Param("since") Instant since);

    long sumTotalAmount();

    default long countByDeviceId(String deviceId) {
    Long c = selectCount(Wrappers.<CabinetOrder>lambdaQuery().eq(CabinetOrder::getDeviceId, deviceId));
    return c == null ? 0 : c;
    }

    default long countByDeviceIdAndCreatedAtAfter(String deviceId, Instant since) {
    Long c = selectCount(Wrappers.<CabinetOrder>lambdaQuery().eq(CabinetOrder::getDeviceId, deviceId).gt(CabinetOrder::getCreatedAt, since));
    return c == null ? 0 : c;
    }

        long sumAmountByDeviceId(@Param("deviceId") String deviceId);


        long sumAmountByDeviceIdSince(@Param("deviceId") String deviceId, @Param("since") Instant since);


        long sumTotalAmountBetween(@Param("start") Instant start, @Param("end") Instant end);


        java.util.List<String> findOrderIdsBetween(@Param("start") Instant start, @Param("end") Instant end);


        java.util.List<CabinetOrder> findByCreatedAtBetween(@Param("start") Instant start, @Param("end") Instant end);


    default Page<CabinetOrder> findByDeviceIdInOrderByCreatedAtDesc(Collection<String> deviceIds, Pageable pageable) {
    var mpPage = new com.baomidou.mybatisplus.extension.plugins.pagination.Page<CabinetOrder>(
            pageable.getPageNumber() + 1L, pageable.getPageSize());
    var result = selectPage(mpPage, Wrappers.<CabinetOrder>lambdaQuery().in(CabinetOrder::getDeviceId, deviceIds).orderByDesc(CabinetOrder::getCreatedAt));
    return new org.springframework.data.domain.PageImpl<>(result.getRecords(), pageable, result.getTotal());
    }

    default long countByDeviceIdIn(Collection<String> deviceIds) {
    Long c = selectCount(Wrappers.<CabinetOrder>lambdaQuery().in(CabinetOrder::getDeviceId, deviceIds));
    return c == null ? 0 : c;
    }

    default long countByDeviceIdInAndCreatedAtAfter(Collection<String> deviceIds, Instant since) {
    Long c = selectCount(Wrappers.<CabinetOrder>lambdaQuery().in(CabinetOrder::getDeviceId, deviceIds).gt(CabinetOrder::getCreatedAt, since));
    return c == null ? 0 : c;
    }

    default long countByDeviceIdInAndCreatedAtBetween(
            Collection<String> deviceIds, Instant start, Instant end) {
        if (deviceIds == null || deviceIds.isEmpty()) {
            return 0;
        }
        Long c = selectCount(Wrappers.<CabinetOrder>lambdaQuery()
                .in(CabinetOrder::getDeviceId, deviceIds)
                .ge(CabinetOrder::getCreatedAt, start)
                .lt(CabinetOrder::getCreatedAt, end));
        return c == null ? 0 : c;
    }

    default List<CabinetOrder> findByDeviceIdInAndCreatedAtAfter(Collection<String> deviceIds, Instant since) {
    return selectList(Wrappers.<CabinetOrder>lambdaQuery().in(CabinetOrder::getDeviceId, deviceIds).gt(CabinetOrder::getCreatedAt, since));
    }

    default List<CabinetOrder> findByStatusAndCreatedAtBefore(String status, Instant cutoff) {
        return selectList(Wrappers.<CabinetOrder>lambdaQuery()
                .eq(CabinetOrder::getStatus, status)
                .lt(CabinetOrder::getCreatedAt, cutoff)
                .orderByAsc(CabinetOrder::getCreatedAt));
    }

    /** 超时关单等批处理：限制单次条数。 */
    default List<CabinetOrder> findByStatusAndCreatedAtBefore(String status, Instant cutoff, int limit) {
        int lim = Math.max(1, Math.min(limit, 1000));
        return selectList(Wrappers.<CabinetOrder>lambdaQuery()
                .eq(CabinetOrder::getStatus, status)
                .lt(CabinetOrder::getCreatedAt, cutoff)
                .orderByAsc(CabinetOrder::getCreatedAt)
                .last("LIMIT " + lim));
    }

    default long countByStatusAndCreatedAtBefore(String status, Instant cutoff) {
        Long c = selectCount(Wrappers.<CabinetOrder>lambdaQuery()
                .eq(CabinetOrder::getStatus, status)
                .lt(CabinetOrder::getCreatedAt, cutoff));
        return c == null ? 0 : c;
    }

    default Page<CabinetOrder> findByFiltersOrderByCreatedAtDesc(
            String deviceId, Collection<String> deviceIds, String status,
            Instant createdBefore, Pageable pageable) {
        return findByFiltersOrderByCreatedAtDesc(
                new OrderFilterCriteria(deviceId, deviceIds, status, createdBefore, null, null,
                        null, null, null, null, null, null, false), pageable);
    }

    /**
     * 运营订单列表筛选：设备范围 + 状态/账龄 + 精确字段 + 关键词（订单号/设备/会话/用户/流水）。
     */
    default Page<CabinetOrder> findByFiltersOrderByCreatedAtDesc(
            OrderFilterCriteria criteria, Pageable pageable) {
        var mpPage = new com.baomidou.mybatisplus.extension.plugins.pagination.Page<CabinetOrder>(
                pageable.getPageNumber() + 1L, pageable.getPageSize());
        var q = Wrappers.<CabinetOrder>lambdaQuery();
        if (!applyOrderDeviceScope(q, criteria.deviceId(), criteria.deviceIds())) {
            return new org.springframework.data.domain.PageImpl<>(List.of(), pageable, 0);
        }
        applyOrderExactFilters(q, criteria);
        applyOrderKeywordFilter(q, criteria.keyword());
        q.orderByDesc(CabinetOrder::getCreatedAt);
        var result = selectPage(mpPage, q);
        return new org.springframework.data.domain.PageImpl<>(result.getRecords(), pageable, result.getTotal());
    }

    private static boolean applyOrderDeviceScope(
            com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<CabinetOrder> q,
            String deviceId, Collection<String> deviceIds) {
        if (deviceId != null && !deviceId.isBlank()) {
            q.eq(CabinetOrder::getDeviceId, deviceId.trim());
            return true;
        }
        if (deviceIds == null) {
            return true;
        }
        if (deviceIds.isEmpty()) {
            return false;
        }
        q.in(CabinetOrder::getDeviceId, deviceIds);
        return true;
    }

    private static void applyOrderExactFilters(
            com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<CabinetOrder> q,
            OrderFilterCriteria criteria) {
        if (criteria.status() != null && !criteria.status().isBlank()) {
            q.eq(CabinetOrder::getStatus, criteria.status().trim());
        }
        if (criteria.createdBefore() != null) {
            q.lt(CabinetOrder::getCreatedAt, criteria.createdBefore());
        }
        if (criteria.createdFrom() != null) {
            q.ge(CabinetOrder::getCreatedAt, criteria.createdFrom());
        }
        if (criteria.createdTo() != null) {
            q.le(CabinetOrder::getCreatedAt, criteria.createdTo());
        }
        if (criteria.orderId() != null && !criteria.orderId().isBlank()) {
            q.eq(CabinetOrder::getOrderId, criteria.orderId().trim());
        }
        if (criteria.userId() != null) {
            q.eq(CabinetOrder::getUserId, criteria.userId());
        }
        if (criteria.sessionId() != null && !criteria.sessionId().isBlank()) {
            q.eq(CabinetOrder::getSessionId, criteria.sessionId().trim());
        }
        if (criteria.payTradeNo() != null && !criteria.payTradeNo().isBlank()) {
            String trade = criteria.payTradeNo().trim();
            q.and(w -> w.eq(CabinetOrder::getPayTradeNo, trade)
                    .or()
                    .eq(CabinetOrder::getPaymentOperationId, trade));
        }
        if (criteria.payChannel() != null && !criteria.payChannel().isBlank()) {
            q.eq(CabinetOrder::getPayChannel, criteria.payChannel().trim());
        }
        // 零元单多为开门未取货结案；运营列表可屏蔽以免淹没真实成交
        if (criteria.excludeZeroAmount()) {
            q.gt(CabinetOrder::getTotalAmountCents, 0);
        }
    }

    private static void applyOrderKeywordFilter(
            com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<CabinetOrder> q,
            String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return;
        }
        String kw = keyword.trim();
        Long kwUserId = parseOptionalUserId(kw);
        Long finalKwUserId = kwUserId;
        q.and(w -> {
            w.like(CabinetOrder::getOrderId, kw)
                    .or().like(CabinetOrder::getDeviceId, kw)
                    .or().like(CabinetOrder::getDeviceName, kw)
                    .or().like(CabinetOrder::getMerchantId, kw)
                    .or().like(CabinetOrder::getMerchantName, kw)
                    .or().like(CabinetOrder::getSessionId, kw)
                    .or().like(CabinetOrder::getPayTradeNo, kw)
                    .or().like(CabinetOrder::getPaymentOperationId, kw);
            if (finalKwUserId != null) {
                w.or().eq(CabinetOrder::getUserId, finalKwUserId);
            }
        });
    }

    private static Long parseOptionalUserId(String kw) {
        try {
            return Long.parseLong(kw);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    default long countByUserIdAndStatus(Long userId, String status) {
        Long c = selectCount(Wrappers.<CabinetOrder>lambdaQuery()
                .eq(CabinetOrder::getUserId, userId)
                .eq(CabinetOrder::getStatus, status));
        return c == null ? 0 : c;
    }

    long sumTotalAmountByDeviceIdIn(@Param("deviceIds") Collection<String> deviceIds);

    long sumTotalAmountByDeviceIdInSince(@Param("deviceIds") Collection<String> deviceIds,
                                         @Param("since") Instant since);

    long sumTotalAmountByDeviceIdInBetween(@Param("deviceIds") Collection<String> deviceIds,
                                           @Param("start") Instant start,
                                           @Param("end") Instant end);

    default List<CabinetOrder> findByCouponIdInAndCreatedAtAfter(
            Collection<Long> couponIds, Instant since) {
        if (couponIds == null || couponIds.isEmpty()) {
            return List.of();
        }
        return selectList(Wrappers.<CabinetOrder>lambdaQuery()
                .in(CabinetOrder::getCouponId, couponIds)
                .ge(CabinetOrder::getCreatedAt, since)
                .orderByDesc(CabinetOrder::getCreatedAt));
    }

    default List<CabinetOrder> findByStatusAndCreatedAtAfter(String status, Instant since) {
        return selectList(Wrappers.<CabinetOrder>lambdaQuery()
                .eq(CabinetOrder::getStatus, status)
                .gt(CabinetOrder::getCreatedAt, since));
    }

}
