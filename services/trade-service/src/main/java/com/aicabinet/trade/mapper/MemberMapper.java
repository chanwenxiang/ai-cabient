package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.Member;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

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

}
