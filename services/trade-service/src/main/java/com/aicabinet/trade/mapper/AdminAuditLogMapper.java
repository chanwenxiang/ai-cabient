package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.AdminAuditLog;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

@Mapper
public interface AdminAuditLogMapper extends BaseTradeMapper<AdminAuditLog> {

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

}
