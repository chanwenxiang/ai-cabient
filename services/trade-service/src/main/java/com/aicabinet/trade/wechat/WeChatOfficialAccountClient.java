package com.aicabinet.trade.wechat;

import com.aicabinet.common.http.OutboundHttp;
import com.aicabinet.trade.config.WeChatOfficialAccountProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/** 微信公众号服务号模板消息发送与 access_token 缓存。 */
@Component
public class WeChatOfficialAccountClient {

    private static final Logger log = LoggerFactory.getLogger(WeChatOfficialAccountClient.class);
    private static final String ACCESS_TOKEN_URL =
            "https://api.weixin.qq.com/cgi-bin/token?grant_type=client_credential&appid={appId}&secret={secret}";
    private static final String TEMPLATE_SEND_URL =
            "https://api.weixin.qq.com/cgi-bin/message/template/send?access_token={token}";

    private final WeChatOfficialAccountProperties properties;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;
    private final Clock clock;
    private final AtomicReference<CachedToken> cachedToken = new AtomicReference<>();

    @Autowired
    public WeChatOfficialAccountClient(WeChatOfficialAccountProperties properties, ObjectMapper objectMapper) {
        this(properties, objectMapper, Clock.systemUTC(), OutboundHttp.publicApiBuffered());
    }

    WeChatOfficialAccountClient(WeChatOfficialAccountProperties properties, ObjectMapper objectMapper,
                                Clock clock, RestClient.Builder restClientBuilder) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.restClient = restClientBuilder.build();
    }

    public boolean sendTemplateMessage(String openId, Map<String, String> data) {
        if (!properties.isConfigured() || openId == null || openId.isBlank()
                || data == null || data.isEmpty()) {
            return false;
        }
        try {
            Map<String, Object> body = buildBody(openId, data);
            String response = restClient.post()
                    .uri(TEMPLATE_SEND_URL, accessToken())
                    .body(body)
                    .retrieve()
                    .body(String.class);
            JsonNode node = objectMapper.readTree(response);
            if (!node.has("errcode") || node.path("errcode").asInt(-1) != 0) {
                String requestId = node.path("request_id").asText(null);
                if (requestId == null || requestId.isBlank()) {
                    requestId = node.path("requestId").asText(null);
                }
                log.warn("official account template send failed openIdHash={} errcode={} requestId={} errorType=WECHAT_RESPONSE",
                        maskOpenId(openId), node.path("errcode").asInt(-1), requestId);
                return false;
            }
            return true;
        } catch (Exception e) {
            log.warn("official account template send error openIdHash={} errorType={}",
                    maskOpenId(openId), e.getClass().getSimpleName());
            return false;
        }
    }

    private static String maskOpenId(String openId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(openId.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(digest, 0, 6);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    Map<String, Object> buildBody(String openId, Map<String, String> data) {
        Map<String, Object> fields = new LinkedHashMap<>();
        data.forEach((key, value) -> fields.put(key, Map.of("value", value == null ? "" : value)));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("touser", openId);
        body.put("template_id", properties.templateId());
        body.put("data", fields);
        String url = properties.resolveUrl();
        if (!url.isEmpty()) {
            body.put("url", url);
        }
        return body;
    }

    synchronized String accessToken() {
        CachedToken current = cachedToken.get();
        if (current != null && current.expiresAt().isAfter(clock.instant().plusSeconds(120))) {
            return current.token();
        }
        try {
            String body = restClient.get()
                    .uri(ACCESS_TOKEN_URL, properties.appId(), properties.appSecret())
                    .retrieve()
                    .body(String.class);
            JsonNode node = objectMapper.readTree(body);
            if (node.has("errcode") && node.path("errcode").asInt(-1) != 0) {
                throw new IllegalStateException("official account access_token failed: errcode="
                        + node.path("errcode").asInt(-1));
            }
            String token = node.path("access_token").asText("");
            if (token.isBlank()) {
                throw new IllegalStateException("official account access_token missing");
            }
            int expiresIn = node.path("expires_in").asInt(7200);
            cachedToken.set(new CachedToken(token, clock.instant().plusSeconds(expiresIn)));
            return token;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("official account access_token failed", e);
        }
    }

    private record CachedToken(String token, Instant expiresAt) {}
}
