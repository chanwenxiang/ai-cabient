package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.MemberPointsLog;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface MemberPointsLogMapper extends BaseTradeMapper<MemberPointsLog> {

    default List<MemberPointsLog> findByMemberId(Long memberId) {
    return selectList(Wrappers.<MemberPointsLog>lambdaQuery().eq(MemberPointsLog::getMemberId, memberId));
    }

    default List<MemberPointsLog> findByMemberIdAndPointsType(Long memberId, String pointsType) {
    return selectList(Wrappers.<MemberPointsLog>lambdaQuery().eq(MemberPointsLog::getMemberId, memberId).eq(MemberPointsLog::getPointsType, pointsType));
    }

        List<MemberPointsLog> findExpiredPoints(Long memberId, Instant now);

}
