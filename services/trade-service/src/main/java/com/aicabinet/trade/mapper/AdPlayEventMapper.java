package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.AdPlayEvent;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Mapper
public interface AdPlayEventMapper extends BaseTradeMapper<AdPlayEvent> {

    default long countByCampaignAndType(Long campaignId, String eventType) {
        Long n = selectCount(Wrappers.<AdPlayEvent>lambdaQuery()
                .eq(AdPlayEvent::getCampaignId, campaignId)
                .eq(AdPlayEvent::getEventType, eventType));
        return n == null ? 0 : n;
    }

    /**
     * 按「展示位 × 主体」汇总曝光/点击/已归因人数（批次 T）。
     *
     * <p>主体判定用 {@code activity_id IS NOT NULL}：弹窗位带活动 id、banner 位带广告计划 id，
     * 两套 id 各自成行，**不共用一列**（那正是此前 {@code MP-U{userId}} 塞 device_id 的错法）。
     */
    @Select("""
            SELECT COALESCE(position, 'UNKNOWN') AS position,
                   CASE WHEN activity_id IS NOT NULL THEN 'ACTIVITY' ELSE 'AD_CAMPAIGN' END AS subject_kind,
                   COALESCE(activity_id, campaign_id) AS subject_id,
                   COUNT(*) FILTER (WHERE event_type = 'IMPRESSION') AS exposure_count,
                   COUNT(DISTINCT user_id) FILTER (WHERE event_type = 'IMPRESSION') AS exposure_users,
                   COUNT(*) FILTER (WHERE event_type = 'CLICK') AS click_count,
                   COUNT(*) FILTER (WHERE user_id IS NULL) AS unattributed_count
            FROM ad_play_event
            WHERE created_at >= #{since}
            GROUP BY 1, 2, 3
            """)
    List<Map<String, Object>> aggregatePlacementsSince(@Param("since") Instant since);

    /**
     * 单用户最大曝光次数（人均是否被一人刷高，就靠这一列看出来）。
     *
     * <p>两层 group-by：先按 (位, 主体, 人) 计数，再取每人峰值。
     * 只统计已归因行（{@code user_id IS NOT NULL}），否则未归因行会被当成"某一个人"刷出假峰值。
     */
    @Select("""
            SELECT t.position, t.subject_kind, t.subject_id, MAX(t.c) AS max_exposure_per_user
            FROM (
              SELECT COALESCE(position, 'UNKNOWN') AS position,
                     CASE WHEN activity_id IS NOT NULL THEN 'ACTIVITY' ELSE 'AD_CAMPAIGN' END AS subject_kind,
                     COALESCE(activity_id, campaign_id) AS subject_id,
                     user_id,
                     COUNT(*) AS c
              FROM ad_play_event
              WHERE created_at >= #{since}
                AND event_type = 'IMPRESSION'
                AND user_id IS NOT NULL
              GROUP BY 1, 2, 3, 4
            ) t
            GROUP BY 1, 2, 3
            """)
    List<Map<String, Object>> peakExposurePerUserSince(@Param("since") Instant since);
}
