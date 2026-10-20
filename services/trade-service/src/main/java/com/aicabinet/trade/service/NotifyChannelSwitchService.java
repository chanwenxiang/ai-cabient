package com.aicabinet.trade.service;

import com.aicabinet.trade.config.NotificationProperties;
import org.springframework.stereotype.Service;

/**
 * 消息触达渠道开关（运行期可调，运营台「功能开关」面板可直接改）。
 *
 * <p><b>为什么要有这一层</b>：原 {@code NOTIFY_WECHAT/SMS/ASYNC_ENABLED} 是纯环境变量口径，
 * 运营台没有图形入口，改一次要动环境变量+重启（已知缺口，2026-10-20 拍板搬进运营台）。
 * 现在真相源是 {@link SystemConfigService}（seed 默认 false，运营台可随时改），
 * 环境变量仅在 seed 尚未落库的极端窗口作兜底（{@code getValue} 返回 null 时回退）。</p>
 *
 * <p>消费方：{@link ExternalNotificationDispatcher}（微信/短信双渠道）、
 * {@link MerchantNotifyService}（商户订阅消息）、{@link NotificationService}（异步派发）。</p>
 */
@Service
public class NotifyChannelSwitchService {

    private final SystemConfigService systemConfigService;
    private final NotificationProperties envProperties;

    public NotifyChannelSwitchService(SystemConfigService systemConfigService,
                                      NotificationProperties envProperties) {
        this.systemConfigService = systemConfigService;
        this.envProperties = envProperties;
    }

    /** 微信订阅消息总开关（notify.wechat.enabled，运营台可改）。 */
    public boolean wechatEnabled() {
        return resolve(SystemConfigService.NOTIFY_WECHAT_ENABLED, envProperties.wechatEnabled());
    }

    /** 欠款催缴短信总闸（notify.sms.enabled，运营台可改；V326 三道门之一）。 */
    public boolean smsEnabled() {
        return resolve(SystemConfigService.NOTIFY_SMS_ENABLED, envProperties.smsEnabled());
    }

    /** 通知异步派发（notify.async.enabled，运营台可改）。 */
    public boolean asyncEnabled() {
        return resolve(SystemConfigService.NOTIFY_ASYNC_ENABLED, envProperties.asyncEnabled());
    }

    private boolean resolve(String key, boolean envFallback) {
        String value = systemConfigService.getValue(key, null);
        return value == null || value.isBlank() ? envFallback : Boolean.parseBoolean(value.trim());
    }
}
