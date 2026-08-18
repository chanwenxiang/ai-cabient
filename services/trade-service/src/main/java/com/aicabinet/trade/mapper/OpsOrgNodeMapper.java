package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.OpsOrgNode;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface OpsOrgNodeMapper extends BaseTradeMapper<OpsOrgNode> {

    default List<OpsOrgNode> findAllOrderBySort() {
        return selectList(Wrappers.<OpsOrgNode>lambdaQuery()
                .orderByAsc(OpsOrgNode::getSortOrder)
                .orderByAsc(OpsOrgNode::getNodeId));
    }

    default long countByParentId(Long parentId) {
        Long n = selectCount(Wrappers.<OpsOrgNode>lambdaQuery()
                .eq(OpsOrgNode::getParentId, parentId));
        return n == null ? 0L : n;
    }
}
