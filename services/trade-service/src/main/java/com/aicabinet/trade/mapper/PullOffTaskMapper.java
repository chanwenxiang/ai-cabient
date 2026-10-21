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
