package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.ApprovalNode;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ApprovalNodeMapper extends BaseTradeMapper<ApprovalNode> {

    default List<ApprovalNode> findByDefIdOrderBySeqAsc(Long defId) {
        return selectList(Wrappers.<ApprovalNode>lambdaQuery()
                .eq(ApprovalNode::getDefId, defId)
                .orderByAsc(ApprovalNode::getSeq));
    }

    default List<ApprovalNode> findByDefIdAndSeq(Long defId, int seq) {
        return selectList(Wrappers.<ApprovalNode>lambdaQuery()
                .eq(ApprovalNode::getDefId, defId)
                .eq(ApprovalNode::getSeq, seq));
    }

    default void deleteByDefId(Long defId) {
        delete(Wrappers.<ApprovalNode>lambdaQuery().eq(ApprovalNode::getDefId, defId));
    }
}
