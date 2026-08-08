package com.aicabinet.trade.wechat;

import com.aicabinet.trade.config.SecurityProperties;
import com.aicabinet.trade.config.WeChatWebProperties;
import com.aicabinet.trade.support.ApiMessages;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

/** 微信公众号网页授权（H5 微信登录）：code → openid。 */
@Component
public class WeChatWebOAuthClient {

    private static final Logger log = LoggerFactory.getLogger(WeChatWebOAuthClient.class);
    private static final String OAUTH_TOKEN_URL =
            "https://api.weixin.qq.com/sns/oauth2/access_token?appid={appId}&secret={secret}&code={code}&grant_type=authorization_code";

    private final WeChatWebProperties properties;
    private final SecurityProperties securityProperties;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public WeChatWebOAuthClient(WeChatWebProperties properties,
                                SecurityProperties securityProperties,
                                ObjectMapper objectMapper) {
        this.properties = properties;
        this.securityProperties = securityProperties;
        this.objectMapper = objectMapper;
        this.restClient = RestClient.create();
    }

    /** H5 网页授权 code → openid（sns/oauth2/access_token）。 */
    public WebSessionResult webCode2Session(String code) {
        if (!properties.isConfigured()) {
            if (!securityProperties.mockEnabled()) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                        ApiMessages.WECHAT_WEB_NOT_CONFIGURED);
            }
            log.warn("wechat web oauth not configured, using mock openid (dev only)");
            return new WebSessionResult("mock_web_openid_10001");
        }
        String body = restClient.get()
                .uri(OAUTH_TOKEN_URL, properties.appId(), properties.appSecret(), code)
                .retrieve()
                .body(String.class);
        try {
            JsonNode node = objectMapper.readTree(body);
            if (node.has("errcode") && node.get("errcode").asInt() != 0) {
                throw new IllegalStateException("web oauth failed: " + body);
            }
            String openid = node.path("openid").asText(null);
            if (openid == null || openid.isBlank()) {
                throw new IllegalStateException("web oauth missing openid: " + body);
            }
            return new WebSessionResult(openid);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("parse web oauth failed", e);
        }
    }

    public record WebSessionResult(String openId) {}
}
