package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.GameTask;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.springframework.stereotype.Repository;

@Mapper
public interface GameTaskMapper extends BaseTradeMapper<GameTask> {

    default Optional<GameTask> findByTaskCode(String taskCode) {
    return Optional.ofNullable(selectOne(Wrappers.<GameTask>lambdaQuery().eq(GameTask::getTaskCode, taskCode)));
    }

    default List<GameTask> findByStatus(String status) {
    return selectList(Wrappers.<GameTask>lambdaQuery().eq(GameTask::getStatus, status));
    }

    default List<GameTask> findByTaskType(String taskType) {
    return selectList(Wrappers.<GameTask>lambdaQuery().eq(GameTask::getTaskType, taskType));
    }

}
