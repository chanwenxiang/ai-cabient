package com.aicabinet.trade.wechat;

import com.aicabinet.trade.config.SecurityProperties;
import com.aicabinet.trade.config.WeChatMiniAppProperties;
import com.aicabinet.trade.support.ApiMessages;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class WeChatMiniAppClient {

    private static final Logger log = LoggerFactory.getLogger(WeChatMiniAppClient.class);
    private static final String CODE2SESSION_URL =
            "https://api.weixin.qq.com/sns/jscode2session?appid={appId}&secret={secret}&js_code={code}&grant_type=authorization_code";
    private static final String ACCESS_TOKEN_URL =
            "https://api.weixin.qq.com/cgi-bin/token?grant_type=client_credential&appid={appId}&secret={secret}";
    private static final String SUBSCRIBE_SEND_URL =
            "https://api.weixin.qq.com/cgi-bin/message/subscribe/send?access_token={token}";

    private final AtomicReference<CachedToken> cachedToken = new AtomicReference<>();

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
        this.restClient = RestClient.create();
    }

    public Code2SessionResult code2Session(String code) {
        if (!properties.isConfigured()) {
            if (!securityProperties.mockEnabled()) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, ApiMessages.WECHAT_MINIAPP_NOT_CONFIGURED);
            }
            log.warn("wechat miniapp not configured, using mock openid (dev only)");
            return new Code2SessionResult("mock_openid_10001", "mock_session");
        }
        String body = restClient.get()
                .uri(CODE2SESSION_URL,
                        properties.appId(), properties.appSecret(), code)
                .retrieve()
                .body(String.class);
        try {
            JsonNode node = objectMapper.readTree(body);
            if (node.has("errcode") && node.get("errcode").asInt() != 0) {
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

    public boolean sendSubscribeMessage(String openId, String templateId, String page, Map<String, String> dataFields) {
        if (openId == null || openId.isBlank() || templateId == null || templateId.isBlank()) {
            return false;
        }
        if (!properties.isConfigured()) {
            if (securityProperties.mockEnabled()) {
                log.info("mock subscribe message to {} data={}", openId, dataFields);
                return true;
            }
            return false;
        }
        try {
            String token = accessToken();
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
            int err = node.path("errcode").asInt(0);
            if (err != 0) {
                log.warn("subscribe send failed openId={} err={} msg={}", openId, err, node.path("errmsg").asText());
                return false;
            }
            return true;
        } catch (Exception e) {
            log.warn("subscribe send error openId={}", openId, e);
            return false;
        }
    }

    private String accessToken() throws Exception {
        CachedToken current = cachedToken.get();
        if (current != null && current.expiresAt.isAfter(Instant.now().plusSeconds(120))) {
            return current.token;
        }
        String body = restClient.get()
                .uri(ACCESS_TOKEN_URL, properties.appId(), properties.appSecret())
                .retrieve()
                .body(String.class);
        JsonNode node = objectMapper.readTree(body);
        if (node.has("errcode") && node.get("errcode").asInt() != 0) {
            throw new IllegalStateException("access_token failed: " + body);
        }
        String token = node.get("access_token").asText();
        int expiresIn = node.path("expires_in").asInt(7200);
        cachedToken.set(new CachedToken(token, Instant.now().plusSeconds(expiresIn)));
        return token;
    }

    private static String truncate(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max);
    }

    private record CachedToken(String token, Instant expiresAt) {}
}
