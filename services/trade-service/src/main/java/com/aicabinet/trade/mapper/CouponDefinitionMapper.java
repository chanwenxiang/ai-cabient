package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.CouponDefinition;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface CouponDefinitionMapper extends BaseTradeMapper<CouponDefinition> {

    /**
     * 显式写「可清空」列（R2-E2）。updateById 跳过 null ⇒ 券型从 PERCENT_OFF 切走时
     * {@code discount_percent} 残留、解绑活动时 {@code activity_id} 残留（领取预算预留/释放
     * 仍挂在旧活动上，而审计行写的是 null ⇒ 账实两套口径）。清列必须走显式 UPDATE。
     */
    @Update("""
            UPDATE coupon_definition
               SET discount_percent = #{discountPercent},
                   activity_id = #{activityId}
             WHERE coupon_def_id = #{couponDefId}
            """)
    int writeNullableColumns(@Param("couponDefId") Long couponDefId,
                             @Param("discountPercent") Integer discountPercent,
                             @Param("activityId") Long activityId);

    CouponDefinition findByIdForUpdateRaw(@Param("couponDefId") Long couponDefId);

    default Optional<CouponDefinition> findByIdForUpdate(Long couponDefId) {
        return Optional.ofNullable(findByIdForUpdateRaw(couponDefId));
    }

    default List<CouponDefinition> findByStatus(String status) {
    return selectList(Wrappers.<CouponDefinition>lambdaQuery().eq(CouponDefinition::getStatus, status));
    }

    default List<CouponDefinition> findByActivityId(Long activityId) {
    return selectList(Wrappers.<CouponDefinition>lambdaQuery().eq(CouponDefinition::getActivityId, activityId));
    }

    default long countByStatus(String status) {
    Long c = selectCount(Wrappers.<CouponDefinition>lambdaQuery().eq(CouponDefinition::getStatus, status));
    return c == null ? 0 : c;
    }

    /** page 为 0-based；status=ACTIVE/INACTIVE（INACTIVE 表示非 ACTIVE）。 */
    default Page<CouponDefinition> searchPage(String q, String status, int page, int size) {
        var query = Wrappers.<CouponDefinition>lambdaQuery().orderByDesc(CouponDefinition::getCouponDefId);
        if (status != null && !status.isBlank()) {
            if ("ACTIVE".equalsIgnoreCase(status.trim())) {
                query.eq(CouponDefinition::getStatus, "ACTIVE");
            } else if ("INACTIVE".equalsIgnoreCase(status.trim())) {
                query.ne(CouponDefinition::getStatus, "ACTIVE");
            }
        }
        if (q != null && !q.isBlank()) {
            String kw = q.trim();
            query.and(w -> {
                w.like(CouponDefinition::getCouponName, kw)
                        .or().like(CouponDefinition::getCouponType, kw);
                try {
                    long id = Long.parseLong(kw);
                    w.or().eq(CouponDefinition::getCouponDefId, id);
                } catch (NumberFormatException ignored) {
                    // not numeric id
                }
            });
        }
        return selectPage(new Page<>(page + 1L, size), query);
    }

}
