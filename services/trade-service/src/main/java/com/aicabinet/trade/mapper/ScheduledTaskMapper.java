package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.ScheduledTask;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface ScheduledTaskMapper extends BaseTradeMapper<ScheduledTask> {

    default List<ScheduledTask> findAllByOrderByTaskKeyAsc() {
        return selectList(Wrappers.<ScheduledTask>lambdaQuery()
                .orderByAsc(ScheduledTask::getTaskKey));
    }
}
