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

@Component
public class WeChatMiniAppClient {

    private static final Logger log = LoggerFactory.getLogger(WeChatMiniAppClient.class);
    private static final String CODE2SESSION_URL =
            "https://api.weixin.qq.com/sns/jscode2session?appid={appId}&secret={secret}&js_code={code}&grant_type=authorization_code";

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
            return new Code2SessionResult("mock_openid_" + code.hashCode(), "mock_session");
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
}
