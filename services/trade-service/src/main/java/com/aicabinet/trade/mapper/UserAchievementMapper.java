package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.UserAchievement;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.springframework.stereotype.Repository;

@Mapper
public interface UserAchievementMapper extends BaseTradeMapper<UserAchievement> {

    default List<UserAchievement> findByUserId(Long userId) {
    return selectList(Wrappers.<UserAchievement>lambdaQuery().eq(UserAchievement::getUserId, userId));
    }

    default Optional<UserAchievement> findByUserIdAndAchievementId(Long userId, Long achievementId) {
    return Optional.ofNullable(selectOne(Wrappers.<UserAchievement>lambdaQuery().eq(UserAchievement::getUserId, userId).eq(UserAchievement::getAchievementId, achievementId)));
    }

    default List<UserAchievement> findByUserIdAndStatus(Long userId, String status) {
    return selectList(Wrappers.<UserAchievement>lambdaQuery().eq(UserAchievement::getUserId, userId).eq(UserAchievement::getStatus, status));
    }

}
