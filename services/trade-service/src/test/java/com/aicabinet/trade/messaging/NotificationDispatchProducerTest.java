package com.aicabinet.trade.messaging;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aicabinet.common.constants.KafkaTopics;
import com.aicabinet.common.dto.NotificationDispatchMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

@ExtendWith(MockitoExtension.class)
class NotificationDispatchProducerTest {

    @Mock private KafkaTemplate<String, String> kafkaTemplate;

    private NotificationDispatchProducer producer(Duration timeout) {
        return new NotificationDispatchProducer(kafkaTemplate, new ObjectMapper(), timeout);
    }

    private static NotificationDispatchMessage message() {
        return new NotificationDispatchMessage("order_paid", 100L, "订单支付成功", "已支付 12 元",
                "ORDER", "O1", Map.of("amount", "12.00"), "client-1", "CONSUMER", null);
    }

    @Test
    void publish_waitsForBrokerAck() {
        CompletableFuture<SendResult<String, String>> ack = new CompletableFuture<>();
        ack.complete(mock(SendResult.class));
        when(kafkaTemplate.send(eq(KafkaTopics.NOTIFY_DISPATCH_REQUEST), eq("100"), anyString()))
                .thenReturn(ack);

        assertDoesNotThrow(() -> producer(Duration.ofSeconds(1)).publish(message()));
    }

    @Test
    void publish_brokerFailure_propagates() {
        CompletableFuture<SendResult<String, String>> ack = new CompletableFuture<>();
        ack.completeExceptionally(new ExecutionException(new IllegalStateException("broker down")));
        when(kafkaTemplate.send(eq(KafkaTopics.NOTIFY_DISPATCH_REQUEST), eq("100"), anyString()))
                .thenReturn(ack);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> producer(Duration.ofSeconds(1)).publish(message()));

        assertTrue(error.getMessage().contains("failed to publish"));
    }

    @Test
    void publish_ackTimeout_failsInsteadOfFalseSuccess() {
        when(kafkaTemplate.send(eq(KafkaTopics.NOTIFY_DISPATCH_REQUEST), eq("100"), anyString()))
                .thenReturn(new CompletableFuture<>());

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> producer(Duration.ofMillis(10)).publish(message()));

        assertTrue(error.getCause() instanceof java.util.concurrent.TimeoutException);
    }
}
