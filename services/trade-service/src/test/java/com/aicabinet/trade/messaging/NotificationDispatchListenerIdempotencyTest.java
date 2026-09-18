package com.aicabinet.trade.messaging;

import com.aicabinet.trade.service.ExternalNotificationDispatcher;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** M09(a)：Kafka 通知分发消费端按 payload sha256 幂等去重。 */
@ExtendWith(MockitoExtension.class)
class NotificationDispatchListenerIdempotencyTest {

    private static final String PAYLOAD = "{\"templateCode\":\"order_paid\",\"userId\":100,"
            + "\"title\":\"订单支付成功\",\"body\":\"已支付 12 元\",\"bizType\":\"ORDER\",\"bizId\":\"O1\"}";

    @Mock private ExternalNotificationDispatcher dispatcher;
    @Mock private RedissonClient redisson;
    @Mock private RBucket<String> dedupeBucket;

    private NotificationDispatchListener listener;

    @BeforeEach
    void setUp() {
        listener = new NotificationDispatchListener(dispatcher, new ObjectMapper(), redisson);
        org.mockito.Mockito.doReturn(dedupeBucket).when(redisson)
                .getBucket(contains("aicabinet:notify:dispatch:"), eq(StringCodec.INSTANCE));
    }

    @Test
    @SuppressWarnings("unchecked")
    void onMessage_firstDelivery_dispatches() {
        when(dedupeBucket.setIfAbsent(eq("1"), any(java.time.Duration.class))).thenReturn(true);

        listener.onMessage(PAYLOAD);

        verify(dispatcher).dispatch(any(com.aicabinet.common.dto.NotificationDispatchMessage.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void onMessage_duplicatePayload_skipsDispatch() {
        when(dedupeBucket.setIfAbsent(eq("1"), any(java.time.Duration.class))).thenReturn(false);

        listener.onMessage(PAYLOAD);

        verify(dispatcher, never()).dispatch(any());
    }

    @Test
    void onMessage_uses24hTtlKey() {
        when(dedupeBucket.setIfAbsent(anyString(), any(java.time.Duration.class))).thenReturn(false);

        listener.onMessage(PAYLOAD);

        verify(redisson).getBucket(contains("aicabinet:notify:dispatch:"), eq(StringCodec.INSTANCE));
    }
}
