package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.ApprovalInstance;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ApprovalInstanceMapper extends BaseTradeMapper<ApprovalInstance> {

    ApprovalInstance _findByIdForUpdateRaw(@Param("instanceId") Long instanceId);

    default Optional<ApprovalInstance> findByIdForUpdate(Long instanceId) {
        return Optional.ofNullable(_findByIdForUpdateRaw(instanceId));
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

    default long countByDefId(Long defId) {
        return selectCount(Wrappers.<ApprovalInstance>lambdaQuery()
                .eq(ApprovalInstance::getDefId, defId));
    }
}
