package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.AdCampaignItem;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface AdCampaignItemMapper extends BaseTradeMapper<AdCampaignItem> {

    default List<AdCampaignItem> findByCampaignId(Long campaignId) {
        return selectList(Wrappers.<AdCampaignItem>lambdaQuery()
                .eq(AdCampaignItem::getCampaignId, campaignId)
                .orderByAsc(AdCampaignItem::getSortOrder));
    }

    default void deleteByCampaignId(Long campaignId) {
        delete(Wrappers.<AdCampaignItem>lambdaQuery()
                .eq(AdCampaignItem::getCampaignId, campaignId));
    }

    default long countByAssetId(Long assetId) {
        Long n = selectCount(Wrappers.<AdCampaignItem>lambdaQuery()
                .eq(AdCampaignItem::getAssetId, assetId));
        return n == null ? 0L : n;
    }
}
