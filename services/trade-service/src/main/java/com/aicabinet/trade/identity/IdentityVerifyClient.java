package com.aicabinet.trade.identity;

import com.aicabinet.trade.config.IdentityVerifyProperties;
import com.aicabinet.trade.config.SecurityProperties;
import com.aicabinet.trade.support.ApiMessages;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

/**
 * 实名二要素：mock 直接通过；生产须配置 base-url，未配置则失败关闭。
 *
 * <p>2026-10-20 对接阿里云市场「身份证二要素」vendor
 * （{@code POST https://swidverify.market.alicloudapi.com/verify/identity}）：
 * <ul>
 *   <li>Content-Type 为 <b>x-www-form-urlencoded</b>（vendor 要求，非 JSON）；</li>
 *   <li>鉴权 {@code Authorization: APPCODE <appCode>}（properties.apiKey() 存 APPCODE）；</li>
 *   <li>业务判据：HTTP 200 且 {@code result_code=="0000"} 且
 *       {@code result.checkresult==1} 才算核验通过（宁可拒不可放）。</li>
 * </ul>
 */
@Service
public class IdentityVerifyClient {

    private static final Logger log = LoggerFactory.getLogger(IdentityVerifyClient.class);

    /** vendor 响应里的「接口调用成功」返回码（≠核验通过；核验通过另看 checkresult）。 */
    private static final String VENDOR_OK_CODE = "0000";
    /** checkresult=1 身份证号和姓名一致（唯一「通过」值）。 */
    private static final int CHECK_MATCHED = 1;
    /** checkresult=2 身份证号和姓名不一致。 */
    private static final int CHECK_MISMATCHED = 2;
    /** checkresult=3 库无（户籍迁移未更新/现役军人等）。 */
    private static final int CHECK_NO_RECORD = 3;
    /** checkresult=110 同一身份证号一天内最多核验 10 次。 */
    private static final int CHECK_RATE_LIMITED = 110;

    private final SecurityProperties securityProperties;
    private final IdentityVerifyProperties properties;
    private final RestClient restClient = RestClient.create();

    public IdentityVerifyClient(SecurityProperties securityProperties,
                                IdentityVerifyProperties properties) {
        this.securityProperties = securityProperties;
        this.properties = properties;
    }

    /**
     * 核验结果。
     *
     * @param verified 核验是否真的通过了
     * @param mock     是否是<b>开发/测试环境</b>的假通过（mock 开启时跳过了一切校验）
     */
    public record VerifyOutcome(boolean verified, boolean mock) {
        /** 🔴 只有「真核验通过」才算实名；mock 通过<b>必须</b>由调用方区别对待。 */
        public boolean trusted() {
            return verified && !mock;
        }
    }

    /**
     * 核验身份（二要素：姓名 + 完整身份证号）。
     *
     * 🔴🔴 <b>mock 时返回 {@code mock=true} 而不是静默「通过」</b>。
     *
     * <p>2026-10-20 契约改版：idCardLast4 → 完整 18 位身份证号。
     * 成因：权威库比对必须完整证件号，后 4 位对任何真 vendor 不可用。
     * 隐私边界：<b>完整证件号只透传给核验服务商</b>（HTTPS 表单体），本地不落库、不进日志。
     *
     * @throws ResponseStatusException 400 格式/校验位/核验不通过；429 当日核验次数超限；
     *                                 503 未配置第三方且mock 关闭（fail-loud，不静默放行）；502 vendor 不可用
     */
    public VerifyOutcome verify(String realName, String idCard) {
        if (securityProperties.mockEnabled()) {
            // ⚠️ 只打 debug 且**不记录 PII**；warn 会暴露「哪些环境没接实名」，
            //    但这类信息不该出现在常态日志里。
            log.debug("identity verify skipped (mock-enabled) —— 结果标记为 mock，调用方不得当作真核验");
            return new VerifyOutcome(true, true);
        }
        if (!properties.isConfigured()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, ApiMessages.IDENTITY_VERIFY_UNAVAILABLE);
        }
        String name = realName == null ? "" : realName.trim();
        String idNumber = idCard == null ? "" : idCard.trim().toUpperCase();
        if (name.isEmpty() || idNumber.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ApiMessages.IDENTITY_VERIFY_FAILED);
        }
        // 🔴 校验位先行：按次计费的 vendor 不该收到格式都不对的请求（白烧钱）。
        IdCardValidator.requireValid(idNumber);
        return new VerifyOutcome(doVerify(name, idNumber), false);
    }

    /** 真实核验：返回是否通过。⚠️ 不记录 PII（姓名/证件号禁进日志与异常消息）。 */
    private boolean doVerify(String name, String idNumber) {
        try {
            // vendor 要求 x-www-form-urlencoded（取自其官方示例 headers：Content-Type
            // application/x-www-form-urlencoded; charset=UTF-8）。FormHttpMessageConverter
            // 默认 UTF-8，中文姓名编码正确。
            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("name", name);
            form.add("id_number", idNumber);
            var spec = restClient.post()
                    .uri(properties.baseUrl().trim())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form);
            if (properties.apiKey() != null && !properties.apiKey().isBlank()) {
                // 阿里云市场 APPCODE 简单身份认证（官方示例：Authorization: APPCODE xxx）。
                spec = spec.header("Authorization", "APPCODE " + properties.apiKey().trim());
            }
            IdentityVerifyResponse body = spec.retrieve().body(IdentityVerifyResponse.class);
            if (body != null && body.isOk()) {
                return true;
            }
            // 🔴 打字段级日志：区分「明确不通过」（用户信息不对）与「字段全缺/调用失败」
            //（对接问题/契约变更）—— 排查方向完全不同。describe() 不含 PII。
            log.warn("identity verify not passed: {}", body == null ? "empty body" : body.describe());
            throw toUserFacingError(body);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("identity verify upstream failed", e);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, ApiMessages.IDENTITY_VERIFY_UNAVAILABLE);
        }
    }

    /**
     * 把 vendor 的「非通过」响应映射为面向用户的错误。
     *
     * <p>分层（保守，宁可拒不可放）：
     * <ul>
     *   <li>{@code result_code != "0000"}（vendor 调用层失败，如 2102 参数错误）⇒
     *       <b>502</b>——这是对接/契约问题，不是用户错，别让用户背锅重试；</li>
     *   <li>{@code checkresult=2}（不一致）⇒ 400 通用「未通过」；</li>
     *   <li>{@code checkresult=3}（库无）⇒ 400 + 指引文案（户籍迁移/军人/新证未更新）；</li>
     *   <li>{@code checkresult=110}（同证号当日超 10 次）⇒ 429 + 次日再试；</li>
     *   <li>其余（字段缺失/未知值）⇒ 400 通用拒绝——<b>fail-closed</b>。</li>
     * </ul>
     */
    private ResponseStatusException toUserFacingError(IdentityVerifyResponse body) {
        if (body == null || body.resultCode() == null || !VENDOR_OK_CODE.equals(body.resultCode())) {
            // 调用层失败（含 HTTP 200 但 result_code 异常）⇒ 我方/上游问题，502。
            return new ResponseStatusException(HttpStatus.BAD_GATEWAY, ApiMessages.IDENTITY_VERIFY_UNAVAILABLE);
        }
        Integer check = body.result() == null ? null : body.result().checkresult();
        if (Integer.valueOf(CHECK_MISMATCHED).equals(check)) {
            return new ResponseStatusException(HttpStatus.BAD_REQUEST, ApiMessages.IDENTITY_VERIFY_FAILED);
        }
        if (Integer.valueOf(CHECK_NO_RECORD).equals(check)) {
            return new ResponseStatusException(HttpStatus.BAD_REQUEST, ApiMessages.IDENTITY_VERIFY_NO_RECORD);
        }
        if (Integer.valueOf(CHECK_RATE_LIMITED).equals(check)) {
            return new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, ApiMessages.IDENTITY_VERIFY_RATE_LIMITED);
        }
        // 未知/缺失 checkresult ⇒ 不猜「没报错就是过」。
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, ApiMessages.IDENTITY_VERIFY_FAILED);
    }

    /**
     * vendor 核验响应（snake_case → camelCase 由 {@code @JsonNaming} 绑定）。
     *
     * 🔴🔴 {@link #isOk()} 的判据是**安全关键逻辑**，改这里前先读完这段说明。
     *
     * <p>vendor 契约（2026-10-20 取证，用户提供的官方文档）：
     * <pre>{@code
     * { "result_code": "0000", "result_msg": "调用成功", "charge": 1,
     *   "result": { "checkresult": 1, "message": "身份证号和姓名一致" },
     *   "request_id": "..." }
     * }</pre>
     * checkresult：1=一致；2=不一致；3=库无；110=同证号当日超限。
     *
     * <p><b>为什么只有 {@code checkresult==1} 算通过</b>：
     * {@code result_code=="0000"} 只说明「接口调用成功」，<b>不说明「核验通过」</b>——
     * 与旧实现防的坑同源：把「请求处理成功」误当「核验通过」⇒ 未实名用户被标记为已实名。
     * 任何字段缺失/未知值一律不通过（fail-closed，宁可拒不可放）。
     *
     * <p>🔴 {@code ignoreUnknown} 是**显式契约**而非侥幸：vendor 响应还带
     * {@code charge}/{@code request_id} 等 我们不消费的字段，且 vendor 以后可能加字段
     * （测试用裸 ObjectMapper 证明该注解生效，不依赖 Boot 的全局配置）。
     */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record IdentityVerifyResponse(String resultCode, String resultMsg, Result result) {

        @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
        public record Result(Integer checkresult, String message) {}

        /** 🔴 只有「调用成功 + checkresult=1（明确一致）」才为 true。 */
        boolean isOk() {
            return VENDOR_OK_CODE.equals(resultCode)
                    && result != null
                    && Integer.valueOf(CHECK_MATCHED).equals(result.checkresult());
        }

        /**
         * 🔴 供日志/排障用：区分「字段缺失/调用失败」与「明确不通过」，
         * 二者的排查方向完全不同（前者=对接问题，后者=用户信息不对）。
         * ⚠️ 不含 PII（只含 vendor 返回的结论字段）。
         */
        String describe() {
            return "resultCode=" + resultCode + ", resultMsg=" + resultMsg
                    + ", checkresult=" + (result == null ? null : result.checkresult());
        }
    }
}
