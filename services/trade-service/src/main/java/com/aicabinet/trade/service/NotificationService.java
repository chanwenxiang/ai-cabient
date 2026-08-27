package com.aicabinet.trade.service;

import com.aicabinet.common.dto.AdminManualNotificationRequest;
import com.aicabinet.common.dto.NotificationDto;
import com.aicabinet.common.dto.NotificationDispatchMessage;
import com.aicabinet.common.dto.PageResult;
import com.aicabinet.trade.domain.NotificationLog;
import com.aicabinet.trade.domain.NotificationTemplate;
import com.aicabinet.trade.config.NotificationProperties;
import com.aicabinet.trade.messaging.NotificationDispatchProducer;
import com.aicabinet.trade.mapper.NotificationLogMapper;
import com.aicabinet.trade.mapper.NotificationTemplateMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 消息触达：模板渲染 + 落库（IN_APP 消息中心）。
 * 预留微信订阅消息 / 短信渠道扩展点：默认先保证站内信与审计留痕。
 */
@Service
public class NotificationService {
    private static final String CONSUMER = "CONSUMER";
    private static final String MERCHANT = "MERCHANT";
    private static final String IN_APP = "IN_APP";
    private static final String LITERAL = "消息不存在";


    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationTemplateMapper templateRepository;
    private final NotificationLogMapper logRepository;
    private final NotificationProperties notificationProperties;
    private final ConsumerNotifyPrefService notifyPrefService;
    private final ExternalNotificationDispatcher externalDispatcher;
    private final ObjectProvider<NotificationDispatchProducer> producerProvider;
    private final DistributedLockService distributedLockService;
    /** 经 Spring 代理调用本类 @Transactional 方法，避免自调用失效。 */
    private final NotificationService self;

    public NotificationService(NotificationTemplateMapper templateRepository,
                               NotificationLogMapper logRepository,
                               NotificationProperties notificationProperties,
                               ConsumerNotifyPrefService notifyPrefService,
                               ExternalNotificationDispatcher externalDispatcher,
                               ObjectProvider<NotificationDispatchProducer> producerProvider,
                               DistributedLockService distributedLockService, @Lazy NotificationService self) {
        this.templateRepository = templateRepository;
        this.logRepository = logRepository;
        this.notificationProperties = notificationProperties;
        this.notifyPrefService = notifyPrefService;
        this.externalDispatcher = externalDispatcher;
        this.producerProvider = producerProvider;
        this.distributedLockService = distributedLockService;
        this.self = self;
    }

    @Transactional
    public void notifyConsumer(Long userId, String templateCode, Map<String, String> params,
                               String bizType, String bizId) {
        self.send(CONSUMER, userId, null, templateCode, params, bizType, bizId);
    }

    @Transactional
    public void notifyMerchant(String merchantId, String templateCode, Map<String, String> params,
                               String bizType, String bizId) {
        self.send(MERCHANT, null, merchantId, templateCode, params, bizType, bizId);
    }

    /** 运营后台站内信：不依赖消费者偏好开关，直接落库。 */
    @Transactional
    public void notifyOpsInApp(Long userId, String title, String body, String bizType, String bizId) {
        if (userId == null || userId < com.aicabinet.common.constants.CabinetConstants.OPERATOR_USER_ID_START) {
            return;
        }
        saveLog(new NotificationLogDraft("OPS_INBOX", IN_APP,
                new NotificationLogDraft.NotificationTarget("OPS", userId, null),
                new NotificationLogDraft.NotificationContent(title, body, bizType, bizId)));
    }

    @Transactional(readOnly = true)
    public List<NotificationDto> opsNotifications(Long userId, int limit) {
        return logRepository.findOpsRecent(userId, limit).stream().map(this::toDto).toList();
    }

    @Transactional(readOnly = true)
    public long opsUnreadCount(Long userId) {
        return logRepository.countUnreadOps(userId);
    }

    @Transactional
    public void markOpsRead(Long userId, Long id) {
        runWithNotificationLogLock(id, () -> {
            NotificationLog logEntry = logRepository.findByIdForUpdate(id)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, LITERAL));
            if (logEntry.getUserId() == null || !logEntry.getUserId().equals(userId)
                    || !"OPS".equals(logEntry.getAudience())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权操作该消息");
            }
            if (logEntry.getReadAt() == null) {
                logEntry.setReadAt(Instant.now());
                logRepository.save(logEntry);
            }
            return null;
        });
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
        if (channelList.contains(IN_APP)) {
            saveLog(new NotificationLogDraft(tpl.getTemplateCode(), IN_APP,
                    new NotificationLogDraft.NotificationTarget(audience, userId, merchantId),
                    new NotificationLogDraft.NotificationContent(title, body, bizType, bizId)));
        }
        boolean hasExternal = channelList.stream().anyMatch(c -> !IN_APP.equals(c));
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

    private record NotificationLogDraft(
            String templateCode, String channel, NotificationTarget target, NotificationContent content) {
        private record NotificationTarget(String audience, Long userId, String merchantId) {}
        private record NotificationContent(String title, String body, String bizType, String bizId) {}
    }

    private void saveLog(NotificationLogDraft draft) {
        NotificationLog logEntry = new NotificationLog();
        logEntry.setTemplateCode(draft.templateCode());
        logEntry.setChannel(draft.channel());
        logEntry.setAudience(draft.target().audience());
        logEntry.setUserId(draft.target().userId());
        logEntry.setMerchantId(draft.target().merchantId());
        logEntry.setTitle(draft.content().title());
        logEntry.setBody(draft.content().body());
        logEntry.setBizType(draft.content().bizType());
        logEntry.setBizId(draft.content().bizId());
        logEntry.setStatus("SENT");
        logEntry.setCreatedAt(Instant.now());
        logRepository.save(logEntry);
        log.info("notification sent channel={} template={} audience={} userId={} merchantId={}",
                draft.channel(), draft.templateCode(), draft.target().audience(),
                draft.target().userId(), draft.target().merchantId());
    }

    private static java.util.List<String> channels(String channels, String fallback) {
        String raw = channels != null && !channels.isBlank() ? channels : fallback;
        if (raw == null || raw.isBlank()) {
            return List.of(IN_APP);
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
        runWithNotificationLogLock(id, () -> {
            doMarkConsumerRead(userId, id);
            return null;
        });
    }

    private void doMarkConsumerRead(Long userId, Long id) {
        NotificationLog logEntry = logRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, LITERAL));
        if (logEntry.getUserId() == null || !logEntry.getUserId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权操作该消息");
        }
        if (logEntry.getReadAt() == null) {
            logEntry.setReadAt(Instant.now());
            logRepository.save(logEntry);
        }
    }

    @Transactional
    public void markConsumerAllRead(Long userId) {
        runWithConsumerNotificationLock(userId, () -> {
            doMarkConsumerAllRead(userId);
            return null;
        });
    }

    private void doMarkConsumerAllRead(Long userId) {
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
        runWithNotificationLogLock(id, () -> {
            doMarkMerchantRead(merchantId, id);
            return null;
        });
    }

    private void doMarkMerchantRead(String merchantId, Long id) {
        NotificationLog logEntry = logRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, LITERAL));
        if (logEntry.getMerchantId() == null || !logEntry.getMerchantId().equals(merchantId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权操作该消息");
        }
        if (logEntry.getReadAt() == null) {
            logEntry.setReadAt(Instant.now());
            logRepository.save(logEntry);
        }
    }

    @Transactional(readOnly = true)
    public List<NotificationDto> adminRecent(int limit) {
        return logRepository.findRecent(limit).stream().map(this::toDto).toList();
    }

    @Transactional(readOnly = true)
    public PageResult<NotificationDto> adminPage(int page, int size) {
        int p = Math.max(page, 0);
        int s = Math.min(Math.max(size, 1), 100);
        Page<NotificationLog> result = logRepository.searchRecent(p, s);
        return new PageResult<>(result.getRecords().stream().map(this::toDto).toList(), p, s, result.getTotal());
    }

    /** 运营手动发站内信（不依赖模板）。 */
    @Transactional
    public NotificationDto sendManual(Long operatorId, AdminManualNotificationRequest body) {
        if (body == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请求体不能为空");
        }
        String audience = body.audience().trim().toUpperCase();
        if (!CONSUMER.equals(audience) && !MERCHANT.equals(audience)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "受众仅支持 CONSUMER / MERCHANT");
        }
        String title = body.title().trim();
        String content = body.body().trim();
        if (title.isEmpty() || content.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "标题与内容不能为空");
        }
        Long userId = body.userId();
        String merchantId = body.merchantId() == null || body.merchantId().isBlank()
                ? null : body.merchantId().trim();
        if (CONSUMER.equals(audience) && userId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "发给消费者需填写用户ID");
        }
        if (MERCHANT.equals(audience) && merchantId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "发给商户需填写商户ID");
        }
        if (CONSUMER.equals(audience)) {
            return runWithConsumerNotificationLock(userId, () -> doSendManual(operatorId, body, audience,
                    title, content, userId, merchantId));
        }
        return runWithMerchantNotificationLock(merchantId, () -> doSendManual(operatorId, body, audience,
                title, content, userId, merchantId));
    }

    private NotificationDto doSendManual(Long operatorId, AdminManualNotificationRequest body, String audience,
                                         String title, String content, Long userId, String merchantId) {
        String bizType = body.bizType() == null || body.bizType().isBlank() ? "OPS_MANUAL" : body.bizType().trim();
        String bizId;
        if (body.bizId() == null || body.bizId().isBlank()) {
            long operatorKey = operatorId == null ? 0 : operatorId;
            bizId = "OPS-" + operatorKey;
        } else {
            bizId = body.bizId().trim();
        }
        NotificationLog logEntry = new NotificationLog();
        logEntry.setTemplateCode("OPS_MANUAL");
        logEntry.setChannel(IN_APP);
        logEntry.setAudience(audience);
        logEntry.setUserId(userId);
        logEntry.setMerchantId(merchantId);
        logEntry.setTitle(title);
        logEntry.setBody(content);
        logEntry.setBizType(bizType);
        logEntry.setBizId(bizId);
        logEntry.setStatus("SENT");
        logEntry.setCreatedAt(Instant.now());
        logRepository.save(logEntry);
        log.info("manual notification sent by={} audience={} userId={} merchantId={} id={}",
                operatorId, audience, userId, merchantId, logEntry.getId());
        return toDto(logEntry);
    }

    static String consumerNotificationLockKey(long userId) {
        return "notification:consumer:" + userId;
    }

    static String merchantNotificationLockKey(String merchantId) {
        return "notification:merchant:" + merchantId;
    }

    static String notificationLogLockKey(long logId) {
        return "notification:log:" + logId;
    }

    private <T> T runWithConsumerNotificationLock(long userId, Supplier<T> action) {
        return runWithLock(consumerNotificationLockKey(userId), action);
    }

    private <T> T runWithMerchantNotificationLock(String merchantId, Supplier<T> action) {
        return runWithLock(merchantNotificationLockKey(merchantId), action);
    }

    private <T> T runWithNotificationLogLock(long logId, Supplier<T> action) {
        return runWithLock(notificationLogLockKey(logId), action);
    }

    private <T> T runWithLock(String lockKey, Supplier<T> action) {
        if (!distributedLockService.tryLock(lockKey, 60, 5)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "消息处理中，请稍后重试");
        }
        try {
            return action.get();
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage(), e);
        } finally {
            distributedLockService.unlock(lockKey);
        }
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
