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

    AdCampaign findByIdForUpdateRaw(@Param("campaignId") Long campaignId);

    default Optional<AdCampaign> findByIdForUpdate(Long campaignId) {
        return Optional.ofNullable(findByIdForUpdateRaw(campaignId));
    }

    default List<AdCampaign> findAllOrderByCreatedDesc() {
        return selectList(Wrappers.<AdCampaign>lambdaQuery()
                .orderByDesc(AdCampaign::getCreatedAt));
    }

    /** page 为 0-based。 */
    default Page<AdCampaign> searchPage(int page, int size) {
        return searchPage(page, size, null, null);
    }

    /**
     * MA-012：关键词与状态进 SQL（原先前端只过滤当前页、total 仍取服务端 ⇒ 行数与总数不符）。
     * q 匹配 活动名称 / 状态值；status 精确匹配（前端把状态中文标签翻成值再传）。
     */
    default Page<AdCampaign> searchPage(int page, int size, String q, String status) {
        var query = Wrappers.<AdCampaign>lambdaQuery().orderByDesc(AdCampaign::getCreatedAt);
        if (status != null && !status.isBlank()) {
            query.eq(AdCampaign::getStatus, status.trim().toUpperCase());
        }
        if (q != null && !q.isBlank()) {
            String kw = q.trim();
            query.and(w -> w.like(AdCampaign::getName, kw).or().like(AdCampaign::getStatus, kw));
        }
        return selectPage(new Page<>(page + 1L, size), query);
    }

    default List<AdCampaign> findRunningInWindow(Instant now) {
        return selectList(Wrappers.<AdCampaign>lambdaQuery()
                .eq(AdCampaign::getStatus, "RUNNING")
                .and(w -> w.isNull(AdCampaign::getStartAt).or().le(AdCampaign::getStartAt, now))
                .and(w -> w.isNull(AdCampaign::getEndAt).or().ge(AdCampaign::getEndAt, now))
                .orderByDesc(AdCampaign::getCreatedAt));
    }
}
