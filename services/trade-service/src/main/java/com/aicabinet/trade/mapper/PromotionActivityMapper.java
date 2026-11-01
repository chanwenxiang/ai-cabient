package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.PromotionActivity;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface PromotionActivityMapper extends BaseTradeMapper<PromotionActivity> {

    PromotionActivity findByIdForUpdateRaw(@Param("activityId") Long activityId);

    /**
     * v1 活动定价（CB-082）：按 设备/商户/SKU 找出**生效中**的可定价活动。
     * 范围约定沿用 rule_config JSON：`skuIds` / `deviceIds` / `merchantIds` 数组；
     * device_scope ∈ ALL / SPECIFIC / MERCHANT。
     *
     * <p>🔴 数组包含判断刻意用 {@code jsonb_array_elements_text(...) EXISTS} 而不是 jsonb 的
     * {@code ?} 运算符：MyBatis 会把 {@code ?} 当 JDBC 参数占位符（实测
     * “No value specified for parameter N”），这是 2026-11-01 首次真库联调才暴露的坑。
     */
    @Select("""
        SELECT * FROM promotion_activity
        WHERE is_deleted = FALSE
          AND UPPER(status) = 'ACTIVE'
          AND activity_type IN ('SKU_REDUCE', 'SKU_DISCOUNT', 'SKU_FIXED_PRICE')
          AND (start_time IS NULL OR start_time <= now())
          AND (end_time IS NULL OR end_time > now())
          AND EXISTS (
            SELECT 1 FROM jsonb_array_elements_text(COALESCE(rule_config -> 'skuIds', '[]'::jsonb)) e
            WHERE e = #{skuId})
          AND (
            UPPER(COALESCE(device_scope, 'ALL')) = 'ALL'
            OR (UPPER(device_scope) = 'SPECIFIC' AND EXISTS (
                SELECT 1 FROM jsonb_array_elements_text(COALESCE(rule_config -> 'deviceIds', '[]'::jsonb)) e
                WHERE e = #{deviceId}))
            OR (UPPER(device_scope) = 'MERCHANT'
                AND CAST(#{merchantId} AS varchar) IS NOT NULL
                AND EXISTS (
                  SELECT 1 FROM jsonb_array_elements_text(COALESCE(rule_config -> 'merchantIds', '[]'::jsonb)) e
                  WHERE e = #{merchantId}))
          )
        ORDER BY activity_id
        """)
    List<PromotionActivity> findPricedCandidates(@Param("deviceId") String deviceId,
                                                 @Param("merchantId") String merchantId,
                                                 @Param("skuId") String skuId);

    default java.util.Optional<PromotionActivity> findByIdForUpdate(Long activityId) {
        return java.util.Optional.ofNullable(findByIdForUpdateRaw(activityId));
    }

    default List<PromotionActivity> findByStatus(String status) {
    return selectList(Wrappers.<PromotionActivity>lambdaQuery().eq(PromotionActivity::getStatus, status));
    }

    default List<PromotionActivity> findByStatusAndStartTimeBeforeAndEndTimeAfter(String status, Instant now1, Instant now2) {
    return selectList(Wrappers.<PromotionActivity>lambdaQuery().eq(PromotionActivity::getStatus, status).lt(PromotionActivity::getStartTime, now1).gt(PromotionActivity::getEndTime, now2));
    }

    default List<PromotionActivity> findByActivityType(String activityType) {
    return selectList(Wrappers.<PromotionActivity>lambdaQuery().eq(PromotionActivity::getActivityType, activityType));
    }

    /** page 为 0-based；status=ACTIVE/INACTIVE（INACTIVE 表示非 ACTIVE）。 */
    default Page<PromotionActivity> searchPage(String q, String status, int page, int size) {
        var query = Wrappers.<PromotionActivity>lambdaQuery().orderByDesc(PromotionActivity::getActivityId);
        if (status != null && !status.isBlank()) {
            if ("ACTIVE".equalsIgnoreCase(status.trim())) {
                query.eq(PromotionActivity::getStatus, "ACTIVE");
            } else if ("INACTIVE".equalsIgnoreCase(status.trim())) {
                query.ne(PromotionActivity::getStatus, "ACTIVE");
            }
        }
        if (q != null && !q.isBlank()) {
            String kw = q.trim();
            query.and(w -> {
                w.like(PromotionActivity::getActivityName, kw)
                        .or().like(PromotionActivity::getActivityType, kw);
                try {
                    long id = Long.parseLong(kw);
                    w.or().eq(PromotionActivity::getActivityId, id);
                } catch (NumberFormatException ignored) {
                    // not numeric id
                }
            });
        }
        return selectPage(new Page<>(page + 1L, size), query);
    }

}
