package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.ApprovalInstance;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ApprovalInstanceMapper extends BaseTradeMapper<ApprovalInstance> {

    ApprovalInstance findByIdForUpdateRaw(@Param("instanceId") Long instanceId);

    default Optional<ApprovalInstance> findByIdForUpdate(Long instanceId) {
        return Optional.ofNullable(findByIdForUpdateRaw(instanceId));
    }

    default Optional<ApprovalInstance> findByBizTypeAndBizId(String bizType, String bizId) {
        return selectList(Wrappers.<ApprovalInstance>lambdaQuery()
                .eq(ApprovalInstance::getBizType, bizType)
                .eq(ApprovalInstance::getBizId, bizId)
                .last("LIMIT 1")).stream().findFirst();
    }

    default Optional<ApprovalInstance> findPendingByBizTypeAndBizId(String bizType, String bizId) {
        return selectList(Wrappers.<ApprovalInstance>lambdaQuery()
                .eq(ApprovalInstance::getBizType, bizType)
                .eq(ApprovalInstance::getBizId, bizId)
                .eq(ApprovalInstance::getStatus, "PENDING")
                .last("LIMIT 1")).stream().findFirst();
    }

    default List<ApprovalInstance> findPendingByBizTypeAndBizIds(String bizType, Collection<String> bizIds) {
        if (bizType == null || bizType.isBlank() || bizIds == null || bizIds.isEmpty()) {
            return List.of();
        }
        return selectList(Wrappers.<ApprovalInstance>lambdaQuery()
                .eq(ApprovalInstance::getBizType, bizType.trim())
                .in(ApprovalInstance::getBizId, bizIds)
                .eq(ApprovalInstance::getStatus, "PENDING"));
    }

    default long countByDefId(Long defId) {
        return selectCount(Wrappers.<ApprovalInstance>lambdaQuery()
                .eq(ApprovalInstance::getDefId, defId));
    }
}
