package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.SlaDailySnapshot;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.LocalDate;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface SlaDailySnapshotMapper extends BaseTradeMapper<SlaDailySnapshot> {

    SlaDailySnapshot findByIdForUpdateRaw(@Param("snapshotDate") LocalDate snapshotDate);

    default Optional<SlaDailySnapshot> findByIdForUpdate(LocalDate snapshotDate) {
        return Optional.ofNullable(findByIdForUpdateRaw(snapshotDate));
    }

    default Optional<SlaDailySnapshot> findFirstByOrderBySnapshotDateDesc() {
    return Optional.ofNullable(selectOne(Wrappers.<SlaDailySnapshot>lambdaQuery().orderByDesc(SlaDailySnapshot::getSnapshotDate).last("LIMIT 1")));
    }

}
