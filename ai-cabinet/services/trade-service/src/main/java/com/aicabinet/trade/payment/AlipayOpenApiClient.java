package com.aicabinet.trade.payment;

import com.aicabinet.trade.config.AlipayProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.TreeMap;

@Component
public class AlipayOpenApiClient {

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final AlipayProperties properties;
    private final AlipaySignUtil signUtil;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    public AlipayOpenApiClient(AlipayProperties properties,
                               AlipaySignUtil signUtil,
                               ObjectMapper objectMapper) {
        this.properties = properties;
        this.signUtil = signUtil;
        this.objectMapper = objectMapper;
        this.restClient = RestClient.create();
    }

    public JsonNode execute(String method, Map<String, Object> bizContent) {
        if (!properties.isConfigured()) {
            throw new IllegalStateException("alipay not configured");
        }
        Map<String, String> params = new TreeMap<>();
        params.put("app_id", properties.appId());
        params.put("method", method);
        params.put("format", "json");
        params.put("charset", "utf-8");
        params.put("sign_type", "RSA2");
        params.put("timestamp", LocalDateTime.now().format(TIMESTAMP));
        params.put("version", "1.0");
        try {
            params.put("biz_content", objectMapper.writeValueAsString(bizContent));
        } catch (Exception e) {
            throw new IllegalStateException("alipay biz_content serialize failed", e);
        }
        params.put("sign", signUtil.signRsa2(params, properties.privateKey()));

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        params.forEach(form::add);

        String body = restClient.post()
                .uri(properties.gatewayUrl())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(String.class);

        try {
            JsonNode root = objectMapper.readTree(body);
            String responseKey = method.replace('.', '_') + "_response";
            JsonNode response = root.path(responseKey);
            if (!"10000".equals(response.path("code").asText())) {
                throw new IllegalStateException("alipay api error: " + response);
            }
            return response;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("alipay response parse failed: " + body, e);
        }
    }

    public byte[] download(String url) {
        byte[] data = restClient.get()
                .uri(url)
                .retrieve()
                .body(byte[].class);
        return data != null ? data : new byte[0];
    }
}
