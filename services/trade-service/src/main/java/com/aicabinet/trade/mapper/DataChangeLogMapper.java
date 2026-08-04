package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.DataChangeLog;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface DataChangeLogMapper extends BaseTradeMapper<DataChangeLog> {

    default List<DataChangeLog> findByTableNameAndRecordId(String tableName, String recordId) {
    return selectList(Wrappers.<DataChangeLog>lambdaQuery().eq(DataChangeLog::getTableName, tableName).eq(DataChangeLog::getRecordId, recordId));
    }

    default List<DataChangeLog> findByVerifiedFalse() {
        return selectList(Wrappers.<DataChangeLog>lambdaQuery()
                .eq(DataChangeLog::getVerified, false)
                .orderByDesc(DataChangeLog::getId)
                .last("LIMIT 200"));
    }

        List<DataChangeLog> findByChangedAtBetween(Instant start, Instant end);

}
