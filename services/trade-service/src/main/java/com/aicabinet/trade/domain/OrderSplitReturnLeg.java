package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/**
 * 一笔微信分账回退单 = 一条「腿」（R20-9 / CB-085 / V463）。
 *
 * <p>改前 {@code order_revenue_split.wechat_pending_return_no / _cents} 是一对单值列，
 * 多轮部分退时后一轮覆盖前一轮 ⇒ 渠道只回退最后一笔，商户多留钱。本表按
 * {@code out_return_no}（微信协议幂等键）一行一笔立账，金额只从这里读。
 *
 * <p>{@code split} 上那两列不删除，降级为「最老一条未完结腿」的派生投影，
 * 仅供存量报表/对账读取，任何资金动作不得再把它当真值。
 */
@TableName("order_split_return_leg")
@Getter
@Setter
public class OrderSplitReturnLeg {

    @TableId(type = IdType.AUTO)
    private Long legId;

    private String splitId;

    /** 微信回退单号，协议幂等键；同一单号重报走 upsert，绝不合并多轮换新号。 */
    private String outReturnNo;

    /** 本腿回退金额（分），> 0。 */
    private long cents;

    /** PENDING / PROCESSING / SUCCESS / FAILED；SUCCESS 是终态，不得复活。 */
    private String status = "PENDING";

    private String failureReason;

    private Instant createdAt;

    private Instant updatedAt;
}
