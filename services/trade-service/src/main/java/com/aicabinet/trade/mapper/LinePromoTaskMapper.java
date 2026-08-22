package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.LinePromoTask;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface LinePromoTaskMapper extends BaseTradeMapper<LinePromoTask> {
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
