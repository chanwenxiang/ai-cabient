package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.CompensationTask;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface CompensationTaskMapper extends BaseTradeMapper<CompensationTask> {

    default List<CompensationTask> findByStatusAndScheduledAtBefore(String status, Instant scheduledAt) {
    return selectList(Wrappers.<CompensationTask>lambdaQuery().eq(CompensationTask::getStatus, status).lt(CompensationTask::getScheduledAt, scheduledAt));
    }

    default boolean existsPending(String txId, String taskType) {
        if (txId == null || txId.isBlank() || taskType == null || taskType.isBlank()) {
            return false;
        }
        Long count = selectCount(Wrappers.<CompensationTask>lambdaQuery()
                .eq(CompensationTask::getTxId, txId)
                .eq(CompensationTask::getTaskType, taskType)
                .eq(CompensationTask::getStatus, "PENDING"));
        return count != null && count > 0;
    }

        List<CompensationTask> findExecutableTasks(Instant now);

}
