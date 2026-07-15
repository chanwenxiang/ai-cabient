package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.ShareReward;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.springframework.stereotype.Repository;

@Mapper
public interface ShareRewardMapper extends BaseTradeMapper<ShareReward> {

    default List<ShareReward> findBySharerId(Long sharerId) {
    return selectList(Wrappers.<ShareReward>lambdaQuery().eq(ShareReward::getSharerId, sharerId));
    }

    default List<ShareReward> findByInviteeId(Long inviteeId) {
    return selectList(Wrappers.<ShareReward>lambdaQuery().eq(ShareReward::getInviteeId, inviteeId));
    }

    default List<ShareReward> findByStatus(String status) {
    return selectList(Wrappers.<ShareReward>lambdaQuery().eq(ShareReward::getStatus, status));
    }

}
