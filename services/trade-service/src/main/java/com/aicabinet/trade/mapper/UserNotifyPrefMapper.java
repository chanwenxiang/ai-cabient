package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.UserNotifyPref;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Optional;

@Mapper
public interface UserNotifyPrefMapper extends BaseTradeMapper<UserNotifyPref> {

    UserNotifyPref _findByUserIdAndCategoryForUpdateRaw(
            @Param("userId") Long userId, @Param("category") String category);

    default Optional<UserNotifyPref> findByUserIdAndCategoryForUpdate(Long userId, String category) {
        return Optional.ofNullable(_findByUserIdAndCategoryForUpdateRaw(userId, category));
    }

    default Optional<UserNotifyPref> findByUserIdAndCategory(Long userId, String category) {
        return Optional.ofNullable(selectOne(Wrappers.<UserNotifyPref>lambdaQuery()
                .eq(UserNotifyPref::getUserId, userId)
                .eq(UserNotifyPref::getCategory, category)));
    }

    default List<UserNotifyPref> findByUserId(Long userId) {
        return selectList(Wrappers.<UserNotifyPref>lambdaQuery().eq(UserNotifyPref::getUserId, userId));
    }

    @Override
    default UserNotifyPref save(UserNotifyPref entity) {
        UserNotifyPref existing = selectOne(Wrappers.<UserNotifyPref>lambdaQuery()
                .eq(UserNotifyPref::getUserId, entity.getUserId())
                .eq(UserNotifyPref::getCategory, entity.getCategory()));
        if (existing == null) {
            insert(entity);
        } else {
            entity.setId(existing.getId());
            updateById(entity);
        }
        return entity;
    }
}
