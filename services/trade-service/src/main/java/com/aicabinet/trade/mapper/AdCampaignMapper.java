package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.AdCampaign;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.apache.ibatis.annotations.Mapper;

import java.time.Instant;
import java.util.List;

@Mapper
public interface AdCampaignMapper extends BaseTradeMapper<AdCampaign> {

    default List<AdCampaign> findAllOrderByCreatedDesc() {
        return selectList(Wrappers.<AdCampaign>lambdaQuery()
                .orderByDesc(AdCampaign::getCreatedAt));
    }

    default List<AdCampaign> findRunningInWindow(Instant now) {
        return selectList(Wrappers.<AdCampaign>lambdaQuery()
                .eq(AdCampaign::getStatus, "RUNNING")
                .and(w -> w.isNull(AdCampaign::getStartAt).or().le(AdCampaign::getStartAt, now))
                .and(w -> w.isNull(AdCampaign::getEndAt).or().ge(AdCampaign::getEndAt, now))
                .orderByDesc(AdCampaign::getCreatedAt));
    }
}
