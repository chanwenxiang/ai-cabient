package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.Achievement;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AchievementMapper extends BaseTradeMapper<Achievement> {

    default Optional<Achievement> findByAchievementCode(String achievementCode) {
    return Optional.ofNullable(selectOne(Wrappers.<Achievement>lambdaQuery().eq(Achievement::getAchievementCode, achievementCode)));
    }

    default List<Achievement> findByStatus(String status) {
    return selectList(Wrappers.<Achievement>lambdaQuery().eq(Achievement::getStatus, status));
    }

    default List<Achievement> findByCategory(String category) {
    return selectList(Wrappers.<Achievement>lambdaQuery().eq(Achievement::getCategory, category));
    }

}
