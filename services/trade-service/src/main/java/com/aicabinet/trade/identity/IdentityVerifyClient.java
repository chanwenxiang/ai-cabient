package com.aicabinet.trade.identity;

import com.aicabinet.trade.config.IdentityVerifyProperties;
import com.aicabinet.trade.config.SecurityProperties;
import com.aicabinet.trade.support.ApiMessages;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * 实名二要素：mock 直接通过；生产须配置 base-url，未配置则失败关闭。
 */
@Service
public class IdentityVerifyClient {

    private static final Logger log = LoggerFactory.getLogger(IdentityVerifyClient.class);

    private final SecurityProperties securityProperties;
    private final IdentityVerifyProperties properties;
    private final RestClient restClient = RestClient.create();

    public IdentityVerifyClient(SecurityProperties securityProperties,
                                IdentityVerifyProperties properties) {
        this.securityProperties = securityProperties;
        this.properties = properties;
    }

    public void verify(String realName, String idCardLast4) {
        if (securityProperties.mockEnabled()) {
            log.debug("identity verify skipped (mock-enabled)");
            return;
        }
        if (!properties.isConfigured()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, ApiMessages.IDENTITY_VERIFY_UNAVAILABLE);
        }
        String name = realName == null ? "" : realName.trim();
        String last4 = idCardLast4 == null ? "" : idCardLast4.trim();
        if (name.isEmpty() || last4.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ApiMessages.IDENTITY_VERIFY_FAILED);
        }
        try {
            var spec = restClient.post()
                    .uri(properties.baseUrl().trim())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("realName", name, "idCardLast4", last4));
            if (properties.apiKey() != null && !properties.apiKey().isBlank()) {
                spec = spec.header("X-Api-Key", properties.apiKey().trim());
            }
            IdentityVerifyResponse body = spec.retrieve().body(IdentityVerifyResponse.class);
            if (body == null || !body.isOk()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ApiMessages.IDENTITY_VERIFY_FAILED);
            }
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("identity verify upstream failed", e);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, ApiMessages.IDENTITY_VERIFY_UNAVAILABLE);
        }
    }

    public record IdentityVerifyResponse(Boolean matched, Boolean success, Boolean passed) {
        boolean isOk() {
            return Boolean.TRUE.equals(passed) || Boolean.TRUE.equals(matched) || Boolean.TRUE.equals(success);
        }
    }
}
