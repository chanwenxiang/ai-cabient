package com.aicabinet.trade.mapper;

import com.aicabinet.common.enums.SessionState;
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
public interface ShoppingSessionMapper extends BaseTradeMapper<ShoppingSession> {

    default Optional<ShoppingSession> findByIdempotencyKey(String idempotencyKey) {
    return Optional.ofNullable(selectOne(Wrappers.<ShoppingSession>lambdaQuery().eq(ShoppingSession::getIdempotencyKey, idempotencyKey)));
    }

    default Optional<ShoppingSession> findFirstByUserIdAndStateInOrderByCreatedAtDesc( Long userId, Collection<SessionState> states) {
    return Optional.ofNullable(selectOne(Wrappers.<ShoppingSession>lambdaQuery().eq(ShoppingSession::getUserId, userId).in(ShoppingSession::getState, states).orderByDesc(ShoppingSession::getCreatedAt).last("LIMIT 1")));
    }

    default List<ShoppingSession> findByDeviceIdAndStateIn(String deviceId, List<SessionState> states) {
    return selectList(Wrappers.<ShoppingSession>lambdaQuery().eq(ShoppingSession::getDeviceId, deviceId).in(ShoppingSession::getState, states));
    }

    default Page<ShoppingSession> findAllByOrderByCreatedAtDesc(Pageable pageable) {
    var mpPage = new com.baomidou.mybatisplus.extension.plugins.pagination.Page<ShoppingSession>(
            pageable.getPageNumber() + 1L, pageable.getPageSize());
    var result = selectPage(mpPage, Wrappers.<ShoppingSession>lambdaQuery().orderByDesc(ShoppingSession::getCreatedAt));
    return new org.springframework.data.domain.PageImpl<>(result.getRecords(), pageable, result.getTotal());
    }

    default Page<ShoppingSession> findByDeviceIdOrderByCreatedAtDesc(String deviceId, Pageable pageable) {
    var mpPage = new com.baomidou.mybatisplus.extension.plugins.pagination.Page<ShoppingSession>(
            pageable.getPageNumber() + 1L, pageable.getPageSize());
    var result = selectPage(mpPage, Wrappers.<ShoppingSession>lambdaQuery().eq(ShoppingSession::getDeviceId, deviceId).orderByDesc(ShoppingSession::getCreatedAt));
    return new org.springframework.data.domain.PageImpl<>(result.getRecords(), pageable, result.getTotal());
    }

    default Page<ShoppingSession> findByStateOrderByCreatedAtDesc(SessionState state, Pageable pageable) {
    var mpPage = new com.baomidou.mybatisplus.extension.plugins.pagination.Page<ShoppingSession>(
            pageable.getPageNumber() + 1L, pageable.getPageSize());
    var result = selectPage(mpPage, Wrappers.<ShoppingSession>lambdaQuery().eq(ShoppingSession::getState, state).orderByDesc(ShoppingSession::getCreatedAt));
    return new org.springframework.data.domain.PageImpl<>(result.getRecords(), pageable, result.getTotal());
    }

    default List<ShoppingSession> findTop10ByStateOrderByUpdatedAtAsc(SessionState state) {
    return selectList(Wrappers.<ShoppingSession>lambdaQuery().eq(ShoppingSession::getState, state).orderByAsc(ShoppingSession::getUpdatedAt).last("LIMIT 10"));
    }

    default Page<ShoppingSession> findByDeviceIdAndStateOrderByCreatedAtDesc(String deviceId, SessionState state, Pageable pageable) {
    var mpPage = new com.baomidou.mybatisplus.extension.plugins.pagination.Page<ShoppingSession>(
            pageable.getPageNumber() + 1L, pageable.getPageSize());
    var result = selectPage(mpPage, Wrappers.<ShoppingSession>lambdaQuery().eq(ShoppingSession::getDeviceId, deviceId).eq(ShoppingSession::getState, state).orderByDesc(ShoppingSession::getCreatedAt));
    return new org.springframework.data.domain.PageImpl<>(result.getRecords(), pageable, result.getTotal());
    }

    default long countByStateIn(List<SessionState> states) {
    Long c = selectCount(Wrappers.<ShoppingSession>lambdaQuery().in(ShoppingSession::getState, states));
    return c == null ? 0 : c;
    }

    default long countByCreatedAtAfter(java.time.Instant since) {
    Long c = selectCount(Wrappers.<ShoppingSession>lambdaQuery().gt(ShoppingSession::getCreatedAt, since));
    return c == null ? 0 : c;
    }

    default long countByDeviceId(String deviceId) {
    Long c = selectCount(Wrappers.<ShoppingSession>lambdaQuery().eq(ShoppingSession::getDeviceId, deviceId));
    return c == null ? 0 : c;
    }

    default long countByDeviceIdAndStateIn(String deviceId, List<SessionState> states) {
    Long c = selectCount(Wrappers.<ShoppingSession>lambdaQuery().eq(ShoppingSession::getDeviceId, deviceId).in(ShoppingSession::getState, states));
    return c == null ? 0 : c;
    }

    default long countByUserIdAndCreatedAtAfter(Long userId, java.time.Instant since) {
    Long c = selectCount(Wrappers.<ShoppingSession>lambdaQuery().eq(ShoppingSession::getUserId, userId).gt(ShoppingSession::getCreatedAt, since));
    return c == null ? 0 : c;
    }

    default long countByUserIdAndStateAndCreatedAtAfter(Long userId, SessionState state, java.time.Instant since) {
    Long c = selectCount(Wrappers.<ShoppingSession>lambdaQuery().eq(ShoppingSession::getUserId, userId).eq(ShoppingSession::getState, state).gt(ShoppingSession::getCreatedAt, since));
    return c == null ? 0 : c;
    }

    default long countByState(SessionState state) {
    Long c = selectCount(Wrappers.<ShoppingSession>lambdaQuery().eq(ShoppingSession::getState, state));
    return c == null ? 0 : c;
    }

    default long countByStateAndUpdatedAtAfter(SessionState state, java.time.Instant since) {
    Long c = selectCount(Wrappers.<ShoppingSession>lambdaQuery().eq(ShoppingSession::getState, state).gt(ShoppingSession::getUpdatedAt, since));
    return c == null ? 0 : c;
    }

    default Page<ShoppingSession> findByDeviceIdInOrderByCreatedAtDesc(Collection<String> deviceIds, Pageable pageable) {
    var mpPage = new com.baomidou.mybatisplus.extension.plugins.pagination.Page<ShoppingSession>(
            pageable.getPageNumber() + 1L, pageable.getPageSize());
    var result = selectPage(mpPage, Wrappers.<ShoppingSession>lambdaQuery().in(ShoppingSession::getDeviceId, deviceIds).orderByDesc(ShoppingSession::getCreatedAt));
    return new org.springframework.data.domain.PageImpl<>(result.getRecords(), pageable, result.getTotal());
    }

    default Page<ShoppingSession> findByDeviceIdInAndStateOrderByCreatedAtDesc( Collection<String> deviceIds, SessionState state, Pageable pageable) {
    var mpPage = new com.baomidou.mybatisplus.extension.plugins.pagination.Page<ShoppingSession>(
            pageable.getPageNumber() + 1L, pageable.getPageSize());
    var result = selectPage(mpPage, Wrappers.<ShoppingSession>lambdaQuery().in(ShoppingSession::getDeviceId, deviceIds).eq(ShoppingSession::getState, state).orderByDesc(ShoppingSession::getCreatedAt));
    return new org.springframework.data.domain.PageImpl<>(result.getRecords(), pageable, result.getTotal());
    }

    default long countByDeviceIdIn(Collection<String> deviceIds) {
    Long c = selectCount(Wrappers.<ShoppingSession>lambdaQuery().in(ShoppingSession::getDeviceId, deviceIds));
    return c == null ? 0 : c;
    }

    default long countByDeviceIdInAndStateIn(Collection<String> deviceIds, List<SessionState> states) {
    Long c = selectCount(Wrappers.<ShoppingSession>lambdaQuery().in(ShoppingSession::getDeviceId, deviceIds).in(ShoppingSession::getState, states));
    return c == null ? 0 : c;
    }

    default long countByDeviceIdInAndCreatedAtAfter(Collection<String> deviceIds, java.time.Instant since) {
    Long c = selectCount(Wrappers.<ShoppingSession>lambdaQuery().in(ShoppingSession::getDeviceId, deviceIds).gt(ShoppingSession::getCreatedAt, since));
    return c == null ? 0 : c;
    }

    default long countByDeviceIdInAndState(Collection<String> deviceIds, SessionState state) {
    Long c = selectCount(Wrappers.<ShoppingSession>lambdaQuery().in(ShoppingSession::getDeviceId, deviceIds).eq(ShoppingSession::getState, state));
    return c == null ? 0 : c;
    }

    default long countByDeviceIdInAndStateAndUpdatedAtAfter( Collection<String> deviceIds, SessionState state, java.time.Instant since) {
    Long c = selectCount(Wrappers.<ShoppingSession>lambdaQuery().in(ShoppingSession::getDeviceId, deviceIds).eq(ShoppingSession::getState, state).gt(ShoppingSession::getUpdatedAt, since));
    return c == null ? 0 : c;
    }

        List<ShoppingSession> findByStateInAndUpdatedAtAfter( @Param("states") Collection<SessionState> states, @Param("since") java.time.Instant since);


}
