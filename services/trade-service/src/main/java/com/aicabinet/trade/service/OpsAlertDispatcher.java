package com.aicabinet.trade.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
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
 *
 * <p><b>投递重试</b>：单渠道投递最多尝试 {@value #MAX_ATTEMPTS} 次，仅重试**瞬时传输故障**
 * （连接失败 / 读写超时 / 5xx / 429）。平台用业务码明确拒绝（如飞书 {@code 19024}）属于配置问题，
 * 重试不会自愈，立即返回，避免把一次配置错误放大成 N 次无用请求。
 * 重试粒度是**单渠道**而非整个 {@code send()} —— 后者会让已收到告警的渠道重复收一遍。
 * 投递语义为 at-least-once：若首次请求已被对方处理但响应丢失，重试会产生重复告警；
 * 告警场景下「重复」优于「丢失」，故接受。</p>
 *
 * <p><b>P0 升级链（O3）</b>：聊天渠道**一条都没真的送达**时，若该告警类型在升级白名单里，
 * 就按<b>值班表</b>把告警升级给当班人 —— 先短信、短信不成再电话。刻意「先查值班表、查不到就
 * 不升级」（fail-closed）：升级的收件人必须是此刻**真的在值班**的人，而不是某个写死的号码。
 * 总开关 {@code ops.alert.escalation_enabled} 默认关闭 ⇒ 默认零行为变化。</p>
 */
@Service
public class OpsAlertDispatcher {

    private static final Logger log = LoggerFactory.getLogger(OpsAlertDispatcher.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 单渠道投递的最大尝试次数（含首次）。 */
    static final int MAX_ATTEMPTS = 3;

    /** 首次重试前的退避，之后按 2 倍递增（200ms → 400ms）。 */
    static final long RETRY_BASE_BACKOFF_MILLIS = 200L;

    /** 告警投递连接超时。 */
    private static final int CONNECT_TIMEOUT_MILLIS = 3_000;

    /** 告警投递读超时。 */
    private static final int READ_TIMEOUT_MILLIS = 5_000;

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
        this.restClient = restClientBuilder.requestFactory(alertRequestFactory()).build();
    }

    /**
     * 告警投递专用请求工厂：**必须显式设超时**。
     *
     * <p>Boot 自动配置的 {@code RestClient.Builder} 默认不带读超时（等于无限等待）。目标机器人若
     * 半死不活（TCP 建连成功但不回包），发送线程会被**永久占住**，重试永远等不到触发时机 ——
     * 而告警正是从支付回调、定时任务这类不能长时间挂起的关键线程发出的。
     * 超时预算：单次尝试至多 {@code connect 3s + read 5s}，配 3 次尝试，单渠道最坏 24s。</p>
     */
    private static SimpleClientHttpRequestFactory alertRequestFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
        factory.setReadTimeout(READ_TIMEOUT_MILLIS);
        return factory;
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

    /**
     * 分发运营告警：投递失败**不影响主流程**（只记日志），并可能触发 P0 升级链。
     *
     * <p>升级链只在 {@code send} / {@link #trySend} 两处被触发，且都从同一个
     * {@link #fanout} 拿「有没有真的送达」，避免两条路径各自实现一遍判定而分叉。</p>
     */
    public void send(String type, String title, String message,
                     Map<String, Object> extra, String... extraUrls) {
        Fanout fanout = fanout(type, title, message, extra, extraUrls);
        escalateIfNeeded(type, title, message, fanout.anyDelivered());
    }

    /**
     * 与 {@link #send(String, String, String, Map, String...)} 相同的分发范围，但可感知失败（H37）：
     * 任一渠道发送成功即返回 true；全部渠道失败返回 false；未配置任何渠道时视为无需投递，返回 true。
     * 既有 {@code send(...)} 对其他调用方的「吞异常、不影响主流程」行为保持不变。
     *
     * <p>返回值语义**刻意不变**（未配置 ⇒ true）。升级链的触发条件与它不同：看的是
     * {@link Fanout#anyDelivered()}，即「一条都没真的送达」——**包含「压根没配聊天渠道」**，
     * 因为 P0 告警「没人收到」本身就该升级，而不只是「配了但全失败」。</p>
     */
    public boolean trySend(String type, String title, String message,
                           Map<String, Object> extra, String... extraUrls) {
        Fanout fanout = fanout(type, title, message, extra, extraUrls);
        escalateIfNeeded(type, title, message, fanout.anyDelivered());
        return !fanout.anyConfigured() || fanout.anyDelivered();
    }

    /** 一次分发的两个**独立**事实：有没有配置渠道、有没有至少一条真的送达。 */
    private record Fanout(boolean anyConfigured, boolean anyDelivered) {
    }

    /**
     * 统一的聊天渠道分发（含历史遗留的 {@code extraUrls}）。
     * {@code send} / {@code trySend} 共用，防止两条路径各自实现「什么叫送达」而分叉。
     */
    private Fanout fanout(String type, String title, String message,
                          Map<String, Object> extra, String... extraUrls) {
        String text = title + (message == null || message.isBlank() ? "" : "\n" + message);
        boolean anyConfigured = false;
        boolean anyDelivered = false;
        for (Channel channel : CHANNELS) {
            String url = systemConfigService.getValue(channel.configKey(), "");
            if (url == null || url.isBlank()) {
                continue;
            }
            anyConfigured = true;
            if (tryPost(channel.name(), type, url,
                    payloadFor(channel, type, title, message, text, extra))) {
                anyDelivered = true;
            }
        }
        if (extraUrls != null) {
            for (String url : extraUrls) {
                if (url != null && !url.isBlank()) {
                    anyConfigured = true;
                    if (tryPost("WEBHOOK", type, url, genericPayload(type, title, message, extra))) {
                        anyDelivered = true;
                    }
                }
            }
        }
        return new Fanout(anyConfigured, anyDelivered);
    }

    /**
     * 单渠道投递（含重试）：被平台接受返回 true；被业务码拒绝、或重试耗尽仍失败返回 false（不抛出）。
     *
     * <p>本方法是**全部渠道日志的唯一出口** —— 若 {@code post()} 与 {@code tryPost()} 各写一份，
     * 迟早会「两处一起写错」而无人发现。</p>
     */
    private boolean tryPost(String channel, String type, String url, Object payload) {
        Delivery delivery = deliver(channel, type, url, payload);
        if (delivery.delivered()) {
            log.info("ops alert sent channel={} type={} attempts={}",
                    channel, type, delivery.attempts());
            return true;
        }
        if (delivery.rejection() != null) {
            log.warn("ops alert rejected channel={} type={} url={}: {}",
                    channel, type, mask(url), delivery.rejection());
        } else {
            log.warn("ops alert failed channel={} type={} url={} attempts={}: {}",
                    channel, type, mask(url), delivery.attempts(), delivery.failure().getMessage());
        }
        return false;
    }

    // ------------------------------------------------------------------
    // P0 告警升级链（O3）：聊天渠道没人收到时，按值班表打给当班人
    // ------------------------------------------------------------------

    /**
     * 升级链两级渠道名。**刻意不放进 {@link #CHANNELS}**：那四条是"群机器人"，
     * 升级打的是"个人"，两者在配置面、试发面、失败语义上都不该混在一起。
     */
    static final String ESCALATION_SMS = "SMS";

    static final String ESCALATION_PHONE = "PHONE";

    /** 一次升级的结局。只用于日志与测试断言，**不改变** {@code send/trySend} 的返回值语义。 */
    enum EscalationOutcome {
        /** 总开关关闭（默认）⇒ 零行为变化。 */
        DISABLED,
        /** 聊天渠道已有人收到 ⇒ 不升级（升级只在「没人收到」时才有意义）。 */
        CHAT_DELIVERED,
        /** 该类型不在升级白名单里。 */
        NOT_P0,
        /** 此刻解析不出值班人 ⇒ **fail-closed 不升级**（不猜收件人）。 */
        NO_ONCALL,
        SMS_SENT,
        PHONE_SENT,
        /** 两级都失败（未配置或投递被业务码拒绝）。 */
        FAILED
    }

    /**
     * P0 升级：聊天渠道**一条都没送达**时，把告警按值班表打给当班人（先短信，短信不成再电话）。
     *
     * <p>包级可见是为了让同包测试直接断言结局矩阵；生产调用方（{@code send/trySend}）
     * 刻意**不消费返回值** —— 升级失败同样不得影响主流程。</p>
     */
    EscalationOutcome escalateIfNeeded(String type, String title, String message, boolean chatDelivered) {
        if (!systemConfigService.getBoolean(SystemConfigService.OPS_ALERT_ESCALATION_ENABLED, false)) {
            return EscalationOutcome.DISABLED;
        }
        if (chatDelivered) {
            return EscalationOutcome.CHAT_DELIVERED;
        }
        if (!isEscalationType(type)) {
            return EscalationOutcome.NOT_P0;
        }
        OnCallRoster.Entry onCall = OnCallRoster
                .parse(systemConfigService.getValue(SystemConfigService.OPS_ALERT_ONCALL_ROSTER, ""))
                .currentAt(now())
                .orElse(null);
        if (onCall == null) {
            // fail-closed：宁可这次不升级，也不半夜打给一个不在班／不存在的人。
            log.warn("ops alert escalation skipped (no on-call person at now) type={}", type);
            return EscalationOutcome.NO_ONCALL;
        }
        Object payload = escalationPayload(type, title, message, onCall);
        if (escalateTo(ESCALATION_SMS, SystemConfigService.OPS_ALERT_ESCALATION_SMS_WEBHOOK,
                type, payload)) {
            log.warn("ops alert escalated to SMS type={} onCall={}", type, onCall.name());
            return EscalationOutcome.SMS_SENT;
        }
        if (escalateTo(ESCALATION_PHONE, SystemConfigService.OPS_ALERT_ESCALATION_PHONE_WEBHOOK,
                type, payload)) {
            log.error("ops alert escalated to PHONE type={} onCall={} (SMS level did not deliver)",
                    type, onCall.name());
            return EscalationOutcome.PHONE_SENT;
        }
        log.error("ops alert escalation FAILED on both levels type={} onCall={}", type, onCall.name());
        return EscalationOutcome.FAILED;
    }

    /**
     * 类型白名单：留空 = 全类型升级；否则**必须逐字相等**。
     * 刻意不用前缀/包含匹配 —— 那会把 `WECHAT_REFUND_ABNORMAL_EXTRA` 之类的无关告警一并升级。
     */
    private boolean isEscalationType(String type) {
        String configured =
                systemConfigService.getValue(SystemConfigService.OPS_ALERT_ESCALATION_TYPES, "");
        if (configured == null || configured.isBlank()) {
            return true;
        }
        for (String candidate : configured.split(",")) {
            if (candidate.trim().equals(type)) {
                return true;
            }
        }
        return false;
    }

    /** 投递到某一级升级渠道；**未配置 URL 视为该级不可用**（继续降级到下一级）。 */
    private boolean escalateTo(String channel, String configKey, String type, Object payload) {
        String url = systemConfigService.getValue(configKey, "");
        if (url == null || url.isBlank()) {
            return false;
        }
        return tryPost(channel, type, url, payload);
    }

    /** 升级报文：收件人 + 告警正文；形状对两级网关统一（短信 / 外呼都按这个约定接）。 */
    static Map<String, Object> escalationPayload(String type, String title, String message,
                                                 OnCallRoster.Entry onCall) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", type);
        body.put("title", title);
        body.put("message", message);
        body.put("onCall", onCall.name());
        body.put("phoneNumber", onCall.phone());
        return body;
    }

    /** 当前时刻；抽成方法便于测试把时间**钉死**在某个星期/小时上（值班表按此匹配）。 */
    protected LocalDateTime now() {
        return LocalDateTime.now();
    }

    /** 单渠道一次投递的最终结局；{@code rejection} 与 {@code failure} 同为 null 表示已送达。 */
    private record Delivery(int attempts, String rejection, Exception failure) {

        boolean delivered() {
            return rejection == null && failure == null;
        }

        /** 失败原因（运营台展示用）：业务码拒绝优先，其次传输异常消息。 */
        String detail() {
            if (rejection != null) {
                return rejection;
            }
            return failure == null ? null : failure.getMessage();
        }
    }

    /**
     * 单渠道投递，带**有界重试**。这是全部分发路径（{@code send} / {@code trySend} /
     * {@code probeChannels}）共用的唯一投递原语。
     *
     * <p>重试只针对 {@link #isRetryable} 认定的瞬时故障；业务码拒绝不重试，立即返回。
     * 退避期间线程被中断则恢复中断位并放弃剩余尝试（按失败处理）。</p>
     */
    private Delivery deliver(String channel, String type, String url, Object payload) {
        for (int attempt = 1; ; attempt++) {
            try {
                return new Delivery(attempt, deliveryError(channel, postJson(url, payload)), null);
            } catch (Exception e) {
                if (attempt >= MAX_ATTEMPTS || !isRetryable(e)) {
                    return new Delivery(attempt, null, e);
                }
                log.warn("ops alert transport error channel={} type={} url={} attempt={}/{}: {} - retrying",
                        channel, type, mask(url), attempt, MAX_ATTEMPTS, e.getMessage());
                if (!backoff(attempt)) {
                    return new Delivery(attempt, null, e);  // 线程被中断：不再占用它
                }
            }
        }
    }

    /**
     * 是否值得重试：只认**瞬时传输故障**。
     *
     * <p>连接失败 / 读写超时（{@link ResourceAccessException}）、服务端 5xx
     * （{@link HttpServerErrorException}）、限流 429 属于「再试一次可能就好」；
     * 其余 4xx（404 地址写错、401/403 鉴权失败）与任何非 HTTP 异常都是**确定性失败**，
     * 重试只会把一次错误放大成 N 次无用请求，并拖长关键线程的占用。</p>
     */
    static boolean isRetryable(Throwable e) {
        if (e instanceof ResourceAccessException || e instanceof HttpServerErrorException) {
            return true;
        }
        return e instanceof HttpClientErrorException clientError
                && clientError.getStatusCode().value() == 429;
    }

    /** 指数退避：{@code base << (attempt-1)}。返回 false 表示线程被中断、应放弃剩余尝试。 */
    private static boolean backoff(int attempt) {
        try {
            Thread.sleep(RETRY_BASE_BACKOFF_MILLIS << (attempt - 1));
            return true;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
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
            Delivery delivery = deliver(channel.name(), type, url,
                    payloadFor(channel, type, title, message, text, Map.of()));
            probes.add(new ChannelProbe(channel.name(), delivery.delivered(), delivery.detail()));
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
            // 升级链两级：网关契约同样以响应体 `code == 0` 表示受理。**必须判业务码** ——
            // 短信/外呼网关在「余额不足、号码黑名单、模板未报备」这类业务拒绝时常常仍回 HTTP 200，
            // 只看状态码会把「一条短信都没发出去」记成「已升级」。
            case ESCALATION_SMS, ESCALATION_PHONE -> "code";
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
