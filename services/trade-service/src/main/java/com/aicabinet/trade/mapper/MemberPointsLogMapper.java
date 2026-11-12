package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.MemberPointsLog;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;
import java.time.Instant;

@Mapper
public interface MemberPointsLogMapper extends BaseTradeMapper<MemberPointsLog> {

    default List<MemberPointsLog> findByMemberIdOrderByCreatedDesc(Long memberId, int limit) {
        return selectList(Wrappers.<MemberPointsLog>lambdaQuery()
                .eq(MemberPointsLog::getMemberId, memberId)
                // 0 分行是「该单已计入统计」的幂等占位（见 MemberService.earnPoints），
                // 不是用户看得懂的积分流水 ⇒ 明细列表不展示。
                .ne(MemberPointsLog::getPoints, 0)
                .orderByDesc(MemberPointsLog::getCreatedAt)
                .last("LIMIT " + Math.min(Math.max(limit, 1), 200)));
    }

    /**
     * 某订单的返积分行（EARN/ORDER）。
     *
     * <p>用于退款扣回时取**发放时**倍率（R20-10）。带 {@code points>0} 过滤与唯一索引同口径：
     * 唯一索引保证一单一 EARN 行，0 分的占位行不该被当作发放凭据。
     */
    default java.util.Optional<MemberPointsLog> findOrderEarn(Long memberId, String sourceId) {
        return selectList(Wrappers.<MemberPointsLog>lambdaQuery()
                        .eq(MemberPointsLog::getMemberId, memberId)
                        .eq(MemberPointsLog::getSourceType, "ORDER")
                        .eq(MemberPointsLog::getSourceId, sourceId)
                        .gt(MemberPointsLog::getPoints, 0)
                        .last("LIMIT 1"))
                .stream().findFirst();
    }

    /** 已到期未处理的 EARN 积分日志（用于过期结转）。 */
    default List<MemberPointsLog> findEarnExpiredBefore(Instant now) {
        return selectList(Wrappers.<MemberPointsLog>lambdaQuery()
                .eq(MemberPointsLog::getPointsType, "EARN")
                .isNull(MemberPointsLog::getExpiredAt)
                .le(MemberPointsLog::getExpireAt, now));
    }

    /** 即将到期且未提醒、未过期的 EARN 积分日志。 */
    default List<MemberPointsLog> findEarnExpiringBetween(Instant from, Instant to) {
        return selectList(Wrappers.<MemberPointsLog>lambdaQuery()
                .eq(MemberPointsLog::getPointsType, "EARN")
                .isNull(MemberPointsLog::getExpiredAt)
                .isNull(MemberPointsLog::getRemindedAt)
                .ge(MemberPointsLog::getExpireAt, from)
                .lt(MemberPointsLog::getExpireAt, to));
    }

    /** 幂等检查：同源（如订单）是否已发放积分。 */
    default boolean existsByMemberAndSource(Long memberId, String sourceType, String sourceId) {
        Long c = selectCount(Wrappers.<MemberPointsLog>lambdaQuery()
                .eq(MemberPointsLog::getMemberId, memberId)
                .eq(MemberPointsLog::getSourceType, sourceType)
                .eq(MemberPointsLog::getSourceId, sourceId));
        return c != null && c > 0;
    }
}
