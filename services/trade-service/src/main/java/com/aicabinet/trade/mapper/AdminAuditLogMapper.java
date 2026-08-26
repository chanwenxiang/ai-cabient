package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.AdminAuditLog;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

@Mapper
public interface AdminAuditLogMapper extends BaseTradeMapper<AdminAuditLog> {

    default Page<AdminAuditLog> findAllByOrderByLogId(Pageable pageable, boolean asc) {
        var mpPage = new com.baomidou.mybatisplus.extension.plugins.pagination.Page<AdminAuditLog>(
                pageable.getPageNumber() + 1L, pageable.getPageSize());
        var q = Wrappers.<AdminAuditLog>lambdaQuery();
        if (asc) {
            q.orderByAsc(AdminAuditLog::getLogId);
        } else {
            q.orderByDesc(AdminAuditLog::getLogId);
        }
        var result = selectPage(mpPage, q);
        return new org.springframework.data.domain.PageImpl<>(result.getRecords(), pageable, result.getTotal());
    }

    default Page<AdminAuditLog> findAllByOrderByCreatedAtDesc(Pageable pageable) {
    var mpPage = new com.baomidou.mybatisplus.extension.plugins.pagination.Page<AdminAuditLog>(
            pageable.getPageNumber() + 1L, pageable.getPageSize());
    var result = selectPage(mpPage, Wrappers.<AdminAuditLog>lambdaQuery().orderByDesc(AdminAuditLog::getCreatedAt));
    return new org.springframework.data.domain.PageImpl<>(result.getRecords(), pageable, result.getTotal());
    }

    default Page<AdminAuditLog> findByOperatorIdOrderByCreatedAtDesc(Long operatorId, Pageable pageable) {
    var mpPage = new com.baomidou.mybatisplus.extension.plugins.pagination.Page<AdminAuditLog>(
            pageable.getPageNumber() + 1L, pageable.getPageSize());
    var result = selectPage(mpPage, Wrappers.<AdminAuditLog>lambdaQuery().eq(AdminAuditLog::getOperatorId, operatorId).orderByDesc(AdminAuditLog::getCreatedAt));
    return new org.springframework.data.domain.PageImpl<>(result.getRecords(), pageable, result.getTotal());
    }

    default List<AdminAuditLog> findByTargetTypeAndTargetIdOrderByCreatedAtAsc(String targetType, String targetId) {
    return selectList(Wrappers.<AdminAuditLog>lambdaQuery().eq(AdminAuditLog::getTargetType, targetType).eq(AdminAuditLog::getTargetId, targetId).orderByAsc(AdminAuditLog::getCreatedAt));
    }

    default long countByActionAndCreatedAtBetween(String action, Instant start, Instant end) {
        Long c = selectCount(Wrappers.<AdminAuditLog>lambdaQuery()
                .eq(AdminAuditLog::getAction, action)
                .ge(AdminAuditLog::getCreatedAt, start)
                .lt(AdminAuditLog::getCreatedAt, end));
        return c == null ? 0 : c;
    }

    default long countByActionAndOperatorIdNotAndCreatedAtBetween(
            String action, Long operatorId, Instant start, Instant end) {
        Long c = selectCount(Wrappers.<AdminAuditLog>lambdaQuery()
                .eq(AdminAuditLog::getAction, action)
                .ne(AdminAuditLog::getOperatorId, operatorId)
                .ge(AdminAuditLog::getCreatedAt, start)
                .lt(AdminAuditLog::getCreatedAt, end));
        return c == null ? 0 : c;
    }

    /** page 为 0-based。 */
    default Page<AdminAuditLog> searchPage(
            Long operatorId,
            String action,
            String targetType,
            boolean logIdAsc,
            int page,
            int size) {
        var q = Wrappers.<AdminAuditLog>lambdaQuery();
        if (operatorId != null) {
            q.eq(AdminAuditLog::getOperatorId, operatorId);
        }
        if (action != null && !action.isBlank()) {
            q.eq(AdminAuditLog::getAction, action.trim());
        }
        if (targetType != null && !targetType.isBlank()) {
            q.eq(AdminAuditLog::getTargetType, targetType.trim());
        }
        if (logIdAsc) {
            q.orderByAsc(AdminAuditLog::getLogId);
        } else {
            q.orderByDesc(AdminAuditLog::getLogId);
        }
        var mpPage = new com.baomidou.mybatisplus.extension.plugins.pagination.Page<AdminAuditLog>(
                page + 1L, size);
        var result = selectPage(mpPage, q);
        return new org.springframework.data.domain.PageImpl<>(
                result.getRecords(),
                org.springframework.data.domain.PageRequest.of(page, size),
                result.getTotal());
    }

}
