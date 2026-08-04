package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.CompensationTask;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface CompensationTaskMapper extends BaseTradeMapper<CompensationTask> {

    default List<CompensationTask> findByStatusAndScheduledAtBefore(String status, Instant scheduledAt) {
    return selectList(Wrappers.<CompensationTask>lambdaQuery().eq(CompensationTask::getStatus, status).lt(CompensationTask::getScheduledAt, scheduledAt));
    }

        List<CompensationTask> findExecutableTasks(Instant now);

}
