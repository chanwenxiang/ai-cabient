package com.aicabinet.trade.client;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 将邑商户账号 token（§5.4.1 login2）的共享缓存。
 *
 * <p>§7.4 明写第七章全部接口的 {@code Authorization: Bearer <token>} 都来自 5.4.1，且与
 * 5.4/5.5/5.6 是同一个商户账号。文档示例 token 为 HS512、payload 含
 * {@code sub=登录手机号 / tenantId / exp≈2038}，属长期票据，因此这里按「缓存 + 401 失效重取」
 * 处理，避免每 25 分钟按 client 各登录一次。</p>
 */
@Component
public class JiangyiMerchantTokenProvider {

    private static final Logger log = LoggerFactory.getLogger(JiangyiMerchantTokenProvider.class);
    private static final long TOKEN_TTL_MS = 25 * 60_000L;

    private final RestClient restClient;
    private final String merchantMobile;
    private final String merchantPassword;

    private volatile String cachedToken;
    private volatile long tokenFetchedAtMs;

    public JiangyiMerchantTokenProvider(
            @Value("${JIANGYI_API_BASE_URL:https://lingshouapi.hunanjysmart.com}") String baseUrl,
            @Value("${JIANGYI_MERCHANT_MOBILE:}") String merchantMobile,
            @Value("${JIANGYI_MERCHANT_PASSWORD:}") String merchantPassword) {
        this.restClient = InternalRestClientFactory.create(baseUrl);
        this.merchantMobile = merchantMobile;
        this.merchantPassword = merchantPassword;
    }

    public synchronized String token() {
        long now = System.currentTimeMillis();
        if (cachedToken != null && now - tokenFetchedAtMs < TOKEN_TTL_MS) {
            return cachedToken;
        }
        if (merchantMobile == null || merchantMobile.isBlank()
                || merchantPassword == null || merchantPassword.isBlank()) {
            throw new IllegalStateException(
                    "将邑商户 API 凭据未配置（JIANGYI_MERCHANT_MOBILE/JIANGYI_MERCHANT_PASSWORD）");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("mobile", merchantMobile);
        body.put("password", merchantPassword);
        JsonNode root;
        try {
            root = restClient.post().uri("/merchant/authentication/login2")
                    .body(body).retrieve().body(JsonNode.class);
        } catch (HttpStatusCodeException e) {
            throw new IllegalStateException("将邑 login2 HTTP " + e.getStatusCode().value(), e);
        }
        JsonNode data = root == null ? null : root.path("data");
        String token = data == null || data.isMissingNode() || data.isNull() ? null : data.asText();
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("将邑 login2 响应缺少 token");
        }
        cachedToken = token;
        tokenFetchedAtMs = now;
        return token;
    }

    public synchronized void invalidate() {
        log.info("jiangyi merchant token invalidated");
        cachedToken = null;
        tokenFetchedAtMs = 0;
    }

    /** 401/403 判定（HTTP 层或 envelope 层都可能出现，见 §7.4 与附录 B）。 */
    static boolean isAuthError(HttpStatusCode code) {
        return code.value() == 401 || code.value() == 403;
    }
}
