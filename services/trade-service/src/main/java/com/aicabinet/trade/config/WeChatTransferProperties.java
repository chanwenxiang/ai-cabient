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
        /** 转账场景 ID，商户平台「产品中心→商家转账→产品设置」查看。默认 1005=佣金报酬。 */
        String sceneId,
        /** 佣金报酬报备：岗位类型 info_content（默认「商户经营者」）。 */
        String reportJobType,
        /** 佣金报酬报备：报酬说明 info_content（默认「平台结算款提现」）。 */
        String reportRewardNote,
        /** 用户收款感知：劳务报酬/报销款/企业补贴/开工利是，空=场景默认。 */
        String userRecvPerception,
        /** 免确认授权结果回调地址（公网 HTTPS，无 query）。 */
        String authorizationNotifyUrl,
        /** 转账结果回调地址（公网 HTTPS，无 query）。 */
        String transferNotifyUrl
) {
    public String sceneId() {
        return isBlank(sceneId) ? "1005" : sceneId.trim();
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
