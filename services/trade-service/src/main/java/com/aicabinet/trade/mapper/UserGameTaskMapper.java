package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.UserGameTask;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.springframework.stereotype.Repository;

@Mapper
public interface UserGameTaskMapper extends BaseTradeMapper<UserGameTask> {

    default List<UserGameTask> findByUserId(Long userId) {
    return selectList(Wrappers.<UserGameTask>lambdaQuery().eq(UserGameTask::getUserId, userId));
    }

    default Optional<UserGameTask> findByUserIdAndTaskId(Long userId, Long taskId) {
    return Optional.ofNullable(selectOne(Wrappers.<UserGameTask>lambdaQuery().eq(UserGameTask::getUserId, userId).eq(UserGameTask::getTaskId, taskId)));
    }

    default List<UserGameTask> findByUserIdAndStatus(Long userId, String status) {
    return selectList(Wrappers.<UserGameTask>lambdaQuery().eq(UserGameTask::getUserId, userId).eq(UserGameTask::getStatus, status));
    }

}
