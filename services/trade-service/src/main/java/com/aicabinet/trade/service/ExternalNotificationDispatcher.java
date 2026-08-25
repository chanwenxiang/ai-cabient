package com.aicabinet.trade.service;

import com.aicabinet.common.dto.NotificationDispatchMessage;
import com.aicabinet.trade.config.NotificationProperties;
import com.aicabinet.trade.config.WeChatMiniAppProperties;
import com.aicabinet.trade.domain.NotificationLog;
import com.aicabinet.trade.domain.UserInfo;
import com.aicabinet.trade.mapper.NotificationLogMapper;
import com.aicabinet.trade.mapper.UserInfoMapper;
import com.aicabinet.trade.sms.WebhookSmsSender;
import com.aicabinet.trade.wechat.WeChatMiniAppClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;

/** 外部渠道通知分发：微信订阅消息 / 短信；同步与异步（Kafka 消费）共用。 */
@Component
public class ExternalNotificationDispatcher {

    private static final Logger log = LoggerFactory.getLogger(ExternalNotificationDispatcher.class);

    private final NotificationLogMapper logRepository;
    private final UserInfoMapper userInfoRepository;
    private final WeChatMiniAppClient weChatMiniAppClient;
    private final WeChatMiniAppProperties weChatMiniAppProperties;
    private final WebhookSmsSender smsSender;
    private final NotificationProperties notificationProperties;

    public ExternalNotificationDispatcher(NotificationLogMapper logRepository,
                                          UserInfoMapper userInfoRepository,
                                          WeChatMiniAppClient weChatMiniAppClient,
                                          WeChatMiniAppProperties weChatMiniAppProperties,
                                          WebhookSmsSender smsSender,
                                          NotificationProperties notificationProperties) {
        this.logRepository = logRepository;
        this.userInfoRepository = userInfoRepository;
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
        smsSender.sendMessage(user.getPhoneNumber(), msg.title() + "：" + msg.body());
        saveLog(msg, "SMS");
    }

    private void saveLog(NotificationDispatchMessage msg, String channel) {
        NotificationLog record = new NotificationLog();
        record.setTemplateCode(msg.templateCode());
        record.setChannel(channel);
        record.setAudience("CONSUMER");
        record.setUserId(msg.userId());
        record.setTitle(msg.title());
        record.setBody(msg.body());
        record.setBizType(msg.bizType());
        record.setBizId(msg.bizId());
        record.setStatus("SENT");
        record.setCreatedAt(Instant.now());
        logRepository.save(record);
        log.info("external notification sent channel={} template={} userId={}",
                channel, msg.templateCode(), msg.userId());
    }
}
