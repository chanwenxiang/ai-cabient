package com.aicabinet.trade.mapper;

import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 资金/账单类 SQL 的静态契约钉（离线、不连库）：
 * 这两条 SQL 都曾因「JOIN 错表 / 内存 IN」而静默失效，必须把形状钉住。
 */
class MapperSqlContractTest {

    private static String sqlOf(Class<?> mapper, String method, Class<?>... params) {
        try {
            Method m = mapper.getMethod(method, params);
            Select select = m.getAnnotation(Select.class);
            if (select == null) {
                throw new AssertionError(method + " 必须带 @Select（SQL 在注解里）");
            }
            return String.join("\n", Arrays.asList(select.value()));
        } catch (NoSuchMethodException e) {
            throw new AssertionError(method + " 不存在", e);
        }
    }

    /** MA-284：充值退款流水 order_id 恒 NULL，必须 JOIN recharge_order（不能 JOIN cabinet_order）。 */
    @Test
    void refundSyncNotAdvanced_joinsRechargeOrder_notCabinetOrder() {
        String sql = sqlOf(PaymentOperationMapper.class, "findRefundSyncNotAdvanced", int.class);
        assertTrue(sql.contains("recharge_order"), "必须 JOIN recharge_order：充值退款流水的 order_id 恒 NULL");
        assertTrue(sql.contains("RECHARGE_REFUND"), "必须只看 RECHARGE_REFUND 流水");
        assertTrue(sql.contains("substring(p.reason from '#([^#]+)$')"),
                "必须用 reason 尾部 #orderId 关联充值单（PaymentOperation.order_id 对充值退款为 NULL）");
        assertFalse(sql.contains("JOIN cabinet_order o ON o.order_id = p.order_id"),
                "不能 JOIN cabinet_order：充值退款流水的 order_id 恒 NULL，JOIN 永远 0 行");
    }

    /** MA-287：结算单归月按订单 created_at，必须一条 SQL JOIN，不得把整月订单 id 拉进内存 IN。 */
    @Test
    void settlementSplitsByOrderCreatedAt_usesJoin_notInList() {
        String sql = sqlOf(OrderRevenueSplitMapper.class, "findByOrderCreatedAtBetween",
                java.time.Instant.class, java.time.Instant.class);
        assertTrue(sql.contains("JOIN cabinet_order"), "必须 JOIN cabinet_order 取订单 created_at");
        assertTrue(sql.contains("o.created_at"), "归月窗口必须按订单 created_at");
        assertTrue(sql.contains("settlement_batch_no IS NOT NULL"), "必须排除未生成批次的 split");
        assertFalse(sql.toLowerCase().contains(" in ("), "不得退回内存 IN 列表（整月订单 id 撞绑定参数上限）");
    }
}