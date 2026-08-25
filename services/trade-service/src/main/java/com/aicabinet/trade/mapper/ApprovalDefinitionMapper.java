package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.ApprovalDefinition;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ApprovalDefinitionMapper extends BaseTradeMapper<ApprovalDefinition> {

    default java.util.List<ApprovalDefinition> findAllOrderByBizType() {
        return selectList(Wrappers.<ApprovalDefinition>lambdaQuery()
                .orderByAsc(ApprovalDefinition::getBizType));
    }

    default Optional<ApprovalDefinition> findByBizType(String bizType) {
        return selectList(Wrappers.<ApprovalDefinition>lambdaQuery()
                .eq(ApprovalDefinition::getBizType, bizType)
                .last("LIMIT 1")).stream().findFirst();
    }
}
