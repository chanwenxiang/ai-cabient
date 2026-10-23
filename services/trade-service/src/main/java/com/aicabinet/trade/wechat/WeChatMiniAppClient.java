package com.aicabinet.trade.wechat;

import com.aicabinet.trade.config.SecurityProperties;
import com.aicabinet.trade.config.WeChatMiniAppProperties;
import com.aicabinet.trade.support.ApiMessages;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class WeChatMiniAppClient {
    private static final String ERRCODE = "errcode";


    private static final Logger log = LoggerFactory.getLogger(WeChatMiniAppClient.class);
    private static final String CODE2SESSION_URL =
            "https://api.weixin.qq.com/sns/jscode2session?appid={appId}&secret={secret}&js_code={code}&grant_type=authorization_code";
    private static final String ACCESS_TOKEN_URL =
            "https://api.weixin.qq.com/cgi-bin/token?grant_type=client_credential&appid={appId}&secret={secret}";
    private static final String SUBSCRIBE_SEND_URL =
            "https://api.weixin.qq.com/cgi-bin/message/subscribe/send?access_token={token}";
    private static final String GENERATE_URL_LINK =
            "https://api.weixin.qq.com/wxa/generate_urllink?access_token={token}";
    private static final String GET_USER_PHONE_NUMBER_URL =
            "https://api.weixin.qq.com/wxa/business/getuserphonenumber?access_token={token}";

    private final AtomicReference<CachedToken> cachedConsumerToken = new AtomicReference<>();
    private final AtomicReference<CachedToken> cachedMerchantToken = new AtomicReference<>();

    private final WeChatMiniAppProperties properties;
    private final SecurityProperties securityProperties;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public WeChatMiniAppClient(WeChatMiniAppProperties properties,
                               SecurityProperties securityProperties,
                               ObjectMapper objectMapper) {
        this.properties = properties;
        this.securityProperties = securityProperties;
        this.objectMapper = objectMapper;
        // 🔴 不能用 RestClient.create()（Spring 6.1+ 对 JSON body 不再预缓冲、不设 Content-Length，
        // 改 Transfer-Encoding: chunked）——微信 API 对无 Content-Length 的请求一律回
        // 412 Precondition Failed（实证 2026-10-23：getuserphonenumber 与 subscribe/send 双双 412，
        // 后者此前被误判为"用户未订阅"）。BufferingClientHttpRequestFactory 恢复缓冲 → 带 Content-Length。
        this.restClient = RestClient.builder()
                .requestFactory(new BufferingClientHttpRequestFactory(new SimpleClientHttpRequestFactory()))
                .build();
    }

    public Code2SessionResult code2Session(String code, MiniAppTarget target) {
        if (!properties.isConfigured(target)) {
            if (!securityProperties.mockEnabled()) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, ApiMessages.WECHAT_MINIAPP_NOT_CONFIGURED);
            }
            log.warn("wechat miniapp {} not configured, using mock openid (dev only)", target);
            return new Code2SessionResult("mock_openid_10001", "mock_session");
        }
        String body = restClient.get()
                .uri(CODE2SESSION_URL,
                        properties.resolveAppId(target), properties.resolveAppSecret(target), code)
                .retrieve()
                .body(String.class);
        try {
            JsonNode node = objectMapper.readTree(body);
            if (node.has(ERRCODE) && node.get(ERRCODE).asInt() != 0) {
                throw new IllegalStateException("code2session failed: " + body);
            }
            return new Code2SessionResult(node.get("openid").asText(), node.path("session_key").asText());
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("parse code2session failed", e);
        }
    }

    public record Code2SessionResult(String openId, String sessionKey) {}

    /**
     * 小程序手机号授权码换真实手机号（C13）：
     * POST wxa/business/getuserphonenumber，access_token 复用 {@link #accessToken(MiniAppTarget)} 缓存。
     * isConfigured 语义与 {@link #code2Session(String, MiniAppTarget)} 一致：未配置且非 mock 抛 503；
     * 未配置且 mock（dev）返回空串，由调用方决定 dev 兼容行为。失败抛 IllegalStateException。
     *
     * @return purePhoneNumber（含手机号授权码无效/未返回时为空串）
     */
    public String getPhoneNumber(String phoneCode, MiniAppTarget target) {
        if (phoneCode == null || phoneCode.isBlank()) {
            return "";
        }
        if (!properties.isConfigured(target)) {
            if (securityProperties.mockEnabled()) {
                log.warn("wechat miniapp {} not configured, skip getuserphonenumber (dev only)", target);
                return "";
            }
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, ApiMessages.WECHAT_MINIAPP_NOT_CONFIGURED);
        }
        String body = restClient.post()
                .uri(GET_USER_PHONE_NUMBER_URL, accessToken(target))
                .body(Map.of("code", phoneCode.trim()))
                .retrieve()
                .body(String.class);
        try {
            JsonNode node = objectMapper.readTree(body);
            if (node.has(ERRCODE) && node.get(ERRCODE).asInt() != 0) {
                throw new IllegalStateException("getuserphonenumber failed: " + body);
            }
            return node.path("phone_info").path("purePhoneNumber").asText("");
        } catch (ResponseStatusException | IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("parse getuserphonenumber failed", e);
        }
    }

    public boolean sendSubscribeMessage(String openId, String templateId, String page, Map<String, String> dataFields,
                                        MiniAppTarget target) {
        if (openId == null || openId.isBlank() || templateId == null || templateId.isBlank()) {
            return false;
        }
        if (!properties.isConfigured(target)) {
            if (securityProperties.mockEnabled()) {
                log.info("mock subscribe message to {} data={}", openId, dataFields);
                return true;
            }
            return false;
        }
        try {
            String token = accessToken(target);
            Map<String, Object> data = new java.util.LinkedHashMap<>();
            dataFields.forEach((k, v) -> data.put(k, Map.of("value", truncate(v, 20))));
            Map<String, Object> body = Map.of(
                    "touser", openId,
                    "template_id", templateId,
                    "page", page != null ? page : properties.resolveNotifyPage(),
                    "data", data,
                    "miniprogram_state", "developer"
            );
            String response = restClient.post()
                    .uri(SUBSCRIBE_SEND_URL, token)
                    .body(body)
                    .retrieve()
                    .body(String.class);
            JsonNode node = objectMapper.readTree(response);
            int err = node.path(ERRCODE).asInt(0);
            if (err != 0) {
                String errmsg = node.path("errmsg").asText();
                log.warn("subscribe send failed openId={} err={} msg={}", openId, err, errmsg);
                return false;
            }
            return true;
        } catch (Exception e) {
            log.warn("subscribe send error openId={}", openId, e);
            return false;
        }
    }

    /**
     * 生成小程序 URL Link；未配置或调用失败返回 empty（调用方回落到 H5）。
     *
     * @param path  小程序页面路径，勿带 query
     * @param query 如 deviceId=330449777078&amp;channel=WECHAT&amp;autoOpen=1
     * @param envVersion release / trial / develop
     */
    public java.util.Optional<String> generateUrlLink(String path, String query, String envVersion,
                                                      MiniAppTarget target) {
        if (!properties.isConfigured(target)) {
            return java.util.Optional.empty();
        }
        try {
            String token = accessToken(target);
            String cleanPath = path == null ? "" : path.trim();
            if (cleanPath.startsWith("/")) {
                cleanPath = cleanPath.substring(1);
            }
            String env = (envVersion == null || envVersion.isBlank()) ? "release" : envVersion.trim();
            Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("path", cleanPath);
            body.put("query", query == null ? "" : query);
            body.put("expire_type", 1);
            body.put("expire_interval", 1);
            body.put("env_version", env);
            String response = restClient.post()
                    .uri(GENERATE_URL_LINK, token)
                    .body(body)
                    .retrieve()
                    .body(String.class);
            JsonNode node = objectMapper.readTree(response);
            int err = node.path(ERRCODE).asInt(0);
            if (err != 0) {
                String errmsg = node.path("errmsg").asText();
                log.warn("generate_urllink failed err={} msg={}", err, errmsg);
                return java.util.Optional.empty();
            }
            String link = node.path("url_link").asText(null);
            if (link == null || link.isBlank()) {
                log.warn("generate_urllink empty url_link body={}", response);
                return java.util.Optional.empty();
            }
            return java.util.Optional.of(link);
        } catch (Exception e) {
            log.warn("generate_urllink error path={}", path, e);
            return java.util.Optional.empty();
        }
    }

    /**
 * 取小程序 {@code access_token}（带内存缓存，提前 120s 过期）。
 *
 * <p>V310：由private 提升为 **public** —— 广告收益对账（{@code publisher/stat}）
 * 需要同一个 token。刻意<b>不新建第二个 token 管理器</b>：
 * 微信对同一 appId 的 token 刷新有频率限制，两套缓存各自刷新会撞
 * {@code 45009 请求过于频繁}，且两份缓存的过期判断会漂移。
 *
 * <p>⚠️ 调用方拿到的是<b>可能已过期但尚未到 120s 安全边界</b>的 token ——
 * 遇到 {@code 40001} / {@code 42001}（token 失效类错误码）时应当
 * <b>直接失败并等下次调度</b>，不要在客户端里做强制刷新：
 * 强刷会让并发拉取的任务一起失效。
 */
/**
 * 取指定端小程序的 {@code access_token}（按端独立缓存，提前 120s 过期）。
 *
 * <p>V310：由private 提升为 **public** —— 广告收益对账（{@code publisher/stat}）
 * 需要同一个 token。刻意<b>不新建第二个 token 管理器</b>：
 * 微信对同一 appId 的 token 刷新有频率限制，两套缓存各自刷新会撞
 * {@code 45009 请求过于频繁}，且两份缓存的过期判断会漂移。
 *
 * <p>2026-10-19 双 appid 改造：两个小程序主体各有独立 token（appid 隔离），
 * 缓存按 {@code MiniAppTarget} 分开——同一端内仍是「一份缓存」，V310 的
 * 同 appid 频率限制约束不变。
 *
 * <p>⚠️ 调用方拿到的是<b>可能已过期但尚未到 120s 安全边界</b>的 token ——
 * 遇到 {@code 40001} / {@code 42001}（token 失效类错误码）时应当
 * <b>直接失败并等下次调度</b>，不要在客户端里做强制刷新：
 * 强刷会让并发拉取的任务一起失效。
 */
public String accessToken(MiniAppTarget target) {
        AtomicReference<CachedToken> cached = cachedRef(target);
        CachedToken current = cached.get();
        if (current != null && current.expiresAt.isAfter(Instant.now().plusSeconds(120))) {
            return current.token;
        }
        try {
            String body = restClient.get()
                    .uri(ACCESS_TOKEN_URL, properties.resolveAppId(target), properties.resolveAppSecret(target))
                    .retrieve()
                    .body(String.class);
            JsonNode node = objectMapper.readTree(body);
            if (node.has(ERRCODE) && node.get(ERRCODE).asInt() != 0) {
                throw new IllegalStateException("access_token failed: " + body);
            }
            String token = node.get("access_token").asText();
            int expiresIn = node.path("expires_in").asInt(7200);
            cached.set(new CachedToken(token, Instant.now().plusSeconds(expiresIn)));
            return token;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("access_token failed", e);
        }
        }

private AtomicReference<CachedToken> cachedRef(MiniAppTarget target) {
        return target == MiniAppTarget.CONSUMER ? cachedConsumerToken : cachedMerchantToken;
        }

    private static String truncate(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max);
    }

    private record CachedToken(String token, Instant expiresAt) {}
}
