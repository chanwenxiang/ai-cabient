package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.UserCoupon;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface UserCouponMapper extends BaseTradeMapper<UserCoupon> {

    UserCoupon findByIdForUpdateRaw(@Param("couponId") Long couponId);

    default Optional<UserCoupon> findByIdForUpdate(Long couponId) {
        return Optional.ofNullable(findByIdForUpdateRaw(couponId));
    }

    default List<UserCoupon> findByUserIdAndStatus(Long userId, String status) {
    return selectList(Wrappers.<UserCoupon>lambdaQuery().eq(UserCoupon::getUserId, userId).eq(UserCoupon::getStatus, status));
    }

    default List<UserCoupon> findByUserIdOrderByCreatedAtDesc(Long userId) {
    return selectList(Wrappers.<UserCoupon>lambdaQuery().eq(UserCoupon::getUserId, userId).orderByDesc(UserCoupon::getCreatedAt));
    }

    default Optional<UserCoupon> findByCouponCode(String couponCode) {
    return Optional.ofNullable(selectOne(Wrappers.<UserCoupon>lambdaQuery().eq(UserCoupon::getCouponCode, couponCode)));
    }

    default long countByUserIdAndStatus(Long userId, String status) {
    Long c = selectCount(Wrappers.<UserCoupon>lambdaQuery().eq(UserCoupon::getUserId, userId).eq(UserCoupon::getStatus, status));
    return c == null ? 0 : c;
    }

    /**
     * MA-026：真正「可用」的张数＝状态 UNUSED **且未过 expire_at**。
     *
     * <p>不能只看 status：置过期依赖 {@code coupon-expire} 日批（{@code expireOverdueCoupons}），
     * 批没跑（夜间关机、调度停摆）时库里会留着一堆 UNUSED 的过期券，消费者端因此报
     * 「N 张可用 · 结算自动选最优券」，而 {@code selectBestCoupon}/{@code useCoupon}
     * 都按 expire_at 拒绝/排除 ⇒ 提示与结算自相矛盾。读面必须自带 expire 判据。
     */
    default long countUnusedNotExpired(Long userId, Instant now) {
    Long c = selectCount(Wrappers.<UserCoupon>lambdaQuery()
            .eq(UserCoupon::getUserId, userId)
            .eq(UserCoupon::getStatus, "UNUSED")
            .ge(UserCoupon::getExpireAt, now));
    return c == null ? 0 : c;
    }

    default List<UserCoupon> findByStatusAndExpireAtBefore(String status, Instant now) {
    return selectList(Wrappers.<UserCoupon>lambdaQuery().eq(UserCoupon::getStatus, status).lt(UserCoupon::getExpireAt, now));
    }

    default List<UserCoupon> findByStatusAndExpireAtBetween(String status, Instant from, Instant to) {
        return selectList(Wrappers.<UserCoupon>lambdaQuery()
                .eq(UserCoupon::getStatus, status)
                .isNull(UserCoupon::getRemindedAt)
                .ge(UserCoupon::getExpireAt, from)
                .lt(UserCoupon::getExpireAt, to));
    }

    default long countByCouponDefId(Long couponDefId) {
    Long c = selectCount(Wrappers.<UserCoupon>lambdaQuery().eq(UserCoupon::getCouponDefId, couponDefId));
    return c == null ? 0 : c;
    }

    default long countByUserIdAndCouponDefId(Long userId, Long couponDefId) {
        Long c = selectCount(Wrappers.<UserCoupon>lambdaQuery()
                .eq(UserCoupon::getUserId, userId)
                .eq(UserCoupon::getCouponDefId, couponDefId));
        return c == null ? 0 : c;
    }

    default List<UserCoupon> findByCouponDefId(Long couponDefId) {
        return selectList(Wrappers.<UserCoupon>lambdaQuery().eq(UserCoupon::getCouponDefId, couponDefId));
    }

    default long countByCouponDefIdAndStatus(Long couponDefId, String status) {
        Long c = selectCount(Wrappers.<UserCoupon>lambdaQuery()
                .eq(UserCoupon::getCouponDefId, couponDefId)
                .eq(UserCoupon::getStatus, status));
        return c == null ? 0 : c;
    }

    /**
     * 按券定义聚合「已领取 / 已使用」张数（R20 P2-15）。
     *
     * <p>ROI 表原来对每个券定义跑三次查询（总数、USED 数、全部实例），一个活动挂 5 种券就是 15 次；
     * 这里一条 group-by 收口。调用方仍按「活动 → 其下全部券定义」求和，与旧口径一致。
     *
     * <p>返回列名保持下划线原样（MyBatis 对 {@code Map} 结果不做驼峰转换，与
     * {@code AdPlayEventMapper.aggregatePlacementsSince} 同规矩）。
     */
    @org.apache.ibatis.annotations.Select("""
            <script>
            SELECT coupon_def_id,
                   COUNT(*) AS claimed,
                   COALESCE(SUM(CASE WHEN status = 'USED' THEN 1 ELSE 0 END), 0) AS used
            FROM user_coupon
            WHERE coupon_def_id IN
            <foreach collection="defIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            GROUP BY coupon_def_id
            </script>
            """)
    List<java.util.Map<String, Object>> countGroupByCouponDefIds(
            @Param("defIds") java.util.Collection<Long> defIds);

    default List<UserCoupon> findByOrderIdAndStatus(String orderId, String status) {
        return selectList(Wrappers.<UserCoupon>lambdaQuery()
                .eq(UserCoupon::getOrderId, orderId)
                .eq(UserCoupon::getStatus, status));
    }

    /**
     * 将 USED 券原子恢复为 UNUSED（仅当当前仍为 USED 时生效），防并发/重复部分退重复退券。
     *
     * @return 影响行数，0 表示未改（已非 USED 或已被别人恢复）
     */
    default int restoreUsedToUnused(Long couponId) {
        if (couponId == null) {
            return 0;
        }
        return update(
                null,
                Wrappers.<UserCoupon>lambdaUpdate()
                        .eq(UserCoupon::getCouponId, couponId)
                        .eq(UserCoupon::getStatus, "USED")
                        .set(UserCoupon::getStatus, "UNUSED")
                        .set(UserCoupon::getUsedAt, null)
                        .set(UserCoupon::getOrderId, null)
                        .set(UserCoupon::getDeviceId, null)
                        .set(UserCoupon::getDiscountCents, 0));
    }

}
