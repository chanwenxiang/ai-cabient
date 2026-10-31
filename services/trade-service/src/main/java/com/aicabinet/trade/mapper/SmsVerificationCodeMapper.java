package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.SmsVerificationCode;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SmsVerificationCodeMapper extends BaseTradeMapper<SmsVerificationCode> {

    /**
     * 原子消费：只有「尚未使用」的行会被置 used_at，返回是否抢到。
     * MA-017（2026-10-31）：原实现是「查出未用 → save(used_at)」，并发两个请求可同时通过。
     */
    default boolean markUsedIfUnused(Long id) {
        return update(null, Wrappers.<SmsVerificationCode>lambdaUpdate()
                .set(SmsVerificationCode::getUsedAt, java.time.Instant.now())
                .eq(SmsVerificationCode::getId, id)
                .isNull(SmsVerificationCode::getUsedAt)) > 0;
    }

    default Optional<SmsVerificationCode> findTopByPhoneNumberAndUsedAtIsNullOrderByCreatedAtDesc(String phoneNumber) {
    return Optional.ofNullable(selectOne(Wrappers.<SmsVerificationCode>lambdaQuery().eq(SmsVerificationCode::getPhoneNumber, phoneNumber).isNull(SmsVerificationCode::getUsedAt).orderByDesc(SmsVerificationCode::getCreatedAt).last("LIMIT 1")));
    }

}
