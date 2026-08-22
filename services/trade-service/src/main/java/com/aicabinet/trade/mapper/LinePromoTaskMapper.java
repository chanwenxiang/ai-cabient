package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.LinePromoTask;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface LinePromoTaskMapper extends BaseTradeMapper<LinePromoTask> {

    LinePromoTask _findByIdForUpdateRaw(@Param("taskId") Long taskId);

    default Optional<LinePromoTask> findByIdForUpdate(Long taskId) {
        return Optional.ofNullable(_findByIdForUpdateRaw(taskId));
    }
    default List<LinePromoTask> findByManager(Long managerId, String status) {
        var q = Wrappers.<LinePromoTask>lambdaQuery()
                .orderByDesc(LinePromoTask::getUpdatedAt)
                .last("LIMIT 200");
        if (managerId != null) {
            q.eq(LinePromoTask::getManagerId, managerId);
        }
        if (status != null && !status.isBlank()) {
            q.eq(LinePromoTask::getStatus, status.trim().toUpperCase());
        }
        return selectList(q);
    }
}
