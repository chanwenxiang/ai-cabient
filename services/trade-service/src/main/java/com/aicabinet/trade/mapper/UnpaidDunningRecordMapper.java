package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.UnpaidDunningRecord;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Optional;

@Mapper
public interface UnpaidDunningRecordMapper extends BaseTradeMapper<UnpaidDunningRecord> {

    UnpaidDunningRecord findByIdForUpdateRaw(@Param("userId") Long userId);

    /**
     * 加行锁读取阶梯记录（阶梯递增必须串行，否则并发下单会丢计数）。
     * 与 {@code UserBlacklistMapper.findByIdForUpdate} 同模式：锁在事务内有效。
     */
    default Optional<UnpaidDunningRecord> findByIdForUpdate(Long userId) {
        return Optional.ofNullable(findByIdForUpdateRaw(userId));
    }

    default Optional<UnpaidDunningRecord> findByUserId(Long userId) {
        return Optional.ofNullable(selectOne(Wrappers.<UnpaidDunningRecord>lambdaQuery()
                .eq(UnpaidDunningRecord::getUserId, userId)
                .last("LIMIT 1")));
    }
}
