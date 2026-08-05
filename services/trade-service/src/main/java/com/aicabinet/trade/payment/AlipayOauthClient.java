package com.aicabinet.trade.payment;

import com.aicabinet.trade.config.AlipayProperties;
import com.aicabinet.trade.config.SecurityProperties;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** 支付宝网页授权：auth.code → user_id */
@Component
public class AlipayOauthClient {

    private final AlipayProperties properties;
    private final SecurityProperties securityProperties;
    private final AlipayOpenApiClient openApiClient;

    public AlipayOauthClient(AlipayProperties properties,
                             SecurityProperties securityProperties,
                             AlipayOpenApiClient openApiClient) {
        this.properties = properties;
        this.securityProperties = securityProperties;
        this.openApiClient = openApiClient;
    }

    public String resolveUserId(String authCode) {
        String code = authCode == null ? "" : authCode.trim();
        if (code.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "authCode 不能为空");
        }
        if (securityProperties.mockEnabled() && !properties.isConfigured()) {
            // mock：直接把 code 当作支付宝 user_id（便于无沙箱联调）
            return code.startsWith("mock_") ? code : "mock_alipay_" + code;
        }
        if (!properties.isConfigured()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "支付宝未配置");
        }
        try {
            JsonNode response = openApiClient.executeOauthToken(code);
            String userId = response.path("user_id").asText(null);
            if (userId == null || userId.isBlank()) {
                userId = response.path("open_id").asText(null);
            }
            if (userId == null || userId.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "支付宝未返回用户标识");
            }
            return userId;
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "支付宝授权失败: " + e.getMessage());
        }
    }
}
