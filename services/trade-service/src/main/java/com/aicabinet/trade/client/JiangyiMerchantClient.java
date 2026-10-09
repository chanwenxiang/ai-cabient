package com.aicabinet.trade.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 将邑商户云 HTTP 客户端（CB-022，设备入驻/模型同步面）。
 * <p>base 默认 {@code https://lingshouapi.hunanjysmart.com}，可经 {@code JIANGYI_API_BASE_URL}
 * 覆盖。鉴权两把钥匙（V16 §5/§5.4.1）：</p>
 * <ul>
 *   <li>tenantId+secret：仅用于 {@code /platform/tenant/setDomain}（把设备的 domain/socketUrl
 *       指向我方服务器），不走 login2 token；</li>
 *   <li>login2(mobile+password) → Bearer：商户云其余全部接口（getIdentifier /
 *       modifyAffiliationTenant / 后续模型与商品接口）。</li>
 * </ul>
 *
 * <p>凭据全部走环境变量（铁律 #27，不落文件/仓库），未配置时调用即抛
 * IllegalStateException（fail-closed）。响应 envelope 统一 {@code {status,msg,data}}，
 * status!=200 抛 IllegalStateException；data 形态不定（对象/字符串），按 JsonNode 宽松解析。</p>
 *
 * <p>已知文档笔误：setDomain 返回示例写 {@code data:{identifier}}（V16 §4.2.2 表5），
 * 与字段表冲突——本客户端对 setDomain 返回的 identifier 只「有则采纳」，缺失时由
 * 调用方走 {@link #getIdentifier} 兜底，两路都空才报错。</p>
 */
@Component
public class JiangyiMerchantClient {

    private static final Logger log = LoggerFactory.getLogger(JiangyiMerchantClient.class);

    private static final int STATUS_OK = 200;
    /** login2 返回 JWT 无明确有效期说明，保守缓存 25 分钟；401/403 时强制重登。 */
    private static final long TOKEN_TTL_MS = 25 * 60_000L;

    private final RestClient restClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String merchantMobile;
    private final String merchantPassword;

    private volatile String cachedToken;
    private volatile long tokenFetchedAtMs;

    public JiangyiMerchantClient(@Value("${JIANGYI_API_BASE_URL:https://lingshouapi.hunanjysmart.com}") String baseUrl,
                                 @Value("${JIANGYI_MERCHANT_MOBILE:}") String merchantMobile,
                                 @Value("${JIANGYI_MERCHANT_PASSWORD:}") String merchantPassword) {
        this.restClient = InternalRestClientFactory.create(baseUrl);
        this.merchantMobile = merchantMobile;
        this.merchantPassword = merchantPassword;
    }

    /**
     * setDomain（V16 §4.2.2）：把将邑设备的 http domain 与 websocket socketUrl 指向我方。
     * 返回 data.identifier（文档笔误存疑，仅当非空时采纳）。
     */
    public Optional<String> setDomain(long tenantId, String secret, String domain, String socketUrl) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tenantId", tenantId);
        body.put("secret", secret);
        body.put("domain", domain);
        body.put("socketUrl", socketUrl);
        JsonNode data = post("/platform/tenant/setDomain", body, false, "setDomain");
        return Optional.ofNullable(textOrNull(data, "identifier")).filter(s -> !s.isBlank());
    }

    /** getIdentifier（采集文档 §3.6）：SN → 将邑签发设备编码（如 CQYB11253）。 */
    public Optional<String> getIdentifier(String deviceSn) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("deviceSn", deviceSn);
        JsonNode data = post("/merchant/deviceInfo/getIdentifier", body, true, "getIdentifier");
        return Optional.ofNullable(textOrNull(data, "identifier")).filter(s -> !s.isBlank());
    }

    /** modifyAffiliationTenant（V16 §5.4.x）：设备绑定到租户。 */
    public void modifyAffiliationTenant(String deviceSn, long tenantId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("deviceSn", deviceSn);
        body.put("tenantId", tenantId);
        post("/merchant/deviceInfo/modifyAffiliationTenant", body, true, "modifyAffiliationTenant");
    }

    /** login2（V16 §5.4.1）：运维小程序手机号+密码 → Bearer token（带 TTL 缓存）。 */
    private synchronized String bearerToken() {
        long now = System.currentTimeMillis();
        if (cachedToken != null && now - tokenFetchedAtMs < TOKEN_TTL_MS) {
            return cachedToken;
        }
        if (merchantMobile == null || merchantMobile.isBlank()
                || merchantPassword == null || merchantPassword.isBlank()) {
            throw new IllegalStateException("将邑商户云凭据未配置（JIANGYI_MERCHANT_MOBILE/JIANGYI_MERCHANT_PASSWORD）");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("mobile", merchantMobile);
        body.put("password", merchantPassword);
        JsonNode data = post("/merchant/authentication/login2", body, false, "login2");
        String token = data.isNull() || !data.isTextual() ? null : data.asText();
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("将邑 login2 响应缺少 token");
        }
        cachedToken = token;
        tokenFetchedAtMs = now;
        return token;
    }

    private void invalidateToken() {
        cachedToken = null;
        tokenFetchedAtMs = 0;
    }

    /**
     * 统一 POST：校验 envelope.status==200，返回 data 节点。
     * withAuth=true 时带 Bearer 并对 401/403 强制重登重试一次。
     */
    private JsonNode post(String path, Map<String, Object> body, boolean withAuth, String op) {
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                RestClient.RequestBodySpec spec = restClient.post().uri(path);
                if (withAuth) {
                    spec = spec.header("Authorization", "Bearer " + bearerToken());
                }
                String resp = spec.body(body).retrieve().body(String.class);
                return parseEnvelope(resp, op);
            } catch (HttpStatusCodeException e) {
                HttpStatusCode code = e.getStatusCode();
                boolean authExpired = withAuth && attempt == 0
                        && (code.value() == 401 || code.value() == 403);
                if (authExpired) {
                    log.warn("jiangyi {} got {} — token refreshed, retrying once", op, code.value());
                    invalidateToken();
                    continue;
                }
                throw new IllegalStateException("将邑 " + op + " HTTP " + code.value(), e);
            }
        }
        throw new IllegalStateException("将邑 " + op + " 重试后仍未成功");
    }

    private JsonNode parseEnvelope(String resp, String op) {
        if (resp == null || resp.isBlank()) {
            throw new IllegalStateException("将邑 " + op + " 返回空响应");
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(resp);
        } catch (Exception e) {
            throw new IllegalStateException("将邑 " + op + " 响应非 JSON：" + truncate(resp), e);
        }
        int status = root.path("status").asInt(-1);
        String msg = root.path("msg").asText("");
        if (status != STATUS_OK) {
            throw new IllegalStateException("将邑 " + op + " 失败 status=" + status + " msg=" + msg);
        }
        return root.path("data");
    }

    private static String textOrNull(JsonNode data, String field) {
        if (data == null || data.isMissingNode() || data.isNull() || !data.isObject()) {
            return null;
        }
        JsonNode v = data.path(field);
        return v.isMissingNode() || v.isNull() ? null : v.asText();
    }

    private static String truncate(String s) {
        return s.length() <= 200 ? s : s.substring(0, 200) + "…";
    }
}
