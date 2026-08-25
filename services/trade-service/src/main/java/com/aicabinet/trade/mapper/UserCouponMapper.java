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

    UserCoupon _findByIdForUpdateRaw(@Param("couponId") Long couponId);

    default Optional<UserCoupon> findByIdForUpdate(Long couponId) {
        return Optional.ofNullable(_findByIdForUpdateRaw(couponId));
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

    default List<UserCoupon> findByOrderIdAndStatus(String orderId, String status) {
        return selectList(Wrappers.<UserCoupon>lambdaQuery()
                .eq(UserCoupon::getOrderId, orderId)
                .eq(UserCoupon::getStatus, status));
    }

}
