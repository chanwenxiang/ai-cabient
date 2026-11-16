package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.RefundFlow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;

import java.util.LinkedHashMap;

public interface RefundFlowMapper extends BaseTradeMapper<RefundFlow> {

    /**
     * B3 修正：插入一笔流水，任一唯一键（refund_no / idem_key）冲突时静默跳过。
     *
     * <p>用 {@code ON CONFLICT DO NOTHING} 而不是 catch DuplicateKeyException——后者在
     * PostgreSQL 里会把调用方事务打成 aborted（25P02），退款 finalize 的后续写全部失败。
     * 不指定冲突目标：两张唯一索引（渠道 refund_no、本地 idem_key）都要覆盖。</p>
     *
     * @return 1=本次插入；0=唯一键冲突（幂等命中）
     */
    @Insert("""
            INSERT INTO refund_flow (order_id, merchant_id, order_pay_channel, amount_cents,
                                     refund_method, refund_no, idem_key, refunded_at, reason)
            VALUES (#{orderId}, #{merchantId}, #{orderPayChannel}, #{amountCents},
                    #{refundMethod}, #{refundNo}, #{idemKey}, #{refundedAt}, #{reason})
            ON CONFLICT DO NOTHING
            """)
    @Options(useGeneratedKeys = true, keyProperty = "flowId", keyColumn = "flow_id")
    int insertIgnore(RefundFlow row);

    /** 单笔流水（巡检/对账用）。 */
    @Select("SELECT * FROM refund_flow WHERE flow_id = #{flowId}")
    LinkedHashMap<String, Object> selectRawById(Long flowId);
}
