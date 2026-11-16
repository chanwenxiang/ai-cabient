package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.RefundFlow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;

import java.util.LinkedHashMap;

public interface RefundFlowMapper extends BaseTradeMapper<RefundFlow> {

    /**
     * MA-271 修正：插入一笔流水，{@code refund_no} 撞唯一键时静默跳过。
     *
     * <p>用 {@code ON CONFLICT DO NOTHING} 而不是 catch DuplicateKeyException——后者在
     * PostgreSQL 里会把调用方事务打成 aborted（25P02），退款 finalize 的后续写全部失败。
     * 并发重投由唯一索引裁决，插不进去即「另一投递已记录」。</p>
     *
     * @return 1=本次插入；0=唯一键冲突（幂等命中）
     */
    @Insert("""
            INSERT INTO refund_flow (order_id, merchant_id, order_pay_channel, amount_cents,
                                     refund_method, refund_no, refunded_at, reason)
            VALUES (#{orderId}, #{merchantId}, #{orderPayChannel}, #{amountCents},
                    #{refundMethod}, #{refundNo}, #{refundedAt}, #{reason})
            ON CONFLICT (refund_no) WHERE refund_no IS NOT NULL DO NOTHING
            """)
    @Options(useGeneratedKeys = true, keyProperty = "flowId", keyColumn = "flow_id")
    int insertIgnore(RefundFlow row);

    /** 单笔流水（巡检/对账用）。 */
    @Select("SELECT * FROM refund_flow WHERE flow_id = #{flowId}")
    LinkedHashMap<String, Object> selectRawById(Long flowId);
}
