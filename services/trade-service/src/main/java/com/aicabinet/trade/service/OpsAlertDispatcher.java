package com.aicabinet.trade.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 运营告警分发：支持钉钉机器人 / 企业微信机器人 / 通用 JSON Webhook。
 *
 * <p>渠道地址通过系统参数配置（留空即不推送），单渠道失败只记日志、不影响主流程，
 * 与消费端微信订阅消息/短信通知相互独立。</p>
 */
@Service
public class OpsAlertDispatcher {

    private static final Logger log = LoggerFactory.getLogger(OpsAlertDispatcher.class);

    private record Channel(String name, String configKey) {
    }

    private static final List<Channel> CHANNELS = List.of(
            new Channel("DINGTALK", SystemConfigService.OPS_ALERT_DINGTALK_WEBHOOK),
            new Channel("WECOM", SystemConfigService.OPS_ALERT_WECOM_WEBHOOK),
            new Channel("WEBHOOK", SystemConfigService.OPS_ALERT_WEBHOOK)
    );

    private final SystemConfigService systemConfigService;
    private final RestClient restClient;

    public OpsAlertDispatcher(SystemConfigService systemConfigService, RestClient.Builder restClientBuilder) {
        this.systemConfigService = systemConfigService;
        this.restClient = restClientBuilder.build();
    }

    /**
     * 分发运营告警到已配置的全部渠道。
     *
     * @param type       告警类型（如 DISPUTE_SLA_REMINDER）
     * @param title      告警标题
     * @param message    告警正文
     * @param extra      附加字段（通用 Webhook 透传）
     * @param extraUrls  额外的通用 Webhook URL（兼容历史配置，如 dispute.sla.webhook）
     */
    public void send(String type, String title, String message,
                     Map<String, Object> extra, String... extraUrls) {
        String text = title + (message == null || message.isBlank() ? "" : "\n" + message);
        for (Channel channel : CHANNELS) {
            String url = systemConfigService.getValue(channel.configKey(), "");
            if (url == null || url.isBlank()) {
                continue;
            }
            Object payload = switch (channel.name()) {
                case "DINGTALK" -> dingTalkPayload(text);
                case "WECOM" -> weComPayload(text);
                default -> genericPayload(type, title, message, extra);
            };
            post(channel.name(), type, url, payload);
        }
        if (extraUrls != null) {
            for (String url : extraUrls) {
                if (url != null && !url.isBlank()) {
                    post("WEBHOOK", type, url, genericPayload(type, title, message, extra));
                }
            }
        }
    }

    private void post(String channel, String type, String url, Object payload) {
        try {
            postJson(url, payload);
            log.info("ops alert sent channel={} type={}", channel, type);
        } catch (Exception e) {
            log.warn("ops alert failed channel={} type={} url={}: {}",
                    channel, type, mask(url), e.getMessage());
        }
    }

    /** 钉钉机器人 text 消息。 */
    static Map<String, Object> dingTalkPayload(String text) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("msgtype", "text");
        Map<String, Object> textNode = new LinkedHashMap<>();
        textNode.put("content", text);
        body.put("text", textNode);
        return body;
    }

    /** 企业微信机器人 text 消息（与钉钉同为 msgtype/text/content 结构）。 */
    static Map<String, Object> weComPayload(String text) {
        return dingTalkPayload(text);
    }

    /** 通用 JSON Webhook：type/title/message/extra 平铺。 */
    static Map<String, Object> genericPayload(String type, String title, String message,
                                              Map<String, Object> extra) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", type);
        body.put("title", title);
        body.put("message", message);
        if (extra != null) {
            body.putAll(extra);
        }
        return body;
    }

    /** 独立方法便于测试替换；日志记录时对 URL 做脱敏。 */
    protected void postJson(String url, Object body) {
        restClient.post()
                .uri(url)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toBodilessEntity();
    }

    private static String mask(String url) {
        if (url == null) {
            return "";
        }
        return url.length() <= 24 ? url : url.substring(0, 12) + "..." + url.substring(url.length() - 8);
    }
}
