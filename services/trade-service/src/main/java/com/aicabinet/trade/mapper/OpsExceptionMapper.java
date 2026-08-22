package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.OpsException;
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
public interface OpsExceptionMapper extends BaseTradeMapper<OpsException> {

    OpsException _findByIdForUpdateRaw(@Param("exceptionId") String exceptionId);

    default Optional<OpsException> findByIdForUpdate(String exceptionId) {
        return Optional.ofNullable(_findByIdForUpdateRaw(exceptionId));
    }

    default Optional<OpsException> findFirstByDedupKeyAndStatusIn(String dedupKey, Collection<String> statuses) {
    return Optional.ofNullable(selectOne(Wrappers.<OpsException>lambdaQuery().eq(OpsException::getDedupKey, dedupKey).in(OpsException::getStatus, statuses).last("LIMIT 1")));
    }

    default Optional<OpsException> findFirstByExceptionTypeAndDeviceIdAndStatusIn(
            String exceptionType, String deviceId, Collection<String> statuses) {
        return Optional.ofNullable(selectOne(Wrappers.<OpsException>lambdaQuery()
                .eq(OpsException::getExceptionType, exceptionType)
                .eq(OpsException::getDeviceId, deviceId)
                .in(OpsException::getStatus, statuses)
                .last("LIMIT 1")));
    }

    default List<OpsException> findBySessionIdAndStatusIn(String sessionId, Collection<String> statuses) {
    return selectList(Wrappers.<OpsException>lambdaQuery().eq(OpsException::getSessionId, sessionId).in(OpsException::getStatus, statuses));
    }

    default Page<OpsException> findAllByOrderByCreatedAtDesc(Pageable pageable) {
    var mpPage = new com.baomidou.mybatisplus.extension.plugins.pagination.Page<OpsException>(
            pageable.getPageNumber() + 1L, pageable.getPageSize());
    var result = selectPage(mpPage, Wrappers.<OpsException>lambdaQuery().orderByDesc(OpsException::getCreatedAt));
    return new org.springframework.data.domain.PageImpl<>(result.getRecords(), pageable, result.getTotal());
    }

    default Page<OpsException> findByStatusOrderByCreatedAtDesc(String status, Pageable pageable) {
    var mpPage = new com.baomidou.mybatisplus.extension.plugins.pagination.Page<OpsException>(
            pageable.getPageNumber() + 1L, pageable.getPageSize());
    var result = selectPage(mpPage, Wrappers.<OpsException>lambdaQuery().eq(OpsException::getStatus, status).orderByDesc(OpsException::getCreatedAt));
    return new org.springframework.data.domain.PageImpl<>(result.getRecords(), pageable, result.getTotal());
    }

    default Page<OpsException> findFiltered(String status, String severity, Pageable pageable) {
        return findFiltered(status, severity, false, pageable);
    }

    default Page<OpsException> findFiltered(String status, String severity, boolean overdueOnly, Pageable pageable) {
        return findFiltered(status, severity, overdueOnly, null, pageable);
    }

    /** archived：null/false 仅看未归档（默认）；true 仅看已归档。 */
    default Page<OpsException> findFiltered(String status, String severity, boolean overdueOnly,
                                            Boolean archived, Pageable pageable) {
    var mpPage = new com.baomidou.mybatisplus.extension.plugins.pagination.Page<OpsException>(
            pageable.getPageNumber() + 1L, pageable.getPageSize());
    var query = Wrappers.<OpsException>lambdaQuery();
    if (overdueOnly) {
        Instant now = Instant.now();
        if (status != null && !status.isBlank()) {
            query.eq(OpsException::getStatus, status);
        } else {
            query.in(OpsException::getStatus, List.of("OPEN", "PROCESSING"));
        }
        query.isNotNull(OpsException::getSlaDueAt).lt(OpsException::getSlaDueAt, now);
    } else if (status != null && !status.isBlank()) {
        query.eq(OpsException::getStatus, status);
    }
    if (severity != null && !severity.isBlank()) {
        query.eq(OpsException::getSeverity, severity);
    }
    query.eq(OpsException::getArchived, Boolean.TRUE.equals(archived));
    if (overdueOnly) {
        query.orderByAsc(OpsException::getSlaDueAt).orderByDesc(OpsException::getCreatedAt);
    } else {
        query.orderByDesc(OpsException::getCreatedAt);
    }
    var result = selectPage(mpPage, query);
    return new org.springframework.data.domain.PageImpl<>(result.getRecords(), pageable, result.getTotal());
    }

    default Page<OpsException> findByDeviceIdInOrderByCreatedAtDesc(Collection<String> deviceIds, Pageable pageable) {
    var mpPage = new com.baomidou.mybatisplus.extension.plugins.pagination.Page<OpsException>(
            pageable.getPageNumber() + 1L, pageable.getPageSize());
    var result = selectPage(mpPage, Wrappers.<OpsException>lambdaQuery().in(OpsException::getDeviceId, deviceIds)
            .eq(OpsException::getArchived, false).orderByDesc(OpsException::getCreatedAt));
    return new org.springframework.data.domain.PageImpl<>(result.getRecords(), pageable, result.getTotal());
    }

    default Page<OpsException> findByDeviceIdInAndStatusOrderByCreatedAtDesc(Collection<String> deviceIds, String status, Pageable pageable) {
    var mpPage = new com.baomidou.mybatisplus.extension.plugins.pagination.Page<OpsException>(
            pageable.getPageNumber() + 1L, pageable.getPageSize());
    var result = selectPage(mpPage, Wrappers.<OpsException>lambdaQuery().in(OpsException::getDeviceId, deviceIds)
            .eq(OpsException::getStatus, status).eq(OpsException::getArchived, false).orderByDesc(OpsException::getCreatedAt));
    return new org.springframework.data.domain.PageImpl<>(result.getRecords(), pageable, result.getTotal());
    }

    default long countByExceptionTypeAndCreatedAtBetween(
            String exceptionType, Instant start, Instant end) {
        Long c = selectCount(Wrappers.<OpsException>lambdaQuery()
                .eq(OpsException::getExceptionType, exceptionType)
                .ge(OpsException::getCreatedAt, start)
                .lt(OpsException::getCreatedAt, end));
        return c == null ? 0 : c;
    }

    /** 当日已解决异常的创建→解决平均时长（小时）；全部未解决返回 null。 */
    default Double avgResolutionHoursByExceptionTypeAndCreatedAtBetween(
            String exceptionType, Instant start, Instant end) {
        List<Object> rows = selectObjs(Wrappers.<OpsException>query()
                .select("AVG(EXTRACT(EPOCH FROM (resolved_at - created_at)) / 3600.0)")
                .eq("exception_type", exceptionType)
                .ge("created_at", start)
                .lt("created_at", end)
                .isNotNull("resolved_at"));
        if (rows == null || rows.isEmpty() || rows.get(0) == null) {
            return null;
        }
        return ((Number) rows.get(0)).doubleValue();
    }

}
