package com.aicabinet.common.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 实名二要素请求。
 *
 * <p>🔴 <b>2026-10-20：idCardLast4 → idCard（完整 18 位）</b>。
 * 成因：接入真实二要素核验服务商（阿里云市场 APPCODE 型 API，body 传
 * {@code name} + {@code id_number}）后，权威库比对必须用<b>完整证件号</b>——
 * 后 4 位无法查权威库，旧契约对任何真 vendor 都不可用。
 *
 * <p>隐私边界不变：完整证件号<b>只透传给核验服务商</b>，本地不落库
 * （{@code user_realname_auth} 恒 0 行是有意设计，见 WithdrawEligibilityService），
 * 日志禁打 PII。
 */
public record VerifyIdentityRequest(
        @NotBlank @Size(min = 2, max = 32) String realName,
        @NotBlank @Pattern(regexp = "\\d{17}[\\dXx]", message = "idCard must be an 18-digit ID number (last char may be X)")
        String idCard
) {}
