package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.MerchantNotifyLog;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.Instant;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface MerchantNotifyLogMapper extends BaseTradeMapper<MerchantNotifyLog> {

    default Optional<MerchantNotifyLog> findFirstByUserIdAndDigestAndSentAtAfter( Long userId, String digest, Instant since) {
    return Optional.ofNullable(selectOne(Wrappers.<MerchantNotifyLog>lambdaQuery().eq(MerchantNotifyLog::getUserId, userId).eq(MerchantNotifyLog::getDigest, digest).gt(MerchantNotifyLog::getSentAt, since).last("LIMIT 1")));
    }

}
