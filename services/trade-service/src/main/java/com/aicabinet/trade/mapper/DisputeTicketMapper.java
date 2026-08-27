package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.DisputeTicket;
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
public interface DisputeTicketMapper extends BaseTradeMapper<DisputeTicket> {

    DisputeTicket findByIdForUpdateRaw(@Param("ticketId") String ticketId);

    default Optional<DisputeTicket> findByIdForUpdate(String ticketId) {
        return Optional.ofNullable(findByIdForUpdateRaw(ticketId));
    }

    default Optional<DisputeTicket> findBySessionId(String sessionId) {
    return Optional.ofNullable(selectOne(Wrappers.<DisputeTicket>lambdaQuery().eq(DisputeTicket::getSessionId, sessionId)));
    }

    default List<DisputeTicket> findByStatusOrderByCreatedAtDesc(String status) {
        return findByStatusOrderByCreatedAtDesc(status, 500);
    }

    default List<DisputeTicket> findByStatusOrderByCreatedAtDesc(String status, int limit) {
        int lim = Math.max(1, Math.min(limit, 500));
        return selectList(Wrappers.<DisputeTicket>lambdaQuery()
                .eq(DisputeTicket::getStatus, status)
                .orderByDesc(DisputeTicket::getCreatedAt)
                .last("LIMIT " + lim));
    }

    /** SLA 扫描：仅拉取仍可能需要提醒/逾期告警的 OPEN 工单。 */
    default List<DisputeTicket> findOpenNeedingSlaScan(int limit) {
        int lim = Math.max(1, Math.min(limit, 500));
        return selectList(Wrappers.<DisputeTicket>lambdaQuery()
                .eq(DisputeTicket::getStatus, "OPEN")
                .and(w -> w.isNull(DisputeTicket::getSlaDueAt)
                        .or().isNull(DisputeTicket::getSlaReminderAt)
                        .or().isNull(DisputeTicket::getSlaAlertedAt))
                .orderByAsc(DisputeTicket::getCreatedAt)
                .last("LIMIT " + lim));
    }

    default List<DisputeTicket> findTop10ByStatusOrderBySlaDueAtAscCreatedAtAsc(String status) {
    return selectList(Wrappers.<DisputeTicket>lambdaQuery().eq(DisputeTicket::getStatus, status).orderByAsc(DisputeTicket::getSlaDueAt).orderByAsc(DisputeTicket::getCreatedAt).last("LIMIT 10"));
    }

    default long countByStatus(String status) {
    Long c = selectCount(Wrappers.<DisputeTicket>lambdaQuery().eq(DisputeTicket::getStatus, status));
    return c == null ? 0 : c;
    }

    default long countByCreatedAtAfter(Instant since) {
    Long c = selectCount(Wrappers.<DisputeTicket>lambdaQuery().gt(DisputeTicket::getCreatedAt, since));
    return c == null ? 0 : c;
    }

    default Page<DisputeTicket> search(
            @Param("status") String status,
            @Param("sessionId") String sessionId,
            @Param("deviceId") String deviceId,
            @Param("orderId") String orderId,
            @Param("category") String category,
            @Param("reviewCode") String reviewCode,
            Pageable pageable) {
        var mpPage = new com.baomidou.mybatisplus.extension.plugins.pagination.Page<DisputeTicket>(
                pageable.getPageNumber() + 1L, pageable.getPageSize());
        var q = Wrappers.<DisputeTicket>lambdaQuery()
                .eq(status != null && !status.isEmpty(), DisputeTicket::getStatus, status)
                .eq(sessionId != null && !sessionId.isEmpty(), DisputeTicket::getSessionId, sessionId)
                .eq(category != null && !category.isEmpty(), DisputeTicket::getCategory, category)
                .eq(reviewCode != null && !reviewCode.isEmpty(), DisputeTicket::getReviewCode, reviewCode)
                .apply(deviceId != null && !deviceId.isBlank(),
                        "EXISTS (SELECT 1 FROM shopping_session s WHERE s.session_id = dispute_ticket.session_id AND s.device_id = {0})",
                        deviceId)
                .apply(orderId != null && !orderId.isBlank(),
                        "EXISTS (SELECT 1 FROM shopping_session s WHERE s.session_id = dispute_ticket.session_id AND s.order_id = {0})",
                        orderId)
                .orderByDesc(DisputeTicket::getCreatedAt);
        var result = selectPage(mpPage, q);
        return new org.springframework.data.domain.PageImpl<>(result.getRecords(), pageable, result.getTotal());
    }

    List<DisputeTicket> findByUserIdOrderByCreatedAtDesc(@Param("userId") Long userId);

    default Page<DisputeTicket> searchByDeviceIds(
            @Param("status") String status,
            @Param("sessionId") String sessionId,
            @Param("deviceIds") Collection<String> deviceIds,
            @Param("orderId") String orderId,
            @Param("category") String category,
            @Param("reviewCode") String reviewCode,
            Pageable pageable) {
        var mpPage = new com.baomidou.mybatisplus.extension.plugins.pagination.Page<DisputeTicket>(
                pageable.getPageNumber() + 1L, pageable.getPageSize());
        var q = Wrappers.<DisputeTicket>lambdaQuery()
                .eq(status != null && !status.isEmpty(), DisputeTicket::getStatus, status)
                .eq(sessionId != null && !sessionId.isEmpty(), DisputeTicket::getSessionId, sessionId)
                .eq(category != null && !category.isEmpty(), DisputeTicket::getCategory, category)
                .eq(reviewCode != null && !reviewCode.isEmpty(), DisputeTicket::getReviewCode, reviewCode)
                .orderByDesc(DisputeTicket::getCreatedAt);
        if (deviceIds != null && !deviceIds.isEmpty()) {
            String inList = deviceIds.stream()
                    .map(id -> "'" + String.valueOf(id).replace("'", "''") + "'")
                    .collect(java.util.stream.Collectors.joining(","));
            q.inSql(DisputeTicket::getSessionId,
                    "SELECT session_id FROM shopping_session WHERE device_id IN (" + inList + ")");
        }
        if (orderId != null && !orderId.isBlank()) {
            q.apply(
                    "EXISTS (SELECT 1 FROM shopping_session s WHERE s.session_id = dispute_ticket.session_id AND s.order_id = {0})",
                    orderId);
        }
        var result = selectPage(mpPage, q);
        return new org.springframework.data.domain.PageImpl<>(result.getRecords(), pageable, result.getTotal());
    }

        long countOpenByDeviceIds(@Param("deviceIds") Collection<String> deviceIds);

        long countOverdue(@Param("now") Instant now);

        long countNearSla(@Param("now") Instant now, @Param("threshold") Instant threshold);

        long countResolvedSince(@Param("since") Instant since);

        long countResolvedWithinSlaSince(@Param("since") Instant since);

}
