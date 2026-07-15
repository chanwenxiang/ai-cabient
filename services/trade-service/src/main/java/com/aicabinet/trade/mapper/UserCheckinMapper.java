package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.UserCheckin;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserCheckinMapper extends BaseTradeMapper<UserCheckin> {

    default Optional<UserCheckin> findByUserIdAndCheckinDate(Long userId, LocalDate checkinDate) {
    return Optional.ofNullable(selectOne(Wrappers.<UserCheckin>lambdaQuery().eq(UserCheckin::getUserId, userId).eq(UserCheckin::getCheckinDate, checkinDate)));
    }

    default List<UserCheckin> findByUserIdOrderByCheckinDateDesc(Long userId) {
    return selectList(Wrappers.<UserCheckin>lambdaQuery().eq(UserCheckin::getUserId, userId).orderByDesc(UserCheckin::getCheckinDate));
    }

    default Optional<UserCheckin> findFirstByUserIdOrderByCheckinDateDesc(Long userId) {
    return Optional.ofNullable(selectOne(Wrappers.<UserCheckin>lambdaQuery().eq(UserCheckin::getUserId, userId).orderByDesc(UserCheckin::getCheckinDate).last("LIMIT 1")));
    }

}
