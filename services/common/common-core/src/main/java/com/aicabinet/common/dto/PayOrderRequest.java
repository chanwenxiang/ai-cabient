package com.aicabinet.common.dto;

import jakarta.validation.constraints.Pattern;

/**
 * 消费者补缴待支付订单时**显式选择**的支付方式（F6「结算页支付方式选择」）。
 *
 * <p>{@code channel} 可空 —— 空表示**沿用服务端既有决策**（用户偏好 → 扫码入口渠道 → 已签约渠道 → 余额兜底），
 * 即与接入本字段前逐字节一致；老客户端不发 body 时走的就是这条路径。
 *
 * <p>非空时为显式选择，语义与「自动结算」不同：
 * <ul>
 *   <li>只按所选渠道扣款，**绝不静默降级**到别的渠道 —— 否则用户以为走了免密、实际被扣了余额；</li>
 *   <li>所选渠道未就绪（未签约支付分 / 无有效支付宝协议 / 非法值）⇒ 返回 412，由前端提示改选或先去开通。</li>
 * </ul>
 *
 * <p>与扫码入口取值域相同（BALANCE / WECHAT / ALIPAY）。本字段只影响**这一笔**，
 * 且必须与本次扫码入口一致——不做跨渠道回落，所选渠道未就绪时由前端提示改选余额。
 */
public record PayOrderRequest(
        @Pattern(regexp = "(?i)BALANCE|WECHAT|ALIPAY", message = "channel must be BALANCE, WECHAT or ALIPAY")
        String channel
) {}
