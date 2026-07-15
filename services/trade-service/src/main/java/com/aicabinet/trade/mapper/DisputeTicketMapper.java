package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.DisputeTicket;
import com.aicabinet.trade.domain.ShoppingSession;
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

    default Optional<DisputeTicket> findBySessionId(String sessionId) {
    return Optional.ofNullable(selectOne(Wrappers.<DisputeTicket>lambdaQuery().eq(DisputeTicket::getSessionId, sessionId)));
    }

    default List<DisputeTicket> findByStatusOrderByCreatedAtDesc(String status) {
    return selectList(Wrappers.<DisputeTicket>lambdaQuery().eq(DisputeTicket::getStatus, status).orderByDesc(DisputeTicket::getCreatedAt));
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

    default Page<DisputeTicket> search( @Param("status") String status, @Param("sessionId") String sessionId, @Param("deviceId") String deviceId, Pageable pageable) {
        var mpPage = new com.baomidou.mybatisplus.extension.plugins.pagination.Page<DisputeTicket>(
                pageable.getPageNumber() + 1L, pageable.getPageSize());
        var q = Wrappers.<DisputeTicket>lambdaQuery()
                .eq(status != null && !status.isEmpty(), DisputeTicket::getStatus, status)
                .eq(sessionId != null && !sessionId.isEmpty(), DisputeTicket::getSessionId, sessionId)
                .orderByDesc(DisputeTicket::getCreatedAt);
        var result = selectPage(mpPage, q);
        return new org.springframework.data.domain.PageImpl<>(result.getRecords(), pageable, result.getTotal());
    }

        List<DisputeTicket> findByUserIdOrderByCreatedAtDesc(@Param("userId") Long userId);


    default Page<DisputeTicket> searchByDeviceIds( @Param("status") String status, @Param("sessionId") String sessionId, @Param("deviceIds") Collection<String> deviceIds, Pageable pageable) {
        var mpPage = new com.baomidou.mybatisplus.extension.plugins.pagination.Page<DisputeTicket>(
                pageable.getPageNumber() + 1L, pageable.getPageSize());
        var q = Wrappers.<DisputeTicket>lambdaQuery()
                .eq(status != null && !status.isEmpty(), DisputeTicket::getStatus, status)
                .eq(sessionId != null && !sessionId.isEmpty(), DisputeTicket::getSessionId, sessionId)
                .orderByDesc(DisputeTicket::getCreatedAt);
        var result = selectPage(mpPage, q);
        return new org.springframework.data.domain.PageImpl<>(result.getRecords(), pageable, result.getTotal());
    }

        long countOpenByDeviceIds(@Param("deviceIds") Collection<String> deviceIds);


        long countOverdue(@Param("now") Instant now);


        long countNearSla(@Param("now") Instant now, @Param("threshold") Instant threshold);


        long countResolvedSince(@Param("since") Instant since);


        long countResolvedWithinSlaSince(@Param("since") Instant since);


}
