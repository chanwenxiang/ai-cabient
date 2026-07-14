package com.aicabinet.trade.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "aicabinet.dispute-sla")
public record DisputeSlaProperties(
        int hours,
        int reminderHoursBefore,
        String alertWebhookUrl,
        boolean schedulerEnabled
) {
    public DisputeSlaProperties {
        if (hours <= 0) {
            hours = 48;
        }
        if (reminderHoursBefore <= 0) {
            reminderHoursBefore = 12;
        }
    }
}
