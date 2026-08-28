package com.aicabinet.trade.payment;

import com.aicabinet.trade.config.AlipayProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
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
        Map<String, String> params = buildCommonParams(method, bizContent, properties.returnUrl(), null);
        params.put("sign", signUtil.signRsa2(params, properties.privateKey()));

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        params.forEach(form::add);

        String body = restClient.post()
                .uri(properties.gatewayUrl())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(String.class);

        return parseResponse(method, body);
    }

    /**
     * 网页授权换 token：grant_type / code 为顶层参数（非 biz_content）。
     */
    public JsonNode executeOauthToken(String authCode) {
        if (!properties.isConfigured()) {
            throw new IllegalStateException("alipay not configured");
        }
        Map<String, String> params = new TreeMap<>();
        params.put("app_id", properties.appId());
        params.put("method", "alipay.system.oauth.token");
        params.put("format", "json");
        params.put("charset", "utf-8");
        params.put("sign_type", "RSA2");
        params.put("timestamp", LocalDateTime.now().format(TIMESTAMP));
        params.put("version", "1.0");
        params.put("grant_type", "authorization_code");
        params.put("code", authCode);
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
            if (root.has("error_response")) {
                throw new IllegalStateException("alipay oauth error: " + root.path("error_response"));
            }
            JsonNode response = root.path("alipay_system_oauth_token_response");
            if (response.isMissingNode() || response.isNull()) {
                throw new IllegalStateException("alipay oauth response missing: " + body);
            }
            // 成功体通常无 code=10000；有 access_token / user_id 即视为成功
            if (response.path("access_token").asText("").isBlank()
                    && response.path("user_id").asText("").isBlank()
                    && response.path("open_id").asText("").isBlank()) {
                throw new IllegalStateException("alipay oauth empty token: " + response);
            }
            return response;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("alipay oauth parse failed: " + body, e);
        }
    }

    private JsonNode parseResponse(String method, String body) {
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

    /** 构建 WAP/页面支付 POST 表单（支付宝推荐方式） */
    public String buildPagePayFormHtml(String method, Map<String, Object> bizContent, String returnUrl) {
        return buildPagePayFormHtml(method, bizContent, returnUrl, null);
    }

    /** @param notifyUrlOverride 非空时覆盖默认 notify_url（协议签约用） */
    public String buildPagePayFormHtml(String method, Map<String, Object> bizContent,
                                       String returnUrl, String notifyUrlOverride) {
        if (!properties.isConfigured()) {
            throw new IllegalStateException("alipay not configured");
        }
        Map<String, String> params = buildCommonParams(method, bizContent, returnUrl, notifyUrlOverride);
        params.put("sign", signUtil.signRsa2(params, properties.privateKey()));
        StringBuilder inputs = new StringBuilder();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            inputs.append("<input type=\"hidden\" name=\"")
                    .append(escapeHtml(entry.getKey()))
                    .append("\" value=\"")
                    .append(escapeHtml(entry.getValue()))
                    .append("\"/>");
        }
        return "<form id=\"alipay-submit\" name=\"alipay-submit\" action=\""
                + escapeHtml(properties.gatewayUrl())
                + "\" method=\"POST\" accept-charset=\"utf-8\">"
                + inputs
                + "<input type=\"submit\" value=\"ok\" style=\"display:none;\"/></form>"
                + "<script>document.forms['alipay-submit'].submit();</script>";
    }

    /** @deprecated 保留兼容；新流程请用 {@link #buildPagePayFormHtml} */
    @Deprecated(since = "0.1.0", forRemoval = false)
    public String buildPagePayUrl(String method, Map<String, Object> bizContent) {
        if (!properties.isConfigured()) {
            throw new IllegalStateException("alipay not configured");
        }
        Map<String, String> params = buildCommonParams(method, bizContent, properties.returnUrl(), null);
        params.put("sign", signUtil.signRsa2(params, properties.privateKey()));
        String query = params.entrySet().stream()
                .map(e -> e.getKey() + "=" + urlEncode(e.getValue()))
                .collect(java.util.stream.Collectors.joining("&"));
        return properties.gatewayUrl() + "?" + query;
    }

    private Map<String, String> buildCommonParams(String method, Map<String, Object> bizContent,
                                                  String returnUrl, String notifyUrlOverride) {
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
        String notify = notifyUrlOverride != null && !notifyUrlOverride.isBlank()
                ? notifyUrlOverride.trim()
                : properties.notifyUrl();
        if (notify != null && !notify.isBlank()) {
            params.put("notify_url", notify);
        }
        String resolvedReturnUrl = returnUrl != null && !returnUrl.isBlank()
                ? returnUrl
                : properties.returnUrl();
        if (resolvedReturnUrl != null && !resolvedReturnUrl.isBlank()) {
            params.put("return_url", resolvedReturnUrl);
        }
        return params;
    }

    private static String escapeHtml(String value) {
        return value
                .replace("&", "&amp;")
                .replace("\"", "&quot;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    private static String urlEncode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
