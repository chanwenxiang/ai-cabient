package com.aicabinet.common.dto;

/**
 * 微信免确认收款授权申请结果（MA-407）。
 *
 * @param outAuthorizationNo 商户侧授权单号（前端拉起授权页后原样带回，用于查状态）
 * @param packageInfo        微信返回的 package，用于 JSAPI 调起免确认收款授权页
 * @param state              授权单状态（WAIT_USER_CONFIRM=待用户在微信点同意）
 * @param mchId              商户号，wx.requestMerchantTransfer 必填且须与授权申请同一个
 */
public record WechatPayoutAuthResponse(
        String outAuthorizationNo,
        String packageInfo,
        String state,
        String mchId
) {}
