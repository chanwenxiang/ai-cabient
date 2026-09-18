package com.aicabinet.trade.service;

import com.aicabinet.common.dto.NotificationDispatchMessage;
import com.aicabinet.trade.config.NotificationProperties;
import com.aicabinet.trade.config.WeChatMiniAppProperties;
import com.aicabinet.trade.domain.NotificationLog;
import com.aicabinet.trade.domain.NotificationTemplate;
import com.aicabinet.trade.mapper.NotificationLogMapper;
import com.aicabinet.trade.mapper.NotificationTemplateMapper;
import com.aicabinet.trade.domain.UserInfo;
import com.aicabinet.trade.mapper.UserInfoMapper;
import com.aicabinet.trade.sms.WebhookSmsSender;
import com.aicabinet.trade.wechat.WeChatMiniAppClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;

/** 外部渠道通知分发：微信订阅消息 / 短信；同步与异步（Kafka 消费）共用。 */
@Component
public class ExternalNotificationDispatcher {

    private static final Logger log = LoggerFactory.getLogger(ExternalNotificationDispatcher.class);

    private final NotificationLogMapper logRepository;
    private final UserInfoMapper userInfoRepository;
    private final NotificationTemplateMapper templateRepository;
    private final WeChatMiniAppClient weChatMiniAppClient;
    private final WeChatMiniAppProperties weChatMiniAppProperties;
    private final WebhookSmsSender smsSender;
    private final NotificationProperties notificationProperties;

    public ExternalNotificationDispatcher(NotificationLogMapper logRepository,
                                          UserInfoMapper userInfoRepository,
                                          NotificationTemplateMapper templateRepository,
                                          WeChatMiniAppClient weChatMiniAppClient,
                                          WeChatMiniAppProperties weChatMiniAppProperties,
                                          WebhookSmsSender smsSender,
                                          NotificationProperties notificationProperties) {
        this.logRepository = logRepository;
        this.userInfoRepository = userInfoRepository;
        this.templateRepository = templateRepository;
        this.weChatMiniAppClient = weChatMiniAppClient;
        this.weChatMiniAppProperties = weChatMiniAppProperties;
        this.smsSender = smsSender;
        this.notificationProperties = notificationProperties;
    }

    /** 按消息渠道配置分发微信订阅消息与短信；单渠道失败只记日志，不抛出。 */
    public void dispatch(NotificationDispatchMessage msg) {
        if (msg.userId() == null) {
            return;
        }
        if (notificationProperties.wechatEnabled()) {
            try {
                sendWechat(msg);
            } catch (Exception e) {
                log.warn("wechat notify failed userId={} template={}: {}",
                        msg.userId(), msg.templateCode(), e.toString());
            }
        }
        if (notificationProperties.smsEnabled()) {
            try {
                sendSms(msg);
            } catch (Exception e) {
                log.warn("sms notify failed userId={} template={}: {}",
                        msg.userId(), msg.templateCode(), e.toString());
            }
        }
    }

    private void sendWechat(NotificationDispatchMessage msg) {
        UserInfo user = userInfoRepository.findById(msg.userId()).orElse(null);
        if (user == null || user.getWxOpenId() == null || user.getWxOpenId().isBlank()) {
            return;
        }
        boolean ok = weChatMiniAppClient.sendSubscribeMessage(
                user.getWxOpenId(),
                weChatMiniAppProperties.resolveConsumerTemplateId(),
                weChatMiniAppProperties.resolveConsumerNotifyPage(),
                Map.of("thing1", msg.title(), "thing2", msg.body()));
        if (ok) {
            saveLog(msg, "WECHAT_SUBSCRIBE");
        }
    }

    private void sendSms(NotificationDispatchMessage msg) {
        UserInfo user = userInfoRepository.findById(msg.userId()).orElse(null);
        if (user == null || user.getPhoneNumber() == null || user.getPhoneNumber().isBlank()) {
            return;
        }
        if (!smsChannelConfigured(msg.templateCode())) {
            // M09：模板未配置短信渠道（如微信-only）时不得因全局 sms-enabled 而双发
            log.info("sms skipped: template {} has no SMS channel, userId={}", msg.templateCode(), msg.userId());
            return;
        }
        smsSender.sendMessage(user.getPhoneNumber(), msg.title() + "：" + msg.body());
        saveLog(msg, "SMS");
    }

    /**
     * M09：按模板 channels（逗号分隔 IN_APP/WECHAT_SUBSCRIBE/SMS）判断短信渠道是否启用。
     * 消息体只带 templateCode，故回查模板；模板缺失/无模板上下文时保持既有直发行为。
     */
    private boolean smsChannelConfigured(String templateCode) {
        if (templateCode == null || templateCode.isBlank()) {
            return true;
        }
        NotificationTemplate template = templateRepository.findByCode(templateCode).orElse(null);
        if (template == null) {
            return true;
        }
        String raw = template.getChannels() != null && !template.getChannels().isBlank()
                ? template.getChannels()
                : template.getChannel();
        if (raw == null || raw.isBlank()) {
            return false;
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .map(c -> c.toUpperCase(Locale.ROOT))
                .anyMatch("SMS"::equals);
    }

    private void saveLog(NotificationDispatchMessage msg, String channel) {
        NotificationLog logEntry = new NotificationLog();
        logEntry.setTemplateCode(msg.templateCode());
        logEntry.setChannel(channel);
        logEntry.setAudience("CONSUMER");
        logEntry.setUserId(msg.userId());
        logEntry.setTitle(msg.title());
        logEntry.setBody(msg.body());
        logEntry.setBizType(msg.bizType());
        logEntry.setBizId(msg.bizId());
        logEntry.setStatus("SENT");
        logEntry.setCreatedAt(Instant.now());
        logRepository.save(logEntry);
        log.info("external notification sent channel={} template={} userId={}",
                channel, msg.templateCode(), msg.userId());
    }
}
