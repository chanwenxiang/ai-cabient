package com.aicabinet.trade.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 微信「商家转账」（MA-407）配置——与支付配置 {@link WeChatPayProperties} 分开：
 * 后者是付款/退款共用凭证，本类是转账业务参数（场景、报备、回调）。
 *
 * <p>🔴 全部走环境变量，生产可改而不改代码。默认场景 ID=1005（佣金报酬），
 * 与商户平台已开通场景一致；报备信息按官方文档 4013774590 固定两条
 * （岗位类型 + 报酬说明），info_type 不可改，info_content 可配。
 */
@ConfigurationProperties(prefix = "aicabinet.wechat-transfer")
public record WeChatTransferProperties(
        boolean enabled,
        /**
         * 收款模式（MA-407）：
         * <ul>
         *   <li>{@code CONFIRM} —— 用户确认收款模式（{@code /transfer-bills} + JSAPI 拉起确认页）。
         *       商家转账产品开通即可用；<b>每笔都要用户点一次确认</b>。</li>
         *   <li>{@code AUTHORIZED} —— 用户授权免确认模式（{@code /user-confirm-authorization}
         *       + {@code /transfer-bills/transfer}）。体验最好，但该产品<b>是白名单</b>
         *       （官方社区答复：未全量开放、不支持自主申请），2026-11-23 实测本商户号
         *       返回 403 NO_AUTH「商户无权限发起授权或权限被处罚」。</li>
         * </ul>
         * 默认 {@code CONFIRM}：先保证「能真打款」，白名单下来后改一行环境变量即可切免确认。
         */
        String mode,
        /** 转账场景 ID，商户平台「产品中心→商家转账→产品设置」查看。默认 1005=佣金报酬。 */
        String sceneId,
        /** 佣金报酬报备：岗位类型 info_content（默认「商户经营者」）。 */
        String reportJobType,
        /** 佣金报酬报备：报酬说明 info_content（默认「平台结算款提现」）。 */
        String reportRewardNote,
        /** 用户收款感知：劳务报酬/报销款/企业补贴/开工利是，空=场景默认。 */
        String userRecvPerception,
        /** 免确认授权结果回调地址（官方 4015901167 <b>必填</b>：公网 HTTPS、不带参数）。 */
        String authorizationNotifyUrl,
        /** 转账结果回调地址（公网 HTTPS，无 query）。 */
        String transferNotifyUrl
) {
    public String sceneId() {
        return isBlank(sceneId) ? "1005" : sceneId.trim();
    }

    /** 归一化收款模式：只认 AUTHORIZED，其余（含空）按 CONFIRM —— 宁可多让用户点一次，也不静默走白名单接口。 */
    public String mode() {
        return "AUTHORIZED".equalsIgnoreCase(isBlank(mode) ? "" : mode.trim()) ? "AUTHORIZED" : "CONFIRM";
    }

    public boolean confirmMode() {
        return "CONFIRM".equals(mode());
    }

    public String reportJobType() {
        return isBlank(reportJobType) ? "商户经营者" : reportJobType.trim();
    }

    public String reportRewardNote() {
        return isBlank(reportRewardNote) ? "平台结算款提现" : reportRewardNote.trim();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
