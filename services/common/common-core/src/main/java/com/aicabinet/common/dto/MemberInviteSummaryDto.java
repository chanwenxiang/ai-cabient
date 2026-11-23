package com.aicabinet.common.dto;

/**
 * 我的邀请（营销 2）：小程序分享卡与「邀请进度」共用一份口径。
 *
 * @param inviteCode    我的邀请码（存量会员首次读取时惰性生成）
 * @param inviteeCount  已绑定到我名下的人数
 * @param rewardedCount 其中已完成首单（邀请人券已发）的人数
 * @param enabled       邀请活动是否开启（关闭时前端隐藏入口，而不是让用户点了报错）
 */
public record MemberInviteSummaryDto(
        String inviteCode,
        int inviteeCount,
        int rewardedCount,
        boolean enabled
) {}
