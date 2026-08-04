package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.PullOffTask;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
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

}
