package com.aicabinet.trade.service;

import com.aicabinet.common.dto.NotificationDto;
import com.aicabinet.common.dto.NotificationDispatchMessage;
import com.aicabinet.trade.domain.NotificationLog;
import com.aicabinet.trade.domain.NotificationTemplate;
import com.aicabinet.trade.config.NotificationProperties;
import com.aicabinet.trade.messaging.NotificationDispatchProducer;
import com.aicabinet.trade.mapper.NotificationLogMapper;
import com.aicabinet.trade.mapper.NotificationTemplateMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 消息触达：模板渲染 + 落库（IN_APP 消息中心）。
 * 预留微信订阅消息 / 短信渠道扩展点：默认先保证站内信与审计留痕。
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationTemplateMapper templateRepository;
    private final NotificationLogMapper logRepository;
    private final NotificationProperties notificationProperties;
    private final ConsumerNotifyPrefService notifyPrefService;
    private final ExternalNotificationDispatcher externalDispatcher;
    private final ObjectProvider<NotificationDispatchProducer> producerProvider;

    public NotificationService(NotificationTemplateMapper templateRepository,
                               NotificationLogMapper logRepository,
                               NotificationProperties notificationProperties,
                               ConsumerNotifyPrefService notifyPrefService,
                               ExternalNotificationDispatcher externalDispatcher,
                               ObjectProvider<NotificationDispatchProducer> producerProvider) {
        this.templateRepository = templateRepository;
        this.logRepository = logRepository;
        this.notificationProperties = notificationProperties;
        this.notifyPrefService = notifyPrefService;
        this.externalDispatcher = externalDispatcher;
        this.producerProvider = producerProvider;
    }

    public void notifyConsumer(Long userId, String templateCode, Map<String, String> params,
                               String bizType, String bizId) {
        send("CONSUMER", userId, null, templateCode, params, bizType, bizId);
    }

    public void notifyMerchant(String merchantId, String templateCode, Map<String, String> params,
                               String bizType, String bizId) {
        send("MERCHANT", null, merchantId, templateCode, params, bizType, bizId);
    }

    @Transactional
    public void send(String audience, Long userId, String merchantId, String templateCode,
                     Map<String, String> params, String bizType, String bizId) {
        NotificationTemplate tpl = templateRepository.findByCode(templateCode).orElse(null);
        if (tpl == null) {
            log.debug("notification template not found or inactive: {}", templateCode);
            return;
        }
        String title = render(tpl.getTitleTemplate(), params);
        String body = render(tpl.getBodyTemplate(), params);
        if (!notifyPrefService.isEnabled(userId, tpl.getCategory())) {
            return;
        }

        List<String> channelList = channels(tpl.getChannels(), tpl.getChannel());
        if (channelList.contains("IN_APP")) {
            saveLog(tpl.getTemplateCode(), "IN_APP", audience, userId, merchantId,
                    title, body, bizType, bizId);
        }
        boolean hasExternal = channelList.stream().anyMatch(c -> !"IN_APP".equals(c));
        if (!hasExternal || userId == null) {
            return;
        }
        NotificationDispatchMessage message = new NotificationDispatchMessage(
                tpl.getTemplateCode(), userId, title, body, bizType, bizId);
        if (notificationProperties.asyncEnabled()) {
            NotificationDispatchProducer producer = producerProvider.getIfAvailable();
            if (producer != null) {
                producer.publish(message);
                return;
            }
            log.warn("notify async enabled but producer unavailable, fallback sync");
        }
        externalDispatcher.dispatch(message);
    }

    private void saveLog(String templateCode, String channel, String audience, Long userId,
                         String merchantId, String title, String body, String bizType, String bizId) {
        NotificationLog record = new NotificationLog();
        record.setTemplateCode(templateCode);
        record.setChannel(channel);
        record.setAudience(audience);
        record.setUserId(userId);
        record.setMerchantId(merchantId);
        record.setTitle(title);
        record.setBody(body);
        record.setBizType(bizType);
        record.setBizId(bizId);
        record.setStatus("SENT");
        record.setCreatedAt(Instant.now());
        logRepository.save(record);
        log.info("notification sent channel={} template={} audience={} userId={} merchantId={}",
                channel, templateCode, audience, userId, merchantId);
    }

    private static java.util.List<String> channels(String channels, String fallback) {
        String raw = channels != null && !channels.isBlank() ? channels : fallback;
        if (raw == null || raw.isBlank()) {
            return List.of("IN_APP");
        }
        return java.util.Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .map(String::toUpperCase)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<NotificationDto> consumerNotifications(Long userId, int limit) {
        return logRepository.findConsumerRecent(userId, limit).stream().map(this::toDto).toList();
    }

    @Transactional(readOnly = true)
    public long consumerUnreadCount(Long userId) {
        return logRepository.countUnreadConsumer(userId);
    }

    @Transactional
    public void markConsumerRead(Long userId, Long id) {
        NotificationLog record = logRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "消息不存在"));
        if (record.getUserId() == null || !record.getUserId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权操作该消息");
        }
        if (record.getReadAt() == null) {
            record.setReadAt(Instant.now());
            logRepository.save(record);
        }
    }

    @Transactional
    public void markConsumerAllRead(Long userId) {
        List<NotificationLog> unread = logRepository.findConsumerRecent(userId, 100).stream()
                .filter(n -> n.getReadAt() == null)
                .toList();
        for (NotificationLog n : unread) {
            n.setReadAt(Instant.now());
            logRepository.save(n);
        }
    }

    @Transactional(readOnly = true)
    public List<NotificationDto> merchantNotifications(String merchantId, int limit) {
        return logRepository.findMerchantRecent(merchantId, limit).stream().map(this::toDto).toList();
    }

    @Transactional(readOnly = true)
    public long merchantUnreadCount(String merchantId) {
        return logRepository.countUnreadMerchant(merchantId);
    }

    @Transactional
    public void markMerchantRead(String merchantId, Long id) {
        NotificationLog record = logRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "消息不存在"));
        if (record.getMerchantId() == null || !record.getMerchantId().equals(merchantId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权操作该消息");
        }
        if (record.getReadAt() == null) {
            record.setReadAt(Instant.now());
            logRepository.save(record);
        }
    }

    @Transactional(readOnly = true)
    public List<NotificationDto> adminRecent(int limit) {
        return logRepository.findRecent(limit).stream().map(this::toDto).toList();
    }

    private NotificationDto toDto(NotificationLog n) {
        return new NotificationDto(
                n.getId(),
                n.getTitle(),
                n.getBody(),
                n.getTemplateCode(),
                n.getChannel(),
                n.getAudience(),
                n.getBizType(),
                n.getBizId(),
                n.getReadAt() != null,
                n.getReadAt(),
                n.getCreatedAt()
        );
    }

    private static String render(String template, Map<String, String> params) {
        if (template == null) {
            return "";
        }
        String out = template;
        if (params != null) {
            for (Map.Entry<String, String> e : params.entrySet()) {
                out = out.replace("{" + e.getKey() + "}", e.getValue() == null ? "" : e.getValue());
            }
        }
        return out;
    }
}
