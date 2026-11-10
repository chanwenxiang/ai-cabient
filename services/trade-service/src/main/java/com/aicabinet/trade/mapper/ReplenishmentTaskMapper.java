package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.ReplenishmentTask;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ReplenishmentTaskMapper extends BaseTradeMapper<ReplenishmentTask> {

    ReplenishmentTask findByIdForUpdateRaw(@Param("taskId") Long taskId);

    default Optional<ReplenishmentTask> findByIdForUpdate(Long taskId) {
        return Optional.ofNullable(findByIdForUpdateRaw(taskId));
    }

    default List<ReplenishmentTask> findByRouteId(Long routeId) {
    return selectList(
        Wrappers.<ReplenishmentTask>lambdaQuery()
            .eq(ReplenishmentTask::getRouteId, routeId)
            .orderByAsc(ReplenishmentTask::getTaskId));
    }

    default List<ReplenishmentTask> findByOutboundId(Long outboundId) {
    return selectList(Wrappers.<ReplenishmentTask>lambdaQuery().eq(ReplenishmentTask::getOutboundId, outboundId));
    }

    default List<ReplenishmentTask> findByDeviceIdAndStatusIn(String deviceId, List<String> statuses) {
    return selectList(Wrappers.<ReplenishmentTask>lambdaQuery().eq(ReplenishmentTask::getDeviceId, deviceId).in(ReplenishmentTask::getStatus, statuses));
    }

    default List<ReplenishmentTask> findByAssigneeUserIdAndStatus(Long assigneeUserId, String status) {
    return selectList(Wrappers.<ReplenishmentTask>lambdaQuery().eq(ReplenishmentTask::getAssigneeUserId, assigneeUserId).eq(ReplenishmentTask::getStatus, status));
    }

    default List<ReplenishmentTask> findByAssigneeUserIdAndStatusIn(Long assigneeUserId, List<String> statuses) {
    return selectList(Wrappers.<ReplenishmentTask>lambdaQuery().eq(ReplenishmentTask::getAssigneeUserId, assigneeUserId).in(ReplenishmentTask::getStatus, statuses));
    }

    default List<ReplenishmentTask> findByAssigneeUserIdAndCreatedAtSince(Long assigneeUserId, Instant since) {
        return selectList(Wrappers.<ReplenishmentTask>lambdaQuery()
                .eq(ReplenishmentTask::getAssigneeUserId, assigneeUserId)
                .ge(ReplenishmentTask::getCreatedAt, since)
                .orderByAsc(ReplenishmentTask::getTaskId));
    }

    /** 自主补货超时重试去重（2026-10-20 接口层审计 H2）：按设备+notes+时间窗查近期任务。 */
    default List<ReplenishmentTask> findByDeviceIdAndNotesAndCreatedAtSince(String deviceId, String notes, Instant since) {
        return selectList(Wrappers.<ReplenishmentTask>lambdaQuery()
                .eq(ReplenishmentTask::getDeviceId, deviceId)
                .eq(ReplenishmentTask::getNotes, notes)
                .ge(ReplenishmentTask::getCreatedAt, since)
                .orderByDesc(ReplenishmentTask::getTaskId));
    }

    /** 自主补货 clientRequestNo 幂等重放（V401）：按键查原任务，命中即返回不重复出库。 */
    default Optional<ReplenishmentTask> findByClientRequestNo(String clientRequestNo) {
        return Optional.ofNullable(selectOne(Wrappers.<ReplenishmentTask>lambdaQuery()
                .eq(ReplenishmentTask::getClientRequestNo, clientRequestNo)));
    }

    default List<ReplenishmentTask> findByStatusIn(List<String> statuses) {
    return selectList(Wrappers.<ReplenishmentTask>lambdaQuery().in(ReplenishmentTask::getStatus, statuses));
    }

    default long countByStatusIn(List<String> statuses) {
    Long c = selectCount(Wrappers.<ReplenishmentTask>lambdaQuery().in(ReplenishmentTask::getStatus, statuses));
    return c == null ? 0 : c;
    }

    default List<ReplenishmentTask> findTop10ByStatusInOrderByCreatedAtAsc(List<String> statuses) {
    return selectList(Wrappers.<ReplenishmentTask>lambdaQuery().in(ReplenishmentTask::getStatus, statuses).orderByAsc(ReplenishmentTask::getCreatedAt).last("LIMIT 10"));
    }

    /**
     * 「此刻正在补货」的任务（status=IN_PROGRESS 且 check_in_at 非空），**判据下推到 SQL**。
     *
     * <p>为什么不能写成「先 {@code findByStatusInOrderByCreatedAtAsc(…, 500)} 再内存过滤」：
     * 出库明细生成那一步就会把任务置成 IN_PROGRESS（当时还没到店、check_in_at 为空），
     * 而 4h 超时收口只扫**已签到**的行（{@link #findByStatusAndCheckInAtBefore}），
     * 所以这类没签到的行不会自愈、只会越积越多。超过 500 条后新签到的任务被 LIMIT 挤出窗口，
     * 展示面的「补货中」就永远不亮（而挡开门的判据是逐柜查的，两边结论相反）。
     *
     * <p>这里**不设上限**：真正签到的在途任务是个位数量级，不该被历史脏行挤掉窗口；
     * 只取三列，避免把整行实体带出来。判据单源仍是 {@link ReplenishmentTask#isRestockingNow}，
     * 调用方拿到结果后再过一遍它（SQL 只是预筛，防的是窗口截断）。
     */
    default List<ReplenishmentTask> findRestockingNowTasks() {
        return selectList(Wrappers.<ReplenishmentTask>lambdaQuery()
                .select(ReplenishmentTask::getDeviceId, ReplenishmentTask::getStatus, ReplenishmentTask::getCheckInAt)
                .eq(ReplenishmentTask::getStatus, "IN_PROGRESS")
                .isNotNull(ReplenishmentTask::getCheckInAt));
    }

    /**
     * 工作台「补货待办」：状态与**设备范围一起下推到 SQL**。
     *
     * <p>为什么不能「先取 {@code limit} 条再按设备过滤」：窗口是按 createdAt **全局**取的，
     * 域受限账号（分仓 / 线长）自己的任务可能排在窗口之外 —— 过完滤一条不剩，
     * 待办面板显示「没有待办」，而任务实实在在挂在那里（与 B13 同族：上限与判据无关时，
     * 上限不能设在过滤之前）。
     *
     * @param deviceIds {@code null}=不限范围；空集合=范围内没有任何柜机（直接空结果，
     *                  也避免生成 MyBatis-Plus 的 {@code IN ()} 空列表语法）
     */
    default List<ReplenishmentTask> findWorkbenchTasks(
            List<String> statuses, Collection<String> deviceIds, int limit) {
        if (deviceIds != null && deviceIds.isEmpty()) {
            return List.of();
        }
        var query = Wrappers.<ReplenishmentTask>lambdaQuery()
                .in(ReplenishmentTask::getStatus, statuses)
                .orderByAsc(ReplenishmentTask::getCreatedAt);
        if (deviceIds != null) {
            query.in(ReplenishmentTask::getDeviceId, deviceIds);
        }
        return selectList(query.last("LIMIT " + Math.max(1, Math.min(limit, 500))));
    }

    /**
     * 「此刻正在补货」的设备集合（{@code IN_PROGRESS} 且已签到），设备范围可下推。
     *
     * <p>判据与 {@link ReplenishmentTask#isRestockingNow} **同源**：出库明细生成即置 IN_PROGRESS 但人还没到店，
     * 那种行不该算「有人占着柜子」（挡开门用的就是这条判据）。设备范围下推 + 只取 distinct device_id、
     * **不设窗口**：旧写法「取 500 条再按范围过滤」在域受限账号下会把别家的任务全过一遍、自己的反被窗口挤掉。
     *
     * @param deviceIds {@code null}=不限范围；空集合=范围内没有任何柜机
     */
    default Set<String> findRestockingNowDeviceIds(Collection<String> deviceIds) {
        if (deviceIds != null && deviceIds.isEmpty()) {
            return Set.of();
        }
        var query = Wrappers.<ReplenishmentTask>lambdaQuery()
                .select(ReplenishmentTask::getDeviceId)
                .eq(ReplenishmentTask::getStatus, "IN_PROGRESS")
                .isNotNull(ReplenishmentTask::getCheckInAt);
        if (deviceIds != null) {
            query.in(ReplenishmentTask::getDeviceId, deviceIds);
        }
        return selectList(query).stream()
                .map(ReplenishmentTask::getDeviceId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    default long countByStatusInAndDeviceIdIn(List<String> statuses, Collection<String> deviceIds) {
        if (deviceIds == null || deviceIds.isEmpty()) {
            return 0;
        }
        Long c = selectCount(Wrappers.<ReplenishmentTask>lambdaQuery()
                .in(ReplenishmentTask::getStatus, statuses)
                .in(ReplenishmentTask::getDeviceId, deviceIds));
        return c == null ? 0 : c;
    }

    /** 已签到且仍进行中、签到时间早于截止：超时收口防占柜。 */
    default List<ReplenishmentTask> findByStatusAndCheckInAtBefore(String status, Instant cutoff, int limit) {
        int lim = Math.max(1, Math.min(limit, 500));
        return selectList(Wrappers.<ReplenishmentTask>lambdaQuery()
                .eq(ReplenishmentTask::getStatus, status)
                .isNotNull(ReplenishmentTask::getCheckInAt)
                .lt(ReplenishmentTask::getCheckInAt, cutoff)
                .orderByAsc(ReplenishmentTask::getCheckInAt)
                .last("LIMIT " + lim));
    }

    Instant selectLastCompletedAtByDeviceId(@Param("deviceId") String deviceId);

    default Optional<Instant> findLastCompletedAtByDeviceId(String deviceId) {
        return Optional.ofNullable(selectLastCompletedAtByDeviceId(deviceId));
    }

    default List<ReplenishmentTask> findByCreatedAtAfter(Instant since) {
        return selectList(Wrappers.<ReplenishmentTask>lambdaQuery()
                .ge(ReplenishmentTask::getCreatedAt, since));
    }

    default List<ReplenishmentTask> findByCompletedAtAfter(Instant since) {
        return selectList(Wrappers.<ReplenishmentTask>lambdaQuery()
                .isNotNull(ReplenishmentTask::getCompletedAt)
                .ge(ReplenishmentTask::getCompletedAt, since));
    }

    /** page 为 0-based。 */
    default Page<ReplenishmentTask> searchPage(String deviceId, String status, int page, int size) {
        var query = Wrappers.<ReplenishmentTask>lambdaQuery().orderByAsc(ReplenishmentTask::getTaskId);
        if (deviceId != null && !deviceId.isBlank()) {
            query.eq(ReplenishmentTask::getDeviceId, deviceId.trim());
        }
        if (status != null && !status.isBlank()) {
            query.eq(ReplenishmentTask::getStatus, status.trim().toUpperCase());
        }
        return selectPage(new Page<>(page + 1L, size), query);
    }

    default java.util.List<Long> findDistinctRouteIdsByDeviceId(String deviceId) {
        if (deviceId == null || deviceId.isBlank()) {
            return java.util.List.of();
        }
        return selectList(Wrappers.<ReplenishmentTask>lambdaQuery()
                        .eq(ReplenishmentTask::getDeviceId, deviceId.trim())
                        .select(ReplenishmentTask::getRouteId))
                .stream()
                .map(ReplenishmentTask::getRouteId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
    }

}
