package com.aicabinet.trade.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 运营告警分发：支持飞书机器人 / 钉钉机器人 / 企业微信机器人 / 通用 JSON Webhook。
 *
 * <p>渠道地址通过系统参数配置（留空即不推送），单渠道失败只记日志、不影响主流程，
 * 与消费端微信订阅消息/短信通知相互独立。</p>
 *
 * <p><b>投递结果判定</b>：钉钉 / 企微 / 飞书在**业务被拒时仍返回 HTTP 200**，只在响应体里给业务码
 * （飞书 {@code code}、钉钉与企微 {@code errcode}）。因此「未抛异常」不等于「已送达」，
 * 必须解析响应体，否则会得到「日志说已发送、群里没消息」的假绿。
 * 判定刻意保守以免误红：**只有解析出明确的非 0 业务码才算失败**；响应体为空、非 JSON 或缺字段
 * 一律视为「无法核验」，记 warn 但按成功计。</p>
 */
@Service
public class OpsAlertDispatcher {

    private static final Logger log = LoggerFactory.getLogger(OpsAlertDispatcher.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private record Channel(String name, String configKey) {
    }

    private static final List<Channel> CHANNELS = List.of(
            new Channel("FEISHU", SystemConfigService.OPS_ALERT_FEISHU_WEBHOOK),
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
    /** 无附加字段的便捷重载。 */
    public void send(String type, String title, String message) {
        send(type, title, message, Map.of());
    }

    public void send(String type, String title, String message,
                     Map<String, Object> extra, String... extraUrls) {
        String text = title + (message == null || message.isBlank() ? "" : "\n" + message);
        for (Channel channel : CHANNELS) {
            String url = systemConfigService.getValue(channel.configKey(), "");
            if (url == null || url.isBlank()) {
                continue;
            }
            post(channel.name(), type, url, payloadFor(channel, type, title, message, text, extra));
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
            String error = deliveryError(channel, postJson(url, payload));
            if (error == null) {
                log.info("ops alert sent channel={} type={}", channel, type);
            } else {
                log.warn("ops alert rejected channel={} type={} url={}: {}",
                        channel, type, mask(url), error);
            }
        } catch (Exception e) {
            log.warn("ops alert failed channel={} type={} url={}: {}",
                    channel, type, mask(url), e.getMessage());
        }
    }

    /**
     * 与 {@link #send(String, String, String, Map, String...)} 相同的分发范围，但可感知失败（H37）：
     * 任一渠道发送成功即返回 true；全部渠道失败返回 false；未配置任何渠道时视为无需投递，返回 true。
     * 既有 {@code send(...)} 对其他调用方的「吞异常、不影响主流程」行为保持不变。
     */
    public boolean trySend(String type, String title, String message,
                           Map<String, Object> extra, String... extraUrls) {
        String text = title + (message == null || message.isBlank() ? "" : "\n" + message);
        boolean anySuccess = false;
        boolean anyConfigured = false;
        for (Channel channel : CHANNELS) {
            String url = systemConfigService.getValue(channel.configKey(), "");
            if (url == null || url.isBlank()) {
                continue;
            }
            anyConfigured = true;
            if (tryPost(channel.name(), type, url,
                    payloadFor(channel, type, title, message, text, extra))) {
                anySuccess = true;
            }
        }
        if (extraUrls != null) {
            for (String url : extraUrls) {
                if (url != null && !url.isBlank()) {
                    anyConfigured = true;
                    if (tryPost("WEBHOOK", type, url, genericPayload(type, title, message, extra))) {
                        anySuccess = true;
                    }
                }
            }
        }
        return !anyConfigured || anySuccess;
    }

    /** 单渠道投递：被平台接受返回 true；抛异常或返回非 0 业务码返回 false（不抛出）。 */
    private boolean tryPost(String channel, String type, String url, Object payload) {
        try {
            String error = deliveryError(channel, postJson(url, payload));
            if (error == null) {
                log.info("ops alert sent channel={} type={}", channel, type);
                return true;
            }
            log.warn("ops alert rejected channel={} type={} url={}: {}",
                    channel, type, mask(url), error);
            return false;
        } catch (Exception e) {
            log.warn("ops alert failed channel={} type={} url={}: {}",
                    channel, type, mask(url), e.getMessage());
            return false;
        }
    }

    /** 单个渠道的试发结果：{@code detail} 为 null 表示已投递，否则是平台给出的拒绝原因。 */
    public record ChannelProbe(String channel, boolean delivered, String detail) {
    }

    /**
     * 逐渠道试发一条消息，返回每个**已配置**渠道的真实投递结果（未配置的渠道没有 URL，无从试发）。
     *
     * <p>给运营台「测试发送」用：渠道填完 URL 后立刻能知道通不通，**不需要任何监控栈**；
     * 失败时把平台业务码原样带回去（如飞书 {@code code=19024 Key Words Not Found}），
     * 便于直接定位是关键词、签名还是 IP 白名单的问题。</p>
     */
    public List<ChannelProbe> probeChannels(String type, String title, String message) {
        String text = title + (message == null || message.isBlank() ? "" : "\n" + message);
        List<ChannelProbe> probes = new ArrayList<>();
        for (Channel channel : CHANNELS) {
            String url = systemConfigService.getValue(channel.configKey(), "");
            if (url == null || url.isBlank()) {
                continue;
            }
            try {
                String error = deliveryError(channel.name(),
                        postJson(url, payloadFor(channel, type, title, message, text, Map.of())));
                probes.add(new ChannelProbe(channel.name(), error == null, error));
            } catch (Exception e) {
                probes.add(new ChannelProbe(channel.name(), false, e.getMessage()));
            }
        }
        return probes;
    }

    /** 组装各渠道请求体；飞书签名密钥取自系统参数。 */
    private Object payloadFor(Channel channel, String type, String title, String message,
                              String text, Map<String, Object> extra) {
        return switch (channel.name()) {
            case "DINGTALK" -> dingTalkPayload(text);
            case "WECOM" -> weComPayload(text);
            case "FEISHU" -> feishuPayload(text,
                    systemConfigService.getValue(SystemConfigService.OPS_ALERT_FEISHU_SIGN_SECRET, ""));
            default -> genericPayload(type, title, message, extra);
        };
    }

    /**
     * 依据平台返回体判定该次投递是否被明确拒绝。
     *
     * @return {@code null} 表示未发现明确拒绝（含无法核验的情形）；否则返回用于日志的原因串
     */
    static String deliveryError(String channel, String body) {
        String statusField = switch (channel) {
            case "FEISHU" -> "code";
            case "DINGTALK", "WECOM" -> "errcode";
            default -> null;  // 通用 Webhook 无返回体契约，不做判定
        };
        if (statusField == null || body == null || body.isBlank()) {
            return null;
        }
        JsonNode node;
        try {
            node = MAPPER.readTree(body);
        } catch (Exception e) {
            return null;  // 非 JSON：无法核验，不据此判失败（避免误红）
        }
        JsonNode code = node.get(statusField);
        if (code == null || !code.isNumber() || code.asInt() == 0) {
            return null;
        }
        String detail = node.path("msg").asText(node.path("errmsg").asText(""));
        return statusField + "=" + code.asInt() + (detail.isBlank() ? "" : " " + detail);
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

    /**
     * 飞书自定义机器人 text 消息。
     *
     * <p>⚠️ 飞书用下划线 {@code msg_type}，与钉钉 / 企微的 {@code msgtype} **不同**；
     * 照搬钉钉报文会被飞书以 {@code code=9499 Bad Request} 拒收。
     * 配置了签名密钥时附带 {@code timestamp} / {@code sign}。</p>
     */
    static Map<String, Object> feishuPayload(String text, String signSecret) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (signSecret != null && !signSecret.isBlank()) {
            long timestamp = Instant.now().getEpochSecond();
            body.put("timestamp", String.valueOf(timestamp));
            body.put("sign", feishuSign(signSecret, timestamp));
        }
        body.put("msg_type", "text");
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("text", text);
        body.put("content", content);
        return body;
    }

    /**
     * 飞书签名：把 {@code timestamp + "\n" + secret} 当作 **HMAC 密钥**、被签消息为空串，
     * HmacSHA256 后 Base64。这是飞书官方的非标准用法（非常见的「key=secret, msg=timestamp+secret」），
     * 照官方文档 Java 示例实现。
     */
    static String feishuSign(String secret, long timestamp) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec((timestamp + "\n" + secret).getBytes(StandardCharsets.UTF_8),
                    "HmacSHA256"));
            return Base64.getEncoder().encodeToString(mac.doFinal(new byte[0]));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("飞书签名计算失败", e);
        }
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

    /** 独立方法便于测试替换；返回响应体原文（业务码判定需要），日志记录时对 URL 做脱敏。 */
    protected String postJson(String url, Object body) {
        ResponseEntity<String> response = restClient.post()
                .uri(url)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toEntity(String.class);
        return response.getBody();
    }

    private static String mask(String url) {
        if (url == null) {
            return "";
        }
        return url.length() <= 24 ? url : url.substring(0, 12) + "..." + url.substring(url.length() - 8);
    }
}
