package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.SmsVerificationCode;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SmsVerificationCodeMapper extends BaseTradeMapper<SmsVerificationCode> {

    default Optional<SmsVerificationCode> findTopByPhoneNumberAndUsedAtIsNullOrderByCreatedAtDesc(String phoneNumber) {
    return Optional.ofNullable(selectOne(Wrappers.<SmsVerificationCode>lambdaQuery().eq(SmsVerificationCode::getPhoneNumber, phoneNumber).isNull(SmsVerificationCode::getUsedAt).orderByDesc(SmsVerificationCode::getCreatedAt).last("LIMIT 1")));
    }

}
