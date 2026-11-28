package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.NotificationDispatchOutbox;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface NotificationDispatchOutboxMapper {

    @Insert("""
            INSERT INTO notification_dispatch_outbox
                (idempotency_key, payload_json, status, retry_count, next_retry_at, created_at, updated_at)
            VALUES
                (#{idempotencyKey}, #{payloadJson}, 'PENDING', 0, #{nextRetryAt}, #{createdAt}, #{updatedAt})
            ON CONFLICT (idempotency_key) DO NOTHING
            """)
    int insertIgnore(NotificationDispatchOutbox row);

    @Select("""
            SELECT * FROM notification_dispatch_outbox
            WHERE status IN ('PENDING', 'FAILED') AND next_retry_at <= NOW()
            ORDER BY next_retry_at, id
            LIMIT #{limit}
            """)
    List<NotificationDispatchOutbox> findDue(@Param("limit") int limit);

    @Select("SELECT * FROM notification_dispatch_outbox WHERE id = #{id}")
    NotificationDispatchOutbox findById(@Param("id") Long id);

    @Select("SELECT * FROM notification_dispatch_outbox WHERE idempotency_key = #{key}")
    NotificationDispatchOutbox findByKey(@Param("key") String key);

    @Update("""
            UPDATE notification_dispatch_outbox
            SET status = 'SENT', sent_at = NOW(), updated_at = NOW(), last_error = NULL
            WHERE id = #{id}
            """)
    int markSent(@Param("id") Long id);

    @Update("""
            UPDATE notification_dispatch_outbox
            SET status = 'FAILED', retry_count = retry_count + 1,
                next_retry_at = #{nextRetryAt}, last_error = #{error}, updated_at = NOW()
            WHERE id = #{id}
            """)
    int markFailed(@Param("id") Long id, @Param("nextRetryAt") Instant nextRetryAt,
                   @Param("error") String error);
}
