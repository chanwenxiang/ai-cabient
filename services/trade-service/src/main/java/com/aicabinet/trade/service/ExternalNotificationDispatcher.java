package com.aicabinet.trade.service;

import com.aicabinet.common.dto.NotificationDispatchMessage;
import com.aicabinet.trade.config.WeChatMiniAppProperties;
import com.aicabinet.trade.domain.NotificationLog;
import com.aicabinet.trade.domain.NotificationTemplate;
import com.aicabinet.trade.mapper.NotificationLogMapper;
import com.aicabinet.trade.mapper.NotificationTemplateMapper;
import com.aicabinet.trade.domain.UserInfo;
import com.aicabinet.trade.mapper.UserInfoMapper;
import com.aicabinet.trade.sms.WebhookSmsSender;
import com.aicabinet.trade.sms.AliyunSmsSender;
import com.aicabinet.trade.wechat.WeChatMiniAppClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import com.aicabinet.trade.wechat.MiniAppTarget;

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
    private final NotifyChannelSwitchService channelSwitch;
    private final AliyunSmsSender aliyunSmsSender;
    private final SystemConfigService systemConfigService;

    public ExternalNotificationDispatcher(NotificationLogMapper logRepository,
                                          UserInfoMapper userInfoRepository,
                                          NotificationTemplateMapper templateRepository,
                                          WeChatMiniAppClient weChatMiniAppClient,
                                          WeChatMiniAppProperties weChatMiniAppProperties,
                                          WebhookSmsSender smsSender,
                                          NotifyChannelSwitchService channelSwitch,
                                          AliyunSmsSender aliyunSmsSender,
                                          SystemConfigService systemConfigService) {
        this.logRepository = logRepository;
        this.userInfoRepository = userInfoRepository;
        this.templateRepository = templateRepository;
        this.weChatMiniAppClient = weChatMiniAppClient;
        this.weChatMiniAppProperties = weChatMiniAppProperties;
        this.smsSender = smsSender;
        this.channelSwitch = channelSwitch;
        this.aliyunSmsSender = aliyunSmsSender;
        this.systemConfigService = systemConfigService;
    }

    /** 按消息渠道配置分发微信订阅消息与短信；单渠道失败只记日志，不抛出。 */
    public void dispatch(NotificationDispatchMessage msg) {
        if (msg.userId() == null) {
            return;
        }
        if (channelSwitch.wechatEnabled()) {
            try {
                sendWechat(msg);
            } catch (Exception e) {
                log.warn("wechat notify failed userId={} template={}: {}",
                        msg.userId(), msg.templateCode(), e.toString());
            }
        }
        if (channelSwitch.smsEnabled()) {
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
                Map.of("thing1", clipToThingLimit(msg.title()), "thing2", clipToThingLimit(msg.body())),
                MiniAppTarget.CONSUMER);
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
        if (sendViaAliyunTemplate(msg, user.getPhoneNumber())) {
            saveLog(msg, "SMS");
            return;
        }
        smsSender.sendMessage(user.getPhoneNumber(), msg.title() + "：" + msg.body());
        saveLog(msg, "SMS");
    }

    /**
     * 阿里云通知短信（模板渲染）：消息带结构化参数（smsParams）且阿里云四件套配齐时走模板发送。
     * <p>键名映射：催缴链路内部参数是 orderId/amount（V325 站内信模板 {orderId} 键名，固定迁移不可改），
     * 阿里云催缴模板变量是 order_no/amount/service_phone；并补 service_phone=运营台客服电话，
     * 清洗为纯数字以适配阿里云「电话号码」属性（5~11 位）。阿里云未配置/无参数 ⇒ 返回 false
     * 回退 webhook 文本通道（既有行为不变）。发送失败也回退 webhook，催缴不因单通道故障丢失。</p>
     */
    private boolean sendViaAliyunTemplate(NotificationDispatchMessage msg, String phoneNumber) {
        if (msg.smsParams() == null || msg.smsParams().isEmpty() || !aliyunSmsSender.isConfigured()) {
            return false;
        }
        String templateCode = systemConfigService.getValue(
                SystemConfigService.NOTIFY_SMS_DUNNING_TEMPLATE, "SMS_512661664");
        Map<String, String> params = new java.util.LinkedHashMap<>(msg.smsParams());
        if (params.get("order_no") == null) {
            params.put("order_no", params.get("orderId"));
        }
        params.put("service_phone", sanitizeDigits(systemConfigService.getValue(
                SystemConfigService.CONSUMER_SERVICE_PHONE, "")));
        try {
            aliyunSmsSender.sendMessage(phoneNumber, templateCode, params);
            log.info("sms sent via aliyun template={} userId={}", templateCode, msg.userId());
            return true;
        } catch (Exception e) {
            log.warn("aliyun template sms failed userId={} template={}: {}",
                    msg.userId(), templateCode, e.toString());
            return false;
        }
    }

    private static String sanitizeDigits(String raw) {
        return raw == null ? "" : raw.replaceAll("[^0-9]", "");
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

    /**
     * 微信订阅消息 thing 类型字段限 20 字符，超长整条被微信拒发（errcode 47003）；
     * 发送侧兜底截断，保证通知不会因文案过长而静默丢失。
     */
    private static String clipToThingLimit(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= WECHAT_THING_MAX_CHARS ? value : value.substring(0, WECHAT_THING_MAX_CHARS);
    }

    private static final int WECHAT_THING_MAX_CHARS = 20;

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
