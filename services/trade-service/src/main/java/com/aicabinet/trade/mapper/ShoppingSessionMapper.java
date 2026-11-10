package com.aicabinet.trade.mapper;

import com.aicabinet.common.enums.SessionState;
import com.aicabinet.trade.domain.ShoppingSession;
import com.aicabinet.trade.support.SessionKind;
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

    ShoppingSession findByIdForUpdateRaw(@Param("sessionId") String sessionId);

    /**
     * 显式清空失败原因（R2-D3）。{@code setFailReason(null) + save()} = updateById 跳 null ⇒
     * 重试成功/人工结案后的 COMPLETED 会话 DTO 仍带上一轮的失败文案（读面自相矛盾）。
     * 同 9d0f31b7 对 {@code sku_jiangyi_link.reject_cause} 的处理范式。
     */
    default void clearFailReason(String sessionId) {
        update(null, Wrappers.<ShoppingSession>lambdaUpdate()
                .eq(ShoppingSession::getSessionId, sessionId)
                .set(ShoppingSession::getFailReason, null));
    }

    default Optional<ShoppingSession> findByIdForUpdate(String sessionId) {
        return Optional.ofNullable(findByIdForUpdateRaw(sessionId));
    }

    default Optional<ShoppingSession> findByIdempotencyKey(String idempotencyKey) {
    return Optional.ofNullable(selectOne(Wrappers.<ShoppingSession>lambdaQuery().eq(ShoppingSession::getIdempotencyKey, idempotencyKey)));
    }

    default Optional<ShoppingSession> findFirstByUserIdAndStateInOrderByCreatedAtDesc( Long userId, Collection<SessionState> states) {
    return Optional.ofNullable(selectOne(Wrappers.<ShoppingSession>lambdaQuery().eq(ShoppingSession::getUserId, userId).in(ShoppingSession::getState, states).orderByDesc(ShoppingSession::getCreatedAt).last("LIMIT 1")));
    }

    /** 设备最近一次会话（任意状态，含已完结）——运营后台「最近会话」展示用。 */
    default Optional<ShoppingSession> findFirstByDeviceIdOrderByCreatedAtDesc(String deviceId) {
    return Optional.ofNullable(selectOne(Wrappers.<ShoppingSession>lambdaQuery().eq(ShoppingSession::getDeviceId, deviceId).orderByDesc(ShoppingSession::getCreatedAt).last("LIMIT 1")));
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

    /**
     * 运营工作台「购物会话可能超时」：**设备范围下推**，`LIMIT` 落在过滤之后（B13 同族）。
     *
     * <p>🔴 超时判据必须与 {@link #countByDeviceIdInAndStateInAndUpdatedAtBefore} 逐字一致：
     * {@code updated_at < cutoff OR (updated_at IS NULL AND created_at < cutoff)}。只加
     * {@code device_id IN} 而简化掉后半段，会让「从没更新过 updated_at」的历史会话
     * 在列表里消失、却还算在计数里 —— 数字与列表又对不上。
     */
    default List<ShoppingSession> findByStateInAndDeviceIdInAndUpdatedAtBefore(
            Collection<SessionState> states, Collection<String> deviceIds,
            java.time.Instant cutoff, int limit) {
        int lim = Math.max(1, Math.min(limit, 1000));
        if (deviceIds == null) {
            return findByStateInAndUpdatedAtBefore(states, cutoff, lim);
        }
        if (deviceIds.isEmpty()) {
            return List.of();
        }
        return selectList(Wrappers.<ShoppingSession>lambdaQuery()
                .in(ShoppingSession::getState, states)
                .in(ShoppingSession::getDeviceId, deviceIds)
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

    /**
     * 该设备最新的活跃补货/盘点会话（将邑补货关门上报 uploadAddGoodsCloseStatus 按设备推进用，
     * 2026-10-27：上报 body 无会话号）。补货会话 = 绑定补货任务，或历史 RESTOCK: 幂等键；
     * 盘点开门会话（STOCKTAKE:，2026-10-28）走同一 sendOpenStockDoor 指令 ⇒ 关门上报
     * 同样到达本入口，须一并推进，否则盘点会话永占柜。
     */
    default Optional<ShoppingSession> findLatestActiveRestockByDeviceId(String deviceId,
                                                                       Collection<SessionState> states) {
        if (deviceId == null || deviceId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(selectOne(Wrappers.<ShoppingSession>lambdaQuery()
                .eq(ShoppingSession::getDeviceId, deviceId)
                .and(w -> w.isNotNull(ShoppingSession::getReplenishmentTaskId)
                        .or().likeRight(ShoppingSession::getIdempotencyKey, "RESTOCK:")
                        .or().likeRight(ShoppingSession::getIdempotencyKey, "STOCKTAKE:"))
                .in(ShoppingSession::getState, states)
                .orderByDesc(ShoppingSession::getCreatedAt)
                .last("LIMIT 1")));
    }

    /** 补货任务关联的全部开门会话（任意状态；补货视频查看按 会话id=补货单号 查台账） */
    default List<ShoppingSession> findByReplenishmentTaskId(Long taskId) {
        return selectList(Wrappers.<ShoppingSession>lambdaQuery()
                .eq(ShoppingSession::getReplenishmentTaskId, taskId)
                .orderByDesc(ShoppingSession::getCreatedAt));
    }

    /** 是否存在绑定该补货任务的开门会话（任意状态，用于「是否已下发开门」门禁） */
    default boolean existsByReplenishmentTaskId(Long taskId) {
        if (taskId == null) {
            return false;
        }
        Long count = selectCount(Wrappers.<ShoppingSession>lambdaQuery()
                .eq(ShoppingSession::getReplenishmentTaskId, taskId)
                .last("LIMIT 1"));
        return count != null && count > 0;
    }

    /** 取该补货任务最近一次开门会话（含已关闭），供商户端同步「已开门」UI */
    default Optional<ShoppingSession> findLatestByReplenishmentTaskId(Long taskId) {
        if (taskId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(selectOne(Wrappers.<ShoppingSession>lambdaQuery()
                .eq(ShoppingSession::getReplenishmentTaskId, taskId)
                .orderByDesc(ShoppingSession::getCreatedAt)
                .last("LIMIT 1")));
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

    /** 运营会话列表筛选：设备范围 + 状态 + 会话/用户/时间 + 关键词 + 上传状态/滞留。 */
    record SessionFilterCriteria(
            String deviceId,
            Collection<String> deviceIds,
            SessionState state,
            /** 多状态（如滞留仅活跃态）；与 {@code state} 互斥，优先 {@code state}。 */
            Collection<SessionState> states,
            String sessionId,
            Long userId,
            java.time.Instant createdFrom,
            java.time.Instant createdTo,
            String keyword,
            java.time.Instant updatedBefore,
            /** 会话类型（CONSUMER/RESTOCK/OPS/STOCKTAKE）；空=不筛。口径与 `DeviceValidationService.sessionKind` 同源。 */
            String sessionKind) {}

    /** 运营会话列表：按更新时间倒序（同刻回落创建时间），与页面「更新时间」列及排序按钮一致。 */
    default Page<ShoppingSession> findByFiltersOrderByUpdatedAtDesc(
            SessionFilterCriteria criteria, Pageable pageable) {
        var mpPage = new com.baomidou.mybatisplus.extension.plugins.pagination.Page<ShoppingSession>(
                pageable.getPageNumber() + 1L, pageable.getPageSize());
        var q = Wrappers.<ShoppingSession>lambdaQuery();
        if (!applySessionDeviceScope(q, criteria.deviceId(), criteria.deviceIds())) {
            return new org.springframework.data.domain.PageImpl<>(List.of(), pageable, 0);
        }
        applySessionExactFilters(q, criteria);
        applySessionKeywordFilter(q, criteria.keyword());
        q.orderByDesc(ShoppingSession::getUpdatedAt).orderByDesc(ShoppingSession::getCreatedAt);
        var result = selectPage(mpPage, q);
        return new org.springframework.data.domain.PageImpl<>(result.getRecords(), pageable, result.getTotal());
    }

    private static boolean applySessionDeviceScope(
            com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ShoppingSession> q,
            String deviceId, Collection<String> deviceIds) {
        if (deviceId != null && !deviceId.isBlank()) {
            q.eq(ShoppingSession::getDeviceId, deviceId.trim());
            return true;
        }
        if (deviceIds == null) {
            return true;
        }
        if (deviceIds.isEmpty()) {
            return false;
        }
        q.in(ShoppingSession::getDeviceId, deviceIds);
        return true;
    }

    private static void applySessionExactFilters(
            com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ShoppingSession> q,
            SessionFilterCriteria criteria) {
        if (criteria.state() != null) {
            q.eq(ShoppingSession::getState, criteria.state());
        } else if (criteria.states() != null && !criteria.states().isEmpty()) {
            q.in(ShoppingSession::getState, criteria.states());
        }
        if (criteria.sessionId() != null && !criteria.sessionId().isBlank()) {
            q.eq(ShoppingSession::getSessionId, criteria.sessionId().trim());
        }
        if (criteria.userId() != null) {
            q.eq(ShoppingSession::getUserId, criteria.userId());
        }
        if (criteria.createdFrom() != null) {
            q.ge(ShoppingSession::getCreatedAt, criteria.createdFrom());
        }
        if (criteria.createdTo() != null) {
            q.le(ShoppingSession::getCreatedAt, criteria.createdTo());
        }
        if (criteria.updatedBefore() != null) {
            q.le(ShoppingSession::getUpdatedAt, criteria.updatedBefore());
        }
        applySessionKindFilter(q, criteria.sessionKind());
    }

    /**
     * 运营列表「类型」筛选（MA-007）：条件必须与 {@code DeviceValidationService.sessionKind} 的
     * **判定顺序**等价——OPS &gt; RESTOCK &gt; STOCKTAKE &gt; CONSUMER，前缀字面量取自
     * {@link SessionKind}（同源，铁律 16）。
     *
     * <p>🔴 取反一律写成「{@code key IS NULL OR key NOT LIKE ...}」：SQL 里 {@code NOT LIKE} 对
     * NULL 求值为 NULL，直接 {@code notLikeRight} 会把幂等键为空的历史消费者会话整批判掉。
     */
    private static void applySessionKindFilter(
            com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ShoppingSession> q,
            String kind) {
        if (kind == null || kind.isBlank()) {
            return;
        }
        switch (kind) {
            case SessionKind.OPS -> q.likeRight(ShoppingSession::getIdempotencyKey, SessionKind.OPS_REMOTE_KEY_PREFIX);
            case SessionKind.RESTOCK -> {
                notKeyPrefix(q, SessionKind.OPS_REMOTE_KEY_PREFIX);
                q.and(w -> w.isNotNull(ShoppingSession::getReplenishmentTaskId)
                        .or()
                        .likeRight(ShoppingSession::getIdempotencyKey, SessionKind.RESTOCK_KEY_PREFIX));
            }
            case SessionKind.STOCKTAKE -> {
                notKeyPrefix(q, SessionKind.OPS_REMOTE_KEY_PREFIX);
                q.isNull(ShoppingSession::getReplenishmentTaskId);
                notKeyPrefix(q, SessionKind.RESTOCK_KEY_PREFIX);
                q.likeRight(ShoppingSession::getIdempotencyKey, SessionKind.STOCKTAKE_KEY_PREFIX);
            }
            case SessionKind.CONSUMER -> {
                notKeyPrefix(q, SessionKind.OPS_REMOTE_KEY_PREFIX);
                q.isNull(ShoppingSession::getReplenishmentTaskId);
                notKeyPrefix(q, SessionKind.RESTOCK_KEY_PREFIX);
                notKeyPrefix(q, SessionKind.STOCKTAKE_KEY_PREFIX);
            }
            default -> throw new IllegalArgumentException("unknown session kind: " + kind);
        }
    }

    private static void notKeyPrefix(
            com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ShoppingSession> q, String prefix) {
        q.and(w -> w.isNull(ShoppingSession::getIdempotencyKey)
                .or()
                .notLikeRight(ShoppingSession::getIdempotencyKey, prefix));
    }

    private static void applySessionKeywordFilter(
            com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ShoppingSession> q,
            String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return;
        }
        String kw = keyword.trim();
        Long kwUserId = parseOptionalUserId(kw);
        Long finalKwUserId = kwUserId;
        q.and(w -> {
            w.like(ShoppingSession::getSessionId, kw)
                    .or().like(ShoppingSession::getDeviceId, kw)
                    .or().like(ShoppingSession::getDeviceName, kw)
                    .or().like(ShoppingSession::getOrderId, kw);
            if (finalKwUserId != null) {
                w.or().eq(ShoppingSession::getUserId, finalKwUserId);
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

    default long countByDeviceIdAndCreatedAtAfter(String deviceId, java.time.Instant since) {
        Long c = selectCount(Wrappers.<ShoppingSession>lambdaQuery()
                .eq(ShoppingSession::getDeviceId, deviceId)
                .gt(ShoppingSession::getCreatedAt, since));
        return c == null ? 0 : c;
    }

}
