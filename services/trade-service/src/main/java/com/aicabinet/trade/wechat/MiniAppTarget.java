package com.aicabinet.trade.wechat;

/**
 * 微信小程序双端路由（2026-10-19）：平台有**两个独立小程序主体**，
 * openid/access_token 均按 appid 隔离，所有微信侧调用必须显式声明走哪一端。
 *
 * <ul>
 *   <li>{@link #CONSUMER} —— 消费者小程序（wx.login 登录、手机号授权、C 端订阅消息、
 *       扫码落地 URL Link、流量主广告对账）</li>
 *   <li>{@link #MERCHANT} —— 商户小程序（商户微信登录/绑定、商户运营待办/设备异常订阅消息）</li>
 * </ul>
 *
 * <p>🔴 不能省略或混用：拿消费者 appid+secret 去换商户小程序的 wx.login code，
 * 微信直接回 40029 invalid code。
 */
public enum MiniAppTarget {
    CONSUMER,
    MERCHANT
}
