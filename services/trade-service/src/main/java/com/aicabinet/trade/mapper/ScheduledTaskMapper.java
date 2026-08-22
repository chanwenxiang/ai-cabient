package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.ScheduledTask;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ScheduledTaskMapper extends BaseTradeMapper<ScheduledTask> {

    ScheduledTask _findByIdForUpdateRaw(@Param("taskKey") String taskKey);

    default Optional<ScheduledTask> findByIdForUpdate(String taskKey) {
        return Optional.ofNullable(_findByIdForUpdateRaw(taskKey));
    }

    default List<ScheduledTask> findAllByOrderByTaskKeyAsc() {
        return selectList(Wrappers.<ScheduledTask>lambdaQuery()
                .orderByAsc(ScheduledTask::getTaskKey));
    }
}
