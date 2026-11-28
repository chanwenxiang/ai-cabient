package com.aicabinet.trade.service;

import com.aicabinet.common.dto.NotificationDispatchMessage;
import com.aicabinet.trade.domain.NotificationDispatchOutbox;
import com.aicabinet.trade.mapper.NotificationDispatchOutboxMapper;
import com.aicabinet.trade.messaging.NotificationDispatchProducer;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

@Service
public class NotificationDispatchOutboxService {

    private static final Logger log = LoggerFactory.getLogger(NotificationDispatchOutboxService.class);
    private static final int BATCH_SIZE = 100;

    private final NotificationDispatchOutboxMapper outboxRepository;
    private final ObjectMapper objectMapper;
    private final ExternalNotificationDispatcher externalDispatcher;
    private final NotifyChannelSwitchService channelSwitch;
    private final ObjectProvider<NotificationDispatchProducer> producerProvider;
    private final DistributedLockService distributedLockService;

    public NotificationDispatchOutboxService(NotificationDispatchOutboxMapper outboxRepository,
                                             ObjectMapper objectMapper,
                                             ExternalNotificationDispatcher externalDispatcher,
                                             NotifyChannelSwitchService channelSwitch,
                                             ObjectProvider<NotificationDispatchProducer> producerProvider,
                                             DistributedLockService distributedLockService) {
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
        this.externalDispatcher = externalDispatcher;
        this.channelSwitch = channelSwitch;
        this.producerProvider = producerProvider;
        this.distributedLockService = distributedLockService;
    }

    public String enqueue(NotificationDispatchMessage message) {
        String key = idempotencyKey(message);
        Instant now = Instant.now();
        NotificationDispatchOutbox row = new NotificationDispatchOutbox();
        row.setIdempotencyKey(key);
        row.setPayloadJson(toJson(message));
        row.setStatus("PENDING");
        row.setRetryCount(0);
        row.setNextRetryAt(now);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        outboxRepository.insertIgnore(row);
        return key;
    }

    public int dispatchDue() {
        List<NotificationDispatchOutbox> due = outboxRepository.findDue(BATCH_SIZE);
        int sent = 0;
        for (NotificationDispatchOutbox row : due) {
            if (dispatchNow(row.getId())) {
                sent++;
            }
        }
        return sent;
    }

    public boolean dispatchNow(Long id) {
        if (id == null) {
            return false;
        }
        NotificationDispatchOutbox row = outboxRepository.findById(id);
        if (row == null || "SENT".equals(row.getStatus())) {
            return false;
        }
        return dispatchRow(row);
    }

    public boolean dispatchByKey(String key) {
        NotificationDispatchOutbox row = outboxRepository.findByKey(key);
        if (row == null || "SENT".equals(row.getStatus())) {
            return false;
        }
        return dispatchRow(row);
    }

    public boolean dispatchDirect(NotificationDispatchMessage message) {
        try {
            if (channelSwitch.asyncEnabled()) {
                NotificationDispatchProducer producer = producerProvider.getIfAvailable();
                if (producer != null) {
                    producer.publish(message);
                    return true;
                }
            }
            return externalDispatcher.dispatchSuccessful(message);
        } catch (Exception e) {
            log.warn("direct notification dispatch failed userId={} template={}",
                    message.userId(), message.templateCode(), e);
            return false;
        }
    }

    private boolean dispatchRow(NotificationDispatchOutbox row) {
        Long id = row.getId();
        String lockKey = String.format("notify:outbox:%d", id);
        if (!distributedLockService.tryLock(lockKey, 60, 0)) {
            return false;
        }
        try {
            NotificationDispatchOutbox current = outboxRepository.findById(id);
            if (current == null || "SENT".equals(current.getStatus())) {
                return false;
            }
            row = current;
            NotificationDispatchMessage message = objectMapper.readValue(row.getPayloadJson(),
                    NotificationDispatchMessage.class);
            boolean success;
            if (channelSwitch.asyncEnabled()) {
                NotificationDispatchProducer producer = producerProvider.getIfAvailable();
                if (producer != null) {
                    producer.publish(message);
                    success = true;
                } else {
                    success = externalDispatcher.dispatchSuccessful(message);
                }
            } else {
                success = externalDispatcher.dispatchSuccessful(message);
            }
            if (success) {
                outboxRepository.markSent(id);
                return true;
            }
            markFailed(row, "外部渠道发送失败");
            return false;
        } catch (Exception e) {
            markFailed(row, e.getClass().getSimpleName() + ":" + e.getMessage());
            log.warn("notification outbox dispatch failed id={} retryCount={}", id, row.getRetryCount(), e);
            return false;
        } finally {
            distributedLockService.unlock(lockKey);
        }
    }

    private void markFailed(NotificationDispatchOutbox row, String error) {
        long delayMinutes = Math.min(1L << Math.min(row.getRetryCount(), 6), 360);
        Instant nextRetryAt = Instant.now().plus(Duration.ofMinutes(delayMinutes));
        outboxRepository.markFailed(row.getId(), nextRetryAt, truncate(error));
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 1000 ? value : value.substring(0, 1000);
    }

    private String toJson(NotificationDispatchMessage message) {
        try {
            return objectMapper.writeValueAsString(message);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize notification dispatch", e);
        }
    }

    public static String idempotencyKey(NotificationDispatchMessage message) {
        if ("refund_success".equals(message.templateCode())
                && message.clientMsgId() != null && !message.clientMsgId().isBlank()) {
            return message.clientMsgId().trim();
        }
        String raw = String.format("%s|%s|%s|%s|%s|%s",
                nullToEmpty(message.audience()),
                nullToEmpty(message.userId()),
                nullToEmpty(message.merchantId()),
                nullToEmpty(message.templateCode()),
                nullToEmpty(message.bizType()),
                nullToEmpty(message.bizId()));
        if (message.bizId() == null || message.bizId().isBlank()) {
            raw = String.format("%s|%s|%s", raw,
                    nullToEmpty(message.title()), nullToEmpty(message.body()));
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String nullToEmpty(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
