package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.AdCampaignDevice;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface AdCampaignDeviceMapper extends BaseTradeMapper<AdCampaignDevice> {

    default List<AdCampaignDevice> findByCampaignId(Long campaignId) {
        return selectList(Wrappers.<AdCampaignDevice>lambdaQuery()
                .eq(AdCampaignDevice::getCampaignId, campaignId));
    }

    default void deleteByCampaignId(Long campaignId) {
        delete(Wrappers.<AdCampaignDevice>lambdaQuery()
                .eq(AdCampaignDevice::getCampaignId, campaignId));
    }
}
