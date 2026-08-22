package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.NotificationLog;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface NotificationLogMapper extends BaseTradeMapper<NotificationLog> {

    NotificationLog _findByIdForUpdateRaw(@Param("id") Long id);

    default Optional<NotificationLog> findByIdForUpdate(Long id) {
        return Optional.ofNullable(_findByIdForUpdateRaw(id));
    }

    default List<NotificationLog> findConsumerRecent(Long userId, int limit) {
        return selectList(Wrappers.<NotificationLog>lambdaQuery()
                .eq(NotificationLog::getAudience, "CONSUMER")
                .eq(NotificationLog::getUserId, userId)
                .orderByDesc(NotificationLog::getCreatedAt)
                .last("LIMIT " + Math.min(Math.max(limit, 1), 100)));
    }

    default List<NotificationLog> findMerchantRecent(String merchantId, int limit) {
        return selectList(Wrappers.<NotificationLog>lambdaQuery()
                .eq(NotificationLog::getAudience, "MERCHANT")
                .eq(NotificationLog::getMerchantId, merchantId)
                .orderByDesc(NotificationLog::getCreatedAt)
                .last("LIMIT " + Math.min(Math.max(limit, 1), 100)));
    }

    default List<NotificationLog> findRecent(int limit) {
        return selectList(Wrappers.<NotificationLog>lambdaQuery()
                .orderByDesc(NotificationLog::getCreatedAt)
                .last("LIMIT " + Math.min(Math.max(limit, 1), 200)));
    }

    default long countUnreadConsumer(Long userId) {
        Long c = selectCount(Wrappers.<NotificationLog>lambdaQuery()
                .eq(NotificationLog::getAudience, "CONSUMER")
                .eq(NotificationLog::getUserId, userId)
                .isNull(NotificationLog::getReadAt));
        return c == null ? 0L : c;
    }

    default long countUnreadMerchant(String merchantId) {
        Long c = selectCount(Wrappers.<NotificationLog>lambdaQuery()
                .eq(NotificationLog::getAudience, "MERCHANT")
                .eq(NotificationLog::getMerchantId, merchantId)
                .isNull(NotificationLog::getReadAt));
        return c == null ? 0L : c;
    }
}
