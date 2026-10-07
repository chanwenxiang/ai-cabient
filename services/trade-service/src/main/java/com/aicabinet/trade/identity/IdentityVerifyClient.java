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
                // 🔴 打字段级日志：区分「明确不通过」（用户信息不对）与
                // 「字段全缺」（第三方对接问题/契约变更）—— 排查方向完全不同。
                // ⚠️ **不记录 realName / idCardLast4**（PII 禁止进日志）。
                log.warn("identity verify not passed: {}", body == null ? "empty body" : body.describe());
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ApiMessages.IDENTITY_VERIFY_FAILED);
            }
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("identity verify upstream failed", e);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, ApiMessages.IDENTITY_VERIFY_UNAVAILABLE);
        }
    }

    /**
     * 第三方核验响应。
     *
     * 🔴🔴 {@link #isOk()} 的判据是**安全关键逻辑**，改这里前先读完这段说明。
     *
     * <p><b>为什么不能「三个字段任一为 true 就算过」</b>：
     * 多数第三方核验 API 里 {@code success/matched} 的语义是
     * 「**请求处理成功**」或「**信息匹配**」，而 {@code passed} 才是
     * 「**核验通过**」。若第三方返回 {@code {success:true, passed:false}}
     *（请求成功但核验不通过，例如姓名与后 4 位不匹配），
     * 旧实现会**当成核验通过** ⇒ 🔴 **未实名用户被标记为已实名**。
     *
     * <p><b>现在的规则（保守，宁可拒不可放）</b>：
     * <ol>
     *   <li>只认 <b>{@code passed}</b> 与 <b>{@code matched}</b> 为 {@code true}；
     *   <li>🔴 <b>{@code success} 单独为 true 一律不算通过</b>
     *       —— 它只说明「接口调通了」，不说明「核验通过」；
     *   <li>🔴 <b>{@code passed} 显式为 false 时一律拒绝</b>，
     *       即使 {@code matched=true}（明确不通过的结论优先）；
     *   <li>全部缺失 ⇒ 拒（不猜「没报错就是过」）。
     * </ol>
     *
     * <p>⚠️ 这样改的前提是：**第三方的「核验通过」确实落在
     * {@code passed} 或 {@code matched} 上**。
     * 🔴<b>该字段契约尚未取证</b>（未见第三方接口文档）⇒
     * 上线前必须与第三方对齐字段语义，否则会出现
     * 「明明核验通过了却被拒」的反向问题（那只是用户重试一次，不涉资损，
     * 属可接受方向；但要知道成因，别当成系统故障排查）。
     */
    public record IdentityVerifyResponse(Boolean matched, Boolean success, Boolean passed) {
        /**
         * 只有明确的「核验通过」标志才为 true。
         *
         * <p>🔴 {@code success} 单独为 true <b>不算通过</b>（它只表示「接口调通了」）。
         *
         * <p>🔴 <b>{@code passed} 显式为 false 时一律拒绝</b>，即使 {@code matched=true}
         * —— 「passed=false」是第三方给出的<b>明确不通过</b>结论，
         * 那种情况下「matched」多半只是「字段填得全」而非「核验通过」。
         * <b>宁可拒（用户重试）不可放（资损/合规）。</b>
         */
        boolean isOk() {
            if (Boolean.FALSE.equals(passed)) {
                return false; // 明确不通过 ⇒ 拒
            }
            return Boolean.TRUE.equals(passed) || Boolean.TRUE.equals(matched);
        }

        /**
         * 🔴 供日志/排障用：区分「字段缺失」与「明确不通过」，
         * 二者的排查方向完全不同（前者=对接问题，后者=用户信息不对）。
         */
        String describe() {
            return "passed=" + passed + ", matched=" + matched + ", success=" + success;
        }
    }
}
