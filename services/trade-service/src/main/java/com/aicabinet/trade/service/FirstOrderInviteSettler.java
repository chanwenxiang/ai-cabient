package com.aicabinet.trade.service;

/**
 * 首单邀请结算钩子（营销 2）。
 *
 * <p>为什么抽接口而不是直接依赖 {@code MemberInviteService}：结算发生在
 * {@code MemberService.onOrderPaid} 里，而那条链路已经持有 member 行的 {@code SELECT ... FOR UPDATE}
 * 锁；实现方只允许做「与 member 无关」的写（发券），不允许再碰 member 行，否则与调用方的行锁互等。
 * 用接口把这条边界钉住，单测也能塞一个空实现而不必拉起整条发券链。
 */
public interface FirstOrderInviteSettler {

    /**
     * 尝试把邀请奖励券发给邀请人。
     *
     * <p>幂等由调用方保证（{@code member.invite_settled_at}）；本方法只负责「发一张券」，
     * 失败抛异常，由调用方决定是否降级。
     *
     * @return 发出的券 id；未绑定邀请人 / 活动关闭 / 无券模板时返回 {@code null}（表示「本次不结算」）
     */
    Long settleInviterReward(Long inviteeUserId, Long inviterUserId, String orderId);
}
