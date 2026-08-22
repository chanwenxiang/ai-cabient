package com.aicabinet.trade.sms;

import com.aicabinet.trade.config.AuthProperties;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * 按 {@code aicabinet.auth.sms.provider} 路由：aliyun / webhook（默认）。
 * mock 模式由 {@link SmsCodeService} 短路，不会调用本类。
 */
@Component
@Primary
public class RoutingSmsSender implements SmsSender {

    private final AuthProperties authProperties;
    private final WebhookSmsSender webhookSmsSender;
    private final AliyunSmsSender aliyunSmsSender;

    public RoutingSmsSender(AuthProperties authProperties,
                            WebhookSmsSender webhookSmsSender,
                            AliyunSmsSender aliyunSmsSender) {
        this.authProperties = authProperties;
        this.webhookSmsSender = webhookSmsSender;
        this.aliyunSmsSender = aliyunSmsSender;
    }

    @Override
    public void send(String phoneNumber, String code) {
        String provider = authProperties.sms().provider();
        if (provider != null && "aliyun".equalsIgnoreCase(provider.trim())) {
            aliyunSmsSender.send(phoneNumber, code);
            return;
        }
        if (provider != null && "auto".equalsIgnoreCase(provider.trim()) && aliyunSmsSender.isConfigured()) {
            aliyunSmsSender.send(phoneNumber, code);
            return;
        }
        webhookSmsSender.send(phoneNumber, code);
    }
}
