package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.IdempotencyKey;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.Instant;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface IdempotencyKeyMapper extends BaseTradeMapper<IdempotencyKey> {

    IdempotencyKey findByIdForUpdateRaw(@Param("idempotencyKey") String idempotencyKey);

    default Optional<IdempotencyKey> findByIdForUpdate(String idempotencyKey) {
        return Optional.ofNullable(findByIdForUpdateRaw(idempotencyKey));
    }

    @org.apache.ibatis.annotations.Delete("""
            DELETE FROM idempotency_key k WHERE k.expire_at < #{now}
            """)
    int deleteExpiredKeys(@Param("now") Instant now);

    default IdempotencyKey findByBusinessTypeAndBusinessId(String businessType, String businessId) {
    return selectOne(Wrappers.<IdempotencyKey>lambdaQuery().eq(IdempotencyKey::getBusinessType, businessType).eq(IdempotencyKey::getBusinessId, businessId));
    }

}
