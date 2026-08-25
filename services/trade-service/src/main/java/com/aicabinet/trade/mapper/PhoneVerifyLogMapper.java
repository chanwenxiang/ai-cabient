package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.PhoneVerifyLog;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;

@Mapper
public interface PhoneVerifyLogMapper extends BaseTradeMapper<PhoneVerifyLog> {

    PhoneVerifyLog _findByIdForUpdateRaw(@Param("logId") Long logId);

    default Optional<PhoneVerifyLog> findByIdForUpdate(Long logId) {
        return Optional.ofNullable(_findByIdForUpdateRaw(logId));
    }

    default Page<PhoneVerifyLog> search(String phone, String channel, Instant from, Instant to, int page, int size) {
        var q = Wrappers.<PhoneVerifyLog>lambdaQuery().orderByDesc(PhoneVerifyLog::getVerifiedAt);
        if (phone != null && !phone.isBlank()) {
            q.like(PhoneVerifyLog::getPhone, phone.trim());
        }
        if (channel != null && !channel.isBlank()) {
            q.eq(PhoneVerifyLog::getChannel, channel.trim());
        }
        if (from != null) {
            q.ge(PhoneVerifyLog::getVerifiedAt, from);
        }
        if (to != null) {
            q.le(PhoneVerifyLog::getVerifiedAt, to);
        }
        return selectPage(new Page<>(page + 1L, size), q);
    }
}
