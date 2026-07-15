package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.GroupBuyParticipant;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface GroupBuyParticipantMapper extends BaseTradeMapper<GroupBuyParticipant> {

    default List<GroupBuyParticipant> findByGroupBuyId(Long groupBuyId) {
    return selectList(Wrappers.<GroupBuyParticipant>lambdaQuery().eq(GroupBuyParticipant::getGroupBuyId, groupBuyId));
    }

    default List<GroupBuyParticipant> findByUserId(Long userId) {
    return selectList(Wrappers.<GroupBuyParticipant>lambdaQuery().eq(GroupBuyParticipant::getUserId, userId));
    }

}
