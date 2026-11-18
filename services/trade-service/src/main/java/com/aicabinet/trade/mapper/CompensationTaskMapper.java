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

    /** F5：领取必须带状态谓词——扫描拿的是快照，两个实例会同时处理同一条任务。 */
    default int claimIfPending(Long taskId) {
        if (taskId == null) {
            return 0;
        }
        return update(null, Wrappers.<CompensationTask>lambdaUpdate()
                .eq(CompensationTask::getTaskId, taskId)
                .eq(CompensationTask::getStatus, "PENDING")
                .set(CompensationTask::getStatus, "PROCESSING"));
    }

    /** F5：收尾只认 PROCESSING，0 行说明已被别的实例推进/终结，禁止覆盖。 */
    default int finishIfProcessing(Long taskId, String status, String result, Instant executedAt) {
        if (taskId == null) {
            return 0;
        }
        return update(null, Wrappers.<CompensationTask>lambdaUpdate()
                .eq(CompensationTask::getTaskId, taskId)
                .eq(CompensationTask::getStatus, "PROCESSING")
                .set(CompensationTask::getStatus, status)
                .set(CompensationTask::getResult, result)
                .set(CompensationTask::getExecutedAt, executedAt));
    }

    /** F5：退避重排同样只认 PROCESSING。 */
    default int rescheduleIfProcessing(Long taskId, Instant scheduledAt, String result) {
        if (taskId == null) {
            return 0;
        }
        return update(null, Wrappers.<CompensationTask>lambdaUpdate()
                .eq(CompensationTask::getTaskId, taskId)
                .eq(CompensationTask::getStatus, "PROCESSING")
                .set(CompensationTask::getStatus, "PENDING")
                .set(CompensationTask::getScheduledAt, scheduledAt)
                .set(CompensationTask::getResult, result));
    }

        List<CompensationTask> findExecutableTasks(Instant now);

}
