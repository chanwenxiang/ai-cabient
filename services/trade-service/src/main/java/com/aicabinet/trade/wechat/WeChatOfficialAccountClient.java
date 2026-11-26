package com.aicabinet.trade.wechat;

import com.aicabinet.common.http.OutboundHttp;
import com.aicabinet.trade.config.WeChatOfficialAccountProperties;
import com.aicabinet.trade.wechat.MiniAppTarget;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URLEncoder;

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
    private static final String OAUTH_TOKEN_URL =
            "https://api.weixin.qq.com/sns/oauth2/access_token?appid={appId}&secret={secret}&code={code}&grant_type=authorization_code";

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
        return sendTemplateMessage(openId, data, null);
    }

    public boolean sendTemplateMessage(String openId, Map<String, String> data, String clientMsgId) {
        return sendTemplateMessage(openId, data, clientMsgId, MiniAppTarget.CONSUMER,
                properties.resolveConsumerMiniPagePath());
    }

    public boolean sendTemplateMessage(String openId, Map<String, String> data, String clientMsgId,
                                       MiniAppTarget target, String pagePath) {
        String templateId = target == MiniAppTarget.MERCHANT
                ? properties.resolveMerchantDailyReportTemplateId() : properties.templateId();
        boolean configured = target == MiniAppTarget.MERCHANT
                ? properties.isMerchantDailyReportConfigured() : properties.isConfigured();
        return sendTemplateMessage(openId, templateId, data, clientMsgId, target, pagePath, configured);
    }

    /**
     * 显式模板 ID 版本：消费者侧现在有「消费成功通知 / 退款成功通知」等多个模板，
     * 按业务类型选模板后调用本方法（目标端仍决定跳转哪个小程序与可用性判据）。
     */
    public boolean sendTemplateMessage(String openId, String templateId, Map<String, String> data,
                                       String clientMsgId, MiniAppTarget target, String pagePath,
                                       boolean configured) {
        if (!configured || templateId == null || templateId.isBlank()
                || openId == null || openId.isBlank()
                || data == null || data.isEmpty()) {
            return false;
        }
        try {
            Map<String, Object> body = buildBody(openId, templateId, data, target, pagePath);
            if (clientMsgId != null && !clientMsgId.isBlank()) {
                body.put("client_msg_id", clientMsgId.trim());
            }
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
        return buildBody(openId, properties.templateId(), data, MiniAppTarget.CONSUMER,
                properties.resolveConsumerMiniPagePath());
    }

    Map<String, Object> buildBody(String openId, String templateId, Map<String, String> data,
                                  MiniAppTarget target, String pagePath) {
        Map<String, Object> fields = new LinkedHashMap<>();
        data.forEach((key, value) -> fields.put(key, Map.of("value", value == null ? "" : value)));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("touser", openId);
        body.put("template_id", templateId);
        body.put("data", fields);
        String miniAppId = target == MiniAppTarget.MERCHANT
                ? properties.resolveMerchantMiniAppId() : properties.resolveConsumerMiniAppId();
        String resolvedPage = pagePath == null || pagePath.isBlank()
                ? (target == MiniAppTarget.MERCHANT
                    ? properties.resolveMerchantMiniPagePath() : properties.resolveConsumerMiniPagePath())
                : pagePath.trim();
        if (!miniAppId.isEmpty() && !resolvedPage.isEmpty()) {
            body.put("miniprogram", Map.of("appid", miniAppId, "pagepath", resolvedPage));
        } else {
            String url = properties.resolveUrl();
            if (!url.isEmpty()) {
                body.put("url", url);
            }
        }
        return body;
    }

    public String authorizeUrl(String state, String redirectUri) {
        return "https://open.weixin.qq.com/connect/oauth2/authorize"
                + "?appid=" + encode(properties.appId())
                + "&redirect_uri=" + encode(redirectUri)
                + "&response_type=code"
                + "&scope=snsapi_base"
                + "&state=" + encode(state)
                + "#wechat_redirect";
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    public String exchangeCodeForOpenId(String code) {
        if (!properties.isBindingConfigured() || code == null || code.isBlank()) {
            return null;
        }
        try {
            String body = restClient.get()
                    .uri(OAUTH_TOKEN_URL, properties.appId(), properties.appSecret(), code)
                    .retrieve()
                    .body(String.class);
            JsonNode node = objectMapper.readTree(body);
            if (node.has("errcode") && node.path("errcode").asInt(-1) != 0) {
                log.warn("official account oauth failed errcode={}", node.path("errcode").asInt(-1));
                return null;
            }
            String openId = node.path("openid").asText(null);
            return openId == null || openId.isBlank() ? null : openId;
        } catch (Exception e) {
            log.warn("official account oauth error errorType={}", e.getClass().getSimpleName());
            return null;
        }
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
