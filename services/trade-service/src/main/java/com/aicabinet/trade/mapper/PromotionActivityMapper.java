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

    /**
     * P3-5（2026-11-12）：活动「生效中」判据——与 {@link #findPricedCandidates} 的 SQL **逐字同口径**。
     *
     * <p>原实现是 MP 派生的 {@code lt(startTime, now).gt(endTime, now)}，与 SQL 侧差三处，各自能出独立故障：
     * ① start 用 {@code <} 而 SQL 是 {@code <=}：起始时刻那一瞬，列表判「未开始」而领券放行；
     * ② 两侧都不容忍 NULL，SQL 是 {@code col IS NULL OR ...} ⇒ **不设起止的活动**在运营的
     *    「进行中活动」列表里**整条消失**。发券类活动（非 SKU 类型）validator 并不强制起止
     *    （见 {@code PromotionActivityValidator#validateForSave}），而领券侧 {@code doClaimCampaign}
     *    对 NULL 是放行的 ⇒ 用户照领、运营看不到，活动等于隐身跑；
     * ③ 只有 end 侧端点（{@code >}）原本就与 SQL 一致，但被 ② 掩盖。
     *
     * <p>沿用 SQL 语义：{@code start_time IS NULL OR start_time <= now}、{@code end_time IS NULL OR end_time > now}，
     * 即「不设起止 = 不限期」、「到点即止」（end 端点不含）。
     */
    default List<PromotionActivity> findActiveInWindow(String status, Instant now) {
        return selectList(Wrappers.<PromotionActivity>lambdaQuery()
                .eq(PromotionActivity::getStatus, status)
                .and(w -> w.isNull(PromotionActivity::getStartTime).or().le(PromotionActivity::getStartTime, now))
                .and(w -> w.isNull(PromotionActivity::getEndTime).or().gt(PromotionActivity::getEndTime, now)));
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


    /**
     * ROI 表专用活动清单（R20 P2-15）：只取列表要展示的 6 列。
     *
     * <p>原来用 {@code findAll()}（{@code SELECT *}），把 {@code rule_config}、
     * 描述这类大 JSON/文本一并拉回内存——ROI 一行都用不到。列下推后单次查询体积与
     * 反序列化开销都降下来。**不按 status/时间下推**：ROI 的口径是「全部活动」，
     * 无券活动也要入表（OBS-006），筛掉任何一类都会让运营看到的活动消失。
     *
     * <p>不过滤 {@code is_deleted}：实体没映射该列，{@code findAll()} 同样不过滤；
     * 两个入口口径必须一致（软删列未映射属另一族问题）。
     */
    default List<PromotionActivity> findAllForRoi() {
        return selectList(Wrappers.<PromotionActivity>lambdaQuery()
                .select(PromotionActivity::getActivityId, PromotionActivity::getActivityName,
                        PromotionActivity::getActivityType, PromotionActivity::getStatus,
                        PromotionActivity::getBudgetCents, PromotionActivity::getUsedCents)
                .orderByDesc(PromotionActivity::getActivityId));
    }

}
