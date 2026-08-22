package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.AdPlayEvent;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AdPlayEventMapper extends BaseTradeMapper<AdPlayEvent> {

    default long countByCampaignAndType(Long campaignId, String eventType) {
        Long n = selectCount(Wrappers.<AdPlayEvent>lambdaQuery()
                .eq(AdPlayEvent::getCampaignId, campaignId)
                .eq(AdPlayEvent::getEventType, eventType));
        return n == null ? 0 : n;
    }
}
