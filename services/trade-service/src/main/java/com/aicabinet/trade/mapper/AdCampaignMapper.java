package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.AdCampaign;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.Mapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AdCampaignMapper extends BaseTradeMapper<AdCampaign> {

    AdCampaign _findByIdForUpdateRaw(@Param("campaignId") Long campaignId);

    default Optional<AdCampaign> findByIdForUpdate(Long campaignId) {
        return Optional.ofNullable(_findByIdForUpdateRaw(campaignId));
    }

    default List<AdCampaign> findAllOrderByCreatedDesc() {
        return selectList(Wrappers.<AdCampaign>lambdaQuery()
                .orderByDesc(AdCampaign::getCreatedAt));
    }

    /** page 为 0-based。 */
    default Page<AdCampaign> searchPage(int page, int size) {
        return selectPage(new Page<>(page + 1L, size),
                Wrappers.<AdCampaign>lambdaQuery().orderByDesc(AdCampaign::getCreatedAt));
    }

    default List<AdCampaign> findRunningInWindow(Instant now) {
        return selectList(Wrappers.<AdCampaign>lambdaQuery()
                .eq(AdCampaign::getStatus, "RUNNING")
                .and(w -> w.isNull(AdCampaign::getStartAt).or().le(AdCampaign::getStartAt, now))
                .and(w -> w.isNull(AdCampaign::getEndAt).or().ge(AdCampaign::getEndAt, now))
                .orderByDesc(AdCampaign::getCreatedAt));
    }
}
