package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.PromotionPlacement;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface PromotionPlacementMapper extends BaseTradeMapper<PromotionPlacement> {

    /**
     * 消费者读面：按位置取生效中的活动展示位，范围判定与定价同口径
     * （ALL / SPECIFIC(deviceIds) / MERCHANT(merchantIds)）。
     * 数组包含判断用 EXISTS + jsonb_array_elements_text（勿用 jsonb `?`，会被 MyBatis 当占位符）。
     */
    @Select("""
        SELECT p.* FROM promotion_placement p
        JOIN promotion_activity a ON a.activity_id = p.activity_id
        WHERE p.position = #{position}
          AND a.is_deleted = FALSE
          AND UPPER(a.status) = 'ACTIVE'
          AND a.activity_type IN ('SKU_REDUCE', 'SKU_DISCOUNT', 'SKU_FIXED_PRICE')
          AND (a.start_time IS NULL OR a.start_time <= now())
          AND (a.end_time IS NULL OR a.end_time > now())
          AND (
            UPPER(COALESCE(a.device_scope, 'ALL')) = 'ALL'
            OR (UPPER(a.device_scope) = 'SPECIFIC' AND EXISTS (
                SELECT 1 FROM jsonb_array_elements_text(COALESCE(a.rule_config -> 'deviceIds', '[]'::jsonb)) e
                WHERE e = #{deviceId}))
            OR (UPPER(a.device_scope) = 'MERCHANT'
                AND CAST(#{merchantId} AS varchar) IS NOT NULL
                AND EXISTS (
                  SELECT 1 FROM jsonb_array_elements_text(COALESCE(a.rule_config -> 'merchantIds', '[]'::jsonb)) e
                  WHERE e = #{merchantId}))
          )
        ORDER BY a.activity_id, p.placement_id
        """)
    List<PromotionPlacement> findActivePlacements(@Param("deviceId") String deviceId,
                                                  @Param("merchantId") String merchantId,
                                                  @Param("position") String position);

    default List<PromotionPlacement> findByActivityId(Long activityId) {
        return selectList(Wrappers.<PromotionPlacement>lambdaQuery()
                .eq(PromotionPlacement::getActivityId, activityId)
                .orderByAsc(PromotionPlacement::getPlacementId));
    }

    default long countByAssetId(Long assetId) {
        Long c = selectCount(Wrappers.<PromotionPlacement>lambdaQuery()
                .eq(PromotionPlacement::getAssetId, assetId));
        return c == null ? 0L : c;
    }

    default long countByActivityIdAndPosition(Long activityId, String position) {
        Long c = selectCount(Wrappers.<PromotionPlacement>lambdaQuery()
                .eq(PromotionPlacement::getActivityId, activityId)
                .eq(PromotionPlacement::getPosition, position));
        return c == null ? 0L : c;
    }
}
