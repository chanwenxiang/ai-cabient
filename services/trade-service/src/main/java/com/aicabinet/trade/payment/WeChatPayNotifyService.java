package com.aicabinet.trade.payment;

import com.aicabinet.trade.config.WeChatPayProperties;
import com.aicabinet.trade.support.ApiMessages;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

@Component
public class WeChatPayNotifyService {

    private final WeChatPayProperties properties;
    private final WeChatPayV3Client v3Client;
    private final WeChatPayV3Aead aead;
    private final ObjectMapper objectMapper;

    public WeChatPayNotifyService(WeChatPayProperties properties,
                                  WeChatPayV3Client v3Client,
                                  WeChatPayV3Aead aead,
                                  ObjectMapper objectMapper) {
        this.properties = properties;
        this.v3Client = v3Client;
        this.aead = aead;
        this.objectMapper = objectMapper;
    }

    public JsonNode parseAndVerify(String body,
                                   String timestamp,
                                   String nonce,
                                   String signature,
                                   String serial) {
        if (!properties.isConfigured()) {
            throw new IllegalStateException(ApiMessages.WECHAT_PAY_NOT_CONFIGURED);
        }
        if (!v3Client.verifyNotifySignature(timestamp, nonce, body, signature, serial)) {
            throw new IllegalArgumentException(ApiMessages.INVALID_WECHAT_NOTIFY);
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode resource = root.path("resource");
            String plain = aead.decrypt(
                    properties.apiV3Key(),
                    resource.path("associated_data").asText(""),
                    resource.path("nonce").asText(""),
                    resource.path("ciphertext").asText("")
            );
            return objectMapper.readTree(plain);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("wechat notify parse failed", e);
        }
    }
}
