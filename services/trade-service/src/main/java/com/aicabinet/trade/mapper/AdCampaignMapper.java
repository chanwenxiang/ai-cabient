package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.AdCampaign;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AdCampaignMapper extends BaseTradeMapper<AdCampaign> {

    default List<AdCampaign> findBySlotIdAndStatus(Long slotId, String status) {
    return selectList(Wrappers.<AdCampaign>lambdaQuery().eq(AdCampaign::getSlotId, slotId).eq(AdCampaign::getStatus, status));
    }

    default List<AdCampaign> findByStatus(String status) {
    return selectList(Wrappers.<AdCampaign>lambdaQuery().eq(AdCampaign::getStatus, status));
    }

        List<AdCampaign> findActiveCampaigns(Long slotId, Instant now);

}
