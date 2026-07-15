package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.AdImpression;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AdImpressionMapper extends BaseTradeMapper<AdImpression> {

    default List<AdImpression> findByCampaignId(Long campaignId) {
    return selectList(Wrappers.<AdImpression>lambdaQuery().eq(AdImpression::getCampaignId, campaignId));
    }

    default List<AdImpression> findBySlotId(Long slotId) {
    return selectList(Wrappers.<AdImpression>lambdaQuery().eq(AdImpression::getSlotId, slotId));
    }

        java.math.BigDecimal sumCostByCampaignId(Long campaignId);

        Long countByCampaignIdAndEventType(Long campaignId, String eventType);

}
