package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.DataConsistencyRecord;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface DataConsistencyRecordMapper extends BaseTradeMapper<DataConsistencyRecord> {

    default List<DataConsistencyRecord> findByStatus(String status) {
        return selectList(Wrappers.<DataConsistencyRecord>lambdaQuery()
                .eq(DataConsistencyRecord::getStatus, status));
    }

    default List<DataConsistencyRecord> findByTableName(String tableName) {
        return selectList(Wrappers.<DataConsistencyRecord>lambdaQuery()
                .eq(DataConsistencyRecord::getTableName, tableName));
    }

    /** 查找同一检查项未修复的 FAIL，用于去重。 */
    default List<DataConsistencyRecord> findByCheckTypeAndCheckKeyAndStatus(
            String checkType, String checkKey, String status) {
        return selectList(Wrappers.<DataConsistencyRecord>lambdaQuery()
                .eq(DataConsistencyRecord::getCheckType, checkType)
                .eq(DataConsistencyRecord::getCheckKey, checkKey)
                .eq(DataConsistencyRecord::getStatus, status)
                .orderByDesc(DataConsistencyRecord::getId));
    }

    default List<DataConsistencyRecord> findByCheckTypeAndStatus(String checkType, String status) {
        return selectList(Wrappers.<DataConsistencyRecord>lambdaQuery()
                .eq(DataConsistencyRecord::getCheckType, checkType)
                .eq(DataConsistencyRecord::getStatus, status)
                .orderByDesc(DataConsistencyRecord::getId));
    }

    List<DataConsistencyRecord> findByCheckedAtBetween(Instant start, Instant end);
}
