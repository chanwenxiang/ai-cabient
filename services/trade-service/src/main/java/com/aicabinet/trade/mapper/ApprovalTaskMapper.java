package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.ApprovalTask;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ApprovalTaskMapper extends BaseTradeMapper<ApprovalTask> {

    ApprovalTask findByIdForUpdateRaw(@Param("taskId") Long taskId);

    default java.util.Optional<ApprovalTask> findByIdForUpdate(Long taskId) {
        return java.util.Optional.ofNullable(findByIdForUpdateRaw(taskId));
    }

    default List<ApprovalTask> findPendingByAssigneeUserId(Long userId, int limit) {
        return selectList(Wrappers.<ApprovalTask>lambdaQuery()
                .eq(ApprovalTask::getAssigneeUserId, userId)
                .eq(ApprovalTask::getStatus, "PENDING")
                .orderByAsc(ApprovalTask::getCreatedAt)
                .last("LIMIT " + Math.max(1, Math.min(limit, 100))));
    }

    default long countPendingByAssigneeUserId(Long userId) {
        Long c = selectCount(Wrappers.<ApprovalTask>lambdaQuery()
                .eq(ApprovalTask::getAssigneeUserId, userId)
                .eq(ApprovalTask::getStatus, "PENDING"));
        return c == null ? 0L : c;
    }

    default List<ApprovalTask> findByInstanceIdAndNodeSeq(Long instanceId, int nodeSeq) {
        return selectList(Wrappers.<ApprovalTask>lambdaQuery()
                .eq(ApprovalTask::getInstanceId, instanceId)
                .eq(ApprovalTask::getNodeSeq, nodeSeq));
    }
}
