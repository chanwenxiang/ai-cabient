package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.ReplenishmentTaskLine;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ReplenishmentTaskLineMapper extends BaseTradeMapper<ReplenishmentTaskLine> {

    default List<ReplenishmentTaskLine> findByTaskIdOrderByLineIdAsc(Long taskId) {
    return selectList(Wrappers.<ReplenishmentTaskLine>lambdaQuery().eq(ReplenishmentTaskLine::getTaskId, taskId).orderByAsc(ReplenishmentTaskLine::getLineId));
    }

    default List<ReplenishmentTaskLine> findByTaskIdAndAppliedFalse(Long taskId) {
    return selectList(Wrappers.<ReplenishmentTaskLine>lambdaQuery().eq(ReplenishmentTaskLine::getTaskId, taskId).eq(ReplenishmentTaskLine::isApplied, false));
    }

    default void deleteByTaskIdAndAppliedFalse(Long taskId) {
    delete(Wrappers.<ReplenishmentTaskLine>lambdaQuery().eq(ReplenishmentTaskLine::getTaskId, taskId).eq(ReplenishmentTaskLine::isApplied, false));
    }

}
