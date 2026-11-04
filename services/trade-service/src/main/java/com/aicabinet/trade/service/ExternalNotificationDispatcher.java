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

    public ExternalNotificationDispatcher(NotificationLogMapper logRepository,
                                          UserInfoMapper userInfoRepository,
                                          NotificationTemplateMapper templateRepository,
                                          WeChatMiniAppClient weChatMiniAppClient,
                                          WeChatMiniAppProperties weChatMiniAppProperties,
                                          WebhookSmsSender smsSender,
                                          NotifyChannelSwitchService channelSwitch,
                                          AliyunSmsSender aliyunSmsSender) {
        this.logRepository = logRepository;
        this.userInfoRepository = userInfoRepository;
        this.templateRepository = templateRepository;
        this.weChatMiniAppClient = weChatMiniAppClient;
        this.weChatMiniAppProperties = weChatMiniAppProperties;
        this.smsSender = smsSender;
        this.channelSwitch = channelSwitch;
        this.aliyunSmsSender = aliyunSmsSender;
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
        NotificationTemplate template = msg.templateCode() == null || msg.templateCode().isBlank()
                ? null
                : templateRepository.findByCode(msg.templateCode()).orElse(null);
        if (!smsChannelConfigured(template)) {
            // M09：模板未配置短信渠道（如微信-only）时不得因全局 sms-enabled 而双发
            log.info("sms skipped: template {} has no SMS channel, userId={}", msg.templateCode(), msg.userId());
            return;
        }
        if (sendViaAliyunTemplate(msg, template, user.getPhoneNumber())) {
            saveLog(msg, "SMS");
            return;
        }
        smsSender.sendMessage(user.getPhoneNumber(), msg.title() + "：" + msg.body());
        saveLog(msg, "SMS");
    }

    /**
     * 阿里云通知短信（模板渲染）：消息带结构化参数、且**该模板自己登记了**阿里云 CODE 时走模板发送。
     * <p>CODE 来自 {@code notification_template.aliyun_template_code}（V441）——一条模板对一个 CODE，
     * 不再有「全局催缴模板」兜底：没登记的模板绝不会被套成别的文案（此前 order_paid 会被套成催缴）。
     * 键名映射：催缴链路内部参数是 orderId/amount（V325 站内信模板 {orderId} 键名，固定迁移不可改），
     * 阿里云模板变量是 order_no/amount；发送前做纯键名归一化（orderId→order_no 且不保留原键），
     * 保证「参数个数 = 模板变量个数」（阿里云硬要求，多传/少传都会被拒）。
     * 阿里云未配齐/无参数/未登记 CODE ⇒ 返回 false 回退 webhook
     * 文本通道；发送失败也回退，催缴不因单通道故障丢失。</p>
     */
    private boolean sendViaAliyunTemplate(
            NotificationDispatchMessage msg, NotificationTemplate template, String phoneNumber) {
        String aliyunTemplateCode = template == null ? null : blankToNull(template.getAliyunTemplateCode());
        if (aliyunTemplateCode == null
                || msg.smsParams() == null || msg.smsParams().isEmpty()
                || !aliyunSmsSender.isConfigured()) {
            return false;
        }
        Map<String, String> params = new java.util.LinkedHashMap<>();
        msg.smsParams().forEach((k, v) -> {
            if (!"orderId".equals(k)) {
                params.put(k, v);
            }
        });
        if (!params.containsKey("order_no") && msg.smsParams().get("orderId") != null) {
            params.put("order_no", msg.smsParams().get("orderId"));
        }
        try {
            aliyunSmsSender.sendMessage(phoneNumber, aliyunTemplateCode, params);
            log.info("sms sent via aliyun template={} userId={}", aliyunTemplateCode, msg.userId());
            return true;
        } catch (Exception e) {
            log.warn("aliyun template sms failed userId={} template={}: {}",
                    msg.userId(), aliyunTemplateCode, e.toString());
            return false;
        }
    }

    private static String blankToNull(String raw) {
        return raw == null || raw.isBlank() ? null : raw.trim();
    }

    /**
     * M09：按模板 channels（逗号分隔 IN_APP/WECHAT_SUBSCRIBE/SMS）判断短信渠道是否启用。
     * 模板缺失/无模板上下文时保持既有直发行为。
     */
    private boolean smsChannelConfigured(NotificationTemplate template) {
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
