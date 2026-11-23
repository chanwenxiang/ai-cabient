package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.Member;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface MemberMapper extends BaseTradeMapper<Member> {

    Member findByUserIdForUpdateRaw(@Param("userId") Long userId);

    Member findByIdForUpdateRaw(@Param("memberId") Long memberId);

    default Optional<Member> findByUserIdForUpdate(Long userId) {
        return Optional.ofNullable(findByUserIdForUpdateRaw(userId));
    }

    default Optional<Member> findByIdForUpdate(Long memberId) {
        return Optional.ofNullable(findByIdForUpdateRaw(memberId));
    }

    default List<Member> findByUserIds(Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return List.of();
        }
        return selectList(Wrappers.<Member>lambdaQuery()
                .in(Member::getUserId, userIds));
    }

    default Optional<Member> findByUserId(Long userId) {
    return Optional.ofNullable(selectOne(Wrappers.<Member>lambdaQuery().eq(Member::getUserId, userId)));
    }

    default List<Member> findByMemberLevel(String memberLevel) {
    return selectList(Wrappers.<Member>lambdaQuery().eq(Member::getMemberLevel, memberLevel));
    }

        List<Member> findByTotalSpentGreaterThanEqual(java.math.BigDecimal minSpent);

    // ── 邀请归因（V501 / 营销 2） ─────────────────────────────

    default Optional<Member> findByInviteCode(String inviteCode) {
        return Optional.ofNullable(selectOne(Wrappers.<Member>lambdaQuery()
                .eq(Member::getInviteCode, inviteCode)));
    }

    default boolean existsInviteCode(String inviteCode) {
        Long count = selectCount(Wrappers.<Member>lambdaQuery()
                .eq(Member::getInviteCode, inviteCode));
        return count != null && count > 0;
    }

    /**
     * 绑定邀请人：**条件更新**，把「已绑过 / 已下过单」的并发情况压进一条 SQL。
     *
     * <p>不做「先查后写」：查与写之间用户可能刚好完成首单，那条缝隙会让老用户被补录成新客。
     *
     * @return 0 = 条件不成立（已绑定 / 已有消费记录 / 会员不存在），由调用方转 409
     */
    @Update("""
            UPDATE member
               SET invited_by = #{inviterUserId},
                   invited_at = #{at},
                   updated_at = NOW()
             WHERE user_id = #{userId}
               AND invited_by IS NULL
               AND COALESCE(order_count, 0) = 0
            """)
    int bindInviter(@Param("userId") Long userId,
                    @Param("inviterUserId") Long inviterUserId,
                    @Param("at") java.time.Instant at);

    /** 存量会员惰性补码：只写空位，不覆盖别人刚写进去的码。 */
    @Update("""
            UPDATE member
               SET invite_code = #{inviteCode},
                   updated_at = NOW()
             WHERE user_id = #{userId}
               AND invite_code IS NULL
            """)
    int writeInviteCodeIfAbsent(@Param("userId") Long userId,
                                @Param("inviteCode") String inviteCode);

    @Update("""
            UPDATE member
               SET invitee_coupon_id = #{couponId},
                   updated_at = NOW()
             WHERE user_id = #{userId}
            """)
    int writeInviteeCouponId(@Param("userId") Long userId, @Param("couponId") Long couponId);

    default long countByInvitedBy(Long inviterUserId) {
        Long count = selectCount(Wrappers.<Member>lambdaQuery()
                .eq(Member::getInvitedBy, inviterUserId));
        return count == null ? 0L : count;
    }

    default long countSettledByInvitedBy(Long inviterUserId) {
        Long count = selectCount(Wrappers.<Member>lambdaQuery()
                .eq(Member::getInvitedBy, inviterUserId)
                .isNotNull(Member::getInviteSettledAt));
        return count == null ? 0L : count;
    }

}
