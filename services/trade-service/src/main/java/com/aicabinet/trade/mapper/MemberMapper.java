package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.Member;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface MemberMapper extends BaseTradeMapper<Member> {

    default Optional<Member> findByUserId(Long userId) {
    return Optional.ofNullable(selectOne(Wrappers.<Member>lambdaQuery().eq(Member::getUserId, userId)));
    }

    default Optional<Member> findByInviteCode(String inviteCode) {
    return Optional.ofNullable(selectOne(Wrappers.<Member>lambdaQuery().eq(Member::getInviteCode, inviteCode)));
    }

    default List<Member> findByMemberLevel(String memberLevel) {
    return selectList(Wrappers.<Member>lambdaQuery().eq(Member::getMemberLevel, memberLevel));
    }

    default List<Member> findByInvitedBy(Long invitedBy) {
    return selectList(Wrappers.<Member>lambdaQuery().eq(Member::getInvitedBy, invitedBy));
    }

        List<Member> findByTotalSpentGreaterThanEqual(java.math.BigDecimal minSpent);

}
