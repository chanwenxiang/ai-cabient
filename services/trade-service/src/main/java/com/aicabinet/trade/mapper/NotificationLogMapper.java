package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.NotificationLog;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface NotificationLogMapper extends BaseTradeMapper<NotificationLog> {

    NotificationLog findByIdForUpdateRaw(@Param("id") Long id);

    default Optional<NotificationLog> findByIdForUpdate(Long id) {
        return Optional.ofNullable(findByIdForUpdateRaw(id));
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

    /** page 为 0-based。 */
    default Page<NotificationLog> searchRecent(int page, int size) {
        return selectPage(new Page<>(page + 1L, size),
                Wrappers.<NotificationLog>lambdaQuery().orderByDesc(NotificationLog::getCreatedAt));
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

    default List<NotificationLog> findOpsRecent(Long userId, int limit) {
        return selectList(Wrappers.<NotificationLog>lambdaQuery()
                .eq(NotificationLog::getAudience, "OPS")
                .eq(NotificationLog::getUserId, userId)
                .orderByDesc(NotificationLog::getCreatedAt)
                .last("LIMIT " + Math.min(Math.max(limit, 1), 100)));
    }

    default long countUnreadOps(Long userId) {
        Long c = selectCount(Wrappers.<NotificationLog>lambdaQuery()
                .eq(NotificationLog::getAudience, "OPS")
                .eq(NotificationLog::getUserId, userId)
                .isNull(NotificationLog::getReadAt));
        return c == null ? 0L : c;
    }
}
