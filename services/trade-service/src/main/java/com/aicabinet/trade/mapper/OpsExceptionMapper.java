package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.OpsException;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

@Mapper
public interface OpsExceptionMapper extends BaseTradeMapper<OpsException> {

    default Optional<OpsException> findFirstByDedupKeyAndStatusIn(String dedupKey, Collection<String> statuses) {
    return Optional.ofNullable(selectOne(Wrappers.<OpsException>lambdaQuery().eq(OpsException::getDedupKey, dedupKey).in(OpsException::getStatus, statuses).last("LIMIT 1")));
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

    default Page<OpsException> findByDeviceIdInOrderByCreatedAtDesc(Collection<String> deviceIds, Pageable pageable) {
    var mpPage = new com.baomidou.mybatisplus.extension.plugins.pagination.Page<OpsException>(
            pageable.getPageNumber() + 1L, pageable.getPageSize());
    var result = selectPage(mpPage, Wrappers.<OpsException>lambdaQuery().in(OpsException::getDeviceId, deviceIds).orderByDesc(OpsException::getCreatedAt));
    return new org.springframework.data.domain.PageImpl<>(result.getRecords(), pageable, result.getTotal());
    }

    default Page<OpsException> findByDeviceIdInAndStatusOrderByCreatedAtDesc(Collection<String> deviceIds, String status, Pageable pageable) {
    var mpPage = new com.baomidou.mybatisplus.extension.plugins.pagination.Page<OpsException>(
            pageable.getPageNumber() + 1L, pageable.getPageSize());
    var result = selectPage(mpPage, Wrappers.<OpsException>lambdaQuery().in(OpsException::getDeviceId, deviceIds).eq(OpsException::getStatus, status).orderByDesc(OpsException::getCreatedAt));
    return new org.springframework.data.domain.PageImpl<>(result.getRecords(), pageable, result.getTotal());
    }

}
