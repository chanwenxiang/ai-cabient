package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.SlaDailySnapshot;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.LocalDate;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SlaDailySnapshotMapper extends BaseTradeMapper<SlaDailySnapshot> {

    default Optional<SlaDailySnapshot> findFirstByOrderBySnapshotDateDesc() {
    return Optional.ofNullable(selectOne(Wrappers.<SlaDailySnapshot>lambdaQuery().orderByDesc(SlaDailySnapshot::getSnapshotDate).last("LIMIT 1")));
    }

}
