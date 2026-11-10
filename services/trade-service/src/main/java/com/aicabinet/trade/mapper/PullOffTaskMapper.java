package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.PullOffTask;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface PullOffTaskMapper extends BaseTradeMapper<PullOffTask> {

    /**
     * 写路径行锁读（MA-103）。{@code ReplenishmentService.createTaskFromPullOff} 原来是
     * {@code findById → 判 OPEN → 建路线+任务+RESTOCK 行 → 置 RESOLVED}，
     * 两个并发请求都读到 OPEN ⇒ 同一条临期告警**双建补货任务**（重复出勤、重复扣货）。
     * 行锁后第二个事务醒来已是 RESOLVED ⇒ 走既有「该临期告警已处理」409。
     */
    default Optional<PullOffTask> findByIdForUpdate(Long taskId) {
        return Optional.ofNullable(selectOne(
                Wrappers.<PullOffTask>lambdaQuery()
                        .eq(PullOffTask::getTaskId, taskId)
                        .last("FOR UPDATE")));
    }

    default long countByStatus(String status) {
    Long c = selectCount(Wrappers.<PullOffTask>lambdaQuery().eq(PullOffTask::getStatus, status));
    return c == null ? 0 : c;
    }

    default long countByStatusAndDeviceIdIn(String status, Collection<String> deviceIds) {
    Long c = selectCount(Wrappers.<PullOffTask>lambdaQuery().eq(PullOffTask::getStatus, status).in(PullOffTask::getDeviceId, deviceIds));
    return c == null ? 0 : c;
    }

    default List<PullOffTask> findByStatusOrderByCreatedAtDesc(String status) {
        return findByStatusOrderByCreatedAtDesc(status, 500);
    }

    default List<PullOffTask> findByStatusOrderByCreatedAtDesc(String status, int limit) {
        int lim = Math.max(1, Math.min(limit, 500));
        return selectList(Wrappers.<PullOffTask>lambdaQuery()
                .eq(PullOffTask::getStatus, status)
                .orderByDesc(PullOffTask::getCreatedAt)
                .last("LIMIT " + lim));
    }

    /**
     * 工作台「临期/过期下架」待办：**设备范围下推**，`LIMIT` 落在过滤之后。
     * 旧写法「取全局最近 N 条 OPEN 再内存过滤 deviceId」在域受限账号下会把自家任务挤掉
     * （B13 同族）；计数 {@link #countByStatusAndDeviceIdIn} 早已带范围，两边口径原本相反。
     */
    default List<PullOffTask> findByStatusAndDeviceIdInOrderByCreatedAtDesc(
            String status, Collection<String> deviceIds, int limit) {
        int lim = Math.max(1, Math.min(limit, 500));
        if (deviceIds == null) {
            return findByStatusOrderByCreatedAtDesc(status, lim);
        }
        if (deviceIds.isEmpty()) {
            return List.of();
        }
        return selectList(Wrappers.<PullOffTask>lambdaQuery()
                .eq(PullOffTask::getStatus, status)
                .in(PullOffTask::getDeviceId, deviceIds)
                .orderByDesc(PullOffTask::getCreatedAt)
                .last("LIMIT " + lim));
    }

    default Optional<PullOffTask> findByLotIdAndStatus(String lotId, String status) {
    return Optional.ofNullable(selectOne(Wrappers.<PullOffTask>lambdaQuery().eq(PullOffTask::getLotId, lotId).eq(PullOffTask::getStatus, status)));
    }

    /**
     * page 为 0-based。deviceId 非空则服务端过滤——运营从设备详情下钻时必须服务端筛，
     * 前端 filter 会让分页 total 失真（显示 N 条 / 共 M 条、翻页大面积空）。
     */
    default Page<PullOffTask> searchPage(String status, String deviceId, int page, int size) {
        String st = status != null && !status.isBlank() ? status.trim().toUpperCase() : "OPEN";
        String dev = deviceId != null && !deviceId.isBlank() ? deviceId.trim() : null;
        return selectPage(new Page<>(page + 1L, size),
                Wrappers.<PullOffTask>lambdaQuery()
                        .eq(PullOffTask::getStatus, st)
                        .eq(dev != null, PullOffTask::getDeviceId, dev)
                        .orderByDesc(PullOffTask::getCreatedAt));
    }

}
