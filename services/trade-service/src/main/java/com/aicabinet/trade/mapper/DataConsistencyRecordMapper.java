package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.DataConsistencyRecord;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface DataConsistencyRecordMapper extends BaseTradeMapper<DataConsistencyRecord> {

    DataConsistencyRecord findByIdForUpdateRaw(@Param("id") Long id);

    default Optional<DataConsistencyRecord> findByIdForUpdate(Long id) {
        return Optional.ofNullable(findByIdForUpdateRaw(id));
    }

    default List<DataConsistencyRecord> findByStatus(String status) {
        return findByStatus(status, 200);
    }

    default List<DataConsistencyRecord> findByStatus(String status, int limit) {
        int lim = Math.max(1, Math.min(limit, 500));
        return selectList(Wrappers.<DataConsistencyRecord>lambdaQuery()
                .eq(DataConsistencyRecord::getStatus, status)
                .orderByDesc(DataConsistencyRecord::getId)
                .last("LIMIT " + lim));
    }

    default List<DataConsistencyRecord> findByTableName(String tableName) {
        return selectList(Wrappers.<DataConsistencyRecord>lambdaQuery()
                .eq(DataConsistencyRecord::getTableName, tableName)
                .orderByDesc(DataConsistencyRecord::getId)
                .last("LIMIT 200"));
    }

    /** 查找同一检查项未修复的 FAIL，用于去重。 */
    default List<DataConsistencyRecord> findByCheckTypeAndCheckKeyAndStatus(
            String checkType, String checkKey, String status) {
        return selectList(Wrappers.<DataConsistencyRecord>lambdaQuery()
                .eq(DataConsistencyRecord::getCheckType, checkType)
                .eq(DataConsistencyRecord::getCheckKey, checkKey)
                .eq(DataConsistencyRecord::getStatus, status)
                .orderByDesc(DataConsistencyRecord::getId)
                .last("LIMIT 5"));
    }

    default List<DataConsistencyRecord> findByCheckTypeAndStatus(String checkType, String status) {
        return findByCheckTypeAndStatus(checkType, status, 500);
    }

    default List<DataConsistencyRecord> findByCheckTypeAndStatus(String checkType, String status, int limit) {
        int lim = Math.max(1, Math.min(limit, 500));
        return selectList(Wrappers.<DataConsistencyRecord>lambdaQuery()
                .eq(DataConsistencyRecord::getCheckType, checkType)
                .eq(DataConsistencyRecord::getStatus, status)
                .orderByDesc(DataConsistencyRecord::getId)
                .last("LIMIT " + lim));
    }

    List<DataConsistencyRecord> findByCheckedAtBetween(Instant start, Instant end);
}
