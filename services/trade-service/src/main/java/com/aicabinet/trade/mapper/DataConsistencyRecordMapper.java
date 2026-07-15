package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.DataConsistencyRecord;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface DataConsistencyRecordMapper extends BaseTradeMapper<DataConsistencyRecord> {

    default List<DataConsistencyRecord> findByStatus(String status) {
    return selectList(Wrappers.<DataConsistencyRecord>lambdaQuery().eq(DataConsistencyRecord::getStatus, status));
    }

    default List<DataConsistencyRecord> findByTableName(String tableName) {
    return selectList(Wrappers.<DataConsistencyRecord>lambdaQuery().eq(DataConsistencyRecord::getTableName, tableName));
    }

        List<DataConsistencyRecord> findByCheckedAtBetween(Instant start, Instant end);

}
