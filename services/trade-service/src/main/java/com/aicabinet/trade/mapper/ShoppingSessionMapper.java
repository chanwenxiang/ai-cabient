package com.aicabinet.trade.mapper;

import com.aicabinet.common.enums.SessionState;
import com.aicabinet.trade.domain.ShoppingSession;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

@Mapper
public interface ShoppingSessionMapper extends BaseTradeMapper<ShoppingSession> {

    ShoppingSession _findByIdForUpdateRaw(@Param("sessionId") String sessionId);

    default Optional<ShoppingSession> findByIdForUpdate(String sessionId) {
        return Optional.ofNullable(_findByIdForUpdateRaw(sessionId));
    }

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

    /** 滞留扫描：按状态 + updated_at 早于截止时间（单次上限防爆内存）。 */
    default List<ShoppingSession> findByStateAndUpdatedAtBefore(SessionState state, java.time.Instant cutoff, int limit) {
        int lim = Math.max(1, Math.min(limit, 1000));
        return selectList(Wrappers.<ShoppingSession>lambdaQuery()
                .eq(ShoppingSession::getState, state)
                .lt(ShoppingSession::getUpdatedAt, cutoff)
                .orderByAsc(ShoppingSession::getUpdatedAt)
                .last("LIMIT " + lim));
    }

    /** 开门过久：SHOPPING 且 open_time 早于截止。 */
    default List<ShoppingSession> findByStateAndOpenTimeBefore(SessionState state, java.time.Instant cutoff, int limit) {
        int lim = Math.max(1, Math.min(limit, 1000));
        return selectList(Wrappers.<ShoppingSession>lambdaQuery()
                .eq(ShoppingSession::getState, state)
                .isNotNull(ShoppingSession::getOpenTime)
                .lt(ShoppingSession::getOpenTime, cutoff)
                .orderByAsc(ShoppingSession::getOpenTime)
                .last("LIMIT " + lim));
    }

    default List<ShoppingSession> findByStateInAndCreatedAtBefore(Collection<SessionState> states,
                                                                  java.time.Instant cutoff, int limit) {
        int lim = Math.max(1, Math.min(limit, 1000));
        return selectList(Wrappers.<ShoppingSession>lambdaQuery()
                .in(ShoppingSession::getState, states)
                .lt(ShoppingSession::getCreatedAt, cutoff)
                .orderByAsc(ShoppingSession::getCreatedAt)
                .last("LIMIT " + lim));
    }

    default List<ShoppingSession> findByStateInAndUpdatedAtBefore(Collection<SessionState> states,
                                                                  java.time.Instant cutoff, int limit) {
        int lim = Math.max(1, Math.min(limit, 1000));
        return selectList(Wrappers.<ShoppingSession>lambdaQuery()
                .in(ShoppingSession::getState, states)
                .and(w -> w.lt(ShoppingSession::getUpdatedAt, cutoff)
                        .or()
                        .isNull(ShoppingSession::getUpdatedAt).lt(ShoppingSession::getCreatedAt, cutoff))
                .orderByAsc(ShoppingSession::getUpdatedAt)
                .last("LIMIT " + lim));
    }

    default List<ShoppingSession> findByReplenishmentTaskIdAndStateIn(Long taskId, Collection<SessionState> states) {
        return selectList(Wrappers.<ShoppingSession>lambdaQuery()
                .eq(ShoppingSession::getReplenishmentTaskId, taskId)
                .in(ShoppingSession::getState, states));
    }

    default List<ShoppingSession> findByIdempotencyKeyStartingWithAndStateIn(String prefix,
                                                                             Collection<SessionState> states) {
        return selectList(Wrappers.<ShoppingSession>lambdaQuery()
                .likeRight(ShoppingSession::getIdempotencyKey, prefix)
                .in(ShoppingSession::getState, states));
    }

    default List<ShoppingSession> findByStateOrderByUpdatedAtAsc(SessionState state) {
    return selectList(Wrappers.<ShoppingSession>lambdaQuery().eq(ShoppingSession::getState, state).orderByAsc(ShoppingSession::getUpdatedAt));
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

    default long countDistinctDeviceIdByStateIn(Collection<SessionState> states) {
        if (states == null || states.isEmpty()) {
            return 0;
        }
        List<Object> rows = selectObjs(Wrappers.<ShoppingSession>query()
                .select("DISTINCT device_id")
                .in("state", states.stream().map(Enum::name).toList()));
        return rows == null ? 0 : rows.size();
    }

    default long countDistinctDeviceIdByDeviceIdInAndStateIn(
            Collection<String> deviceIds, Collection<SessionState> states) {
        if (deviceIds == null || deviceIds.isEmpty() || states == null || states.isEmpty()) {
            return 0;
        }
        List<Object> rows = selectObjs(Wrappers.<ShoppingSession>query()
                .select("DISTINCT device_id")
                .in("device_id", deviceIds)
                .in("state", states.stream().map(Enum::name).toList()));
        return rows == null ? 0 : rows.size();
    }

    default long countByStateInAndUpdatedAtBefore(Collection<SessionState> states, java.time.Instant cutoff) {
        Long c = selectCount(Wrappers.<ShoppingSession>lambdaQuery()
                .in(ShoppingSession::getState, states)
                .and(w -> w.lt(ShoppingSession::getUpdatedAt, cutoff)
                        .or()
                        .isNull(ShoppingSession::getUpdatedAt).lt(ShoppingSession::getCreatedAt, cutoff)));
        return c == null ? 0 : c;
    }

    default long countByDeviceIdInAndStateInAndUpdatedAtBefore(
            Collection<String> deviceIds, Collection<SessionState> states, java.time.Instant cutoff) {
        if (deviceIds == null || deviceIds.isEmpty()) {
            return 0;
        }
        Long c = selectCount(Wrappers.<ShoppingSession>lambdaQuery()
                .in(ShoppingSession::getDeviceId, deviceIds)
                .in(ShoppingSession::getState, states)
                .and(w -> w.lt(ShoppingSession::getUpdatedAt, cutoff)
                        .or()
                        .isNull(ShoppingSession::getUpdatedAt).lt(ShoppingSession::getCreatedAt, cutoff)));
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

    List<ShoppingSession> findByStateInAndUpdatedAtAfter(
            @Param("states") Collection<SessionState> states,
            @Param("since") java.time.Instant since,
            @Param("limit") int limit);

    default List<ShoppingSession> findByStateInAndUpdatedAtAfter(
            Collection<SessionState> states, java.time.Instant since) {
        return findByStateInAndUpdatedAtAfter(states, since, 5000);
    }

    /** 设备列表占用态：仅拉活跃会话，避免全表 shopping_session。 */
    default List<ShoppingSession> findByStateIn(Collection<SessionState> states, int limit) {
        if (states == null || states.isEmpty()) {
            return List.of();
        }
        int lim = Math.max(1, Math.min(limit, 5000));
        return selectList(Wrappers.<ShoppingSession>lambdaQuery()
                .in(ShoppingSession::getState, states)
                .orderByDesc(ShoppingSession::getUpdatedAt)
                .last("LIMIT " + lim));
    }

    long countCreatedBetween(@Param("start") java.time.Instant start, @Param("end") java.time.Instant end);

    long countCreatedBetweenAndStateIn(
            @Param("start") java.time.Instant start,
            @Param("end") java.time.Instant end,
            @Param("states") Collection<SessionState> states);

    Long avgDoorOpenMsBetween(@Param("start") java.time.Instant start, @Param("end") java.time.Instant end);

    Long p95DoorOpenMsBetween(@Param("start") java.time.Instant start, @Param("end") java.time.Instant end);

    long countCreatedAfterAndStateIn(
            @Param("since") java.time.Instant since,
            @Param("states") Collection<SessionState> states);

    long countCreatedAfterAndStateInForDevices(
            @Param("since") java.time.Instant since,
            @Param("states") Collection<SessionState> states,
            @Param("deviceIds") Collection<String> deviceIds);

    Long avgDoorOpenMsCreatedAfter(@Param("since") java.time.Instant since);

    Long avgDoorOpenMsCreatedAfterForDevices(
            @Param("since") java.time.Instant since,
            @Param("deviceIds") Collection<String> deviceIds);

    /** 运营会话列表筛选：设备范围 + 状态 + 会话/用户/时间 + 关键词。 */
    default Page<ShoppingSession> findByFiltersOrderByCreatedAtDesc(
            String deviceId,
            Collection<String> deviceIds,
            SessionState state,
            String sessionId,
            Long userId,
            java.time.Instant createdFrom,
            java.time.Instant createdTo,
            String keyword,
            Pageable pageable) {
        var mpPage = new com.baomidou.mybatisplus.extension.plugins.pagination.Page<ShoppingSession>(
                pageable.getPageNumber() + 1L, pageable.getPageSize());
        var q = Wrappers.<ShoppingSession>lambdaQuery();
        if (deviceId != null && !deviceId.isBlank()) {
            q.eq(ShoppingSession::getDeviceId, deviceId.trim());
        } else if (deviceIds != null) {
            if (deviceIds.isEmpty()) {
                return new org.springframework.data.domain.PageImpl<>(List.of(), pageable, 0);
            }
            q.in(ShoppingSession::getDeviceId, deviceIds);
        }
        if (state != null) {
            q.eq(ShoppingSession::getState, state);
        }
        if (sessionId != null && !sessionId.isBlank()) {
            q.eq(ShoppingSession::getSessionId, sessionId.trim());
        }
        if (userId != null) {
            q.eq(ShoppingSession::getUserId, userId);
        }
        if (createdFrom != null) {
            q.ge(ShoppingSession::getCreatedAt, createdFrom);
        }
        if (createdTo != null) {
            q.le(ShoppingSession::getCreatedAt, createdTo);
        }
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim();
            Long kwUserId = null;
            try {
                kwUserId = Long.parseLong(kw);
            } catch (NumberFormatException ignored) {
                // not a user id
            }
            Long finalKwUserId = kwUserId;
            q.and(w -> {
                w.like(ShoppingSession::getSessionId, kw)
                        .or().like(ShoppingSession::getDeviceId, kw)
                        .or().like(ShoppingSession::getOrderId, kw);
                if (finalKwUserId != null) {
                    w.or().eq(ShoppingSession::getUserId, finalKwUserId);
                }
            });
        }
        q.orderByDesc(ShoppingSession::getCreatedAt);
        var result = selectPage(mpPage, q);
        return new org.springframework.data.domain.PageImpl<>(result.getRecords(), pageable, result.getTotal());
    }

    default long countByDeviceIdAndCreatedAtAfter(String deviceId, java.time.Instant since) {
        Long c = selectCount(Wrappers.<ShoppingSession>lambdaQuery()
                .eq(ShoppingSession::getDeviceId, deviceId)
                .gt(ShoppingSession::getCreatedAt, since));
        return c == null ? 0 : c;
    }

}
