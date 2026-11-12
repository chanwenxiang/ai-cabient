package com.aicabinet.trade.service;

import com.aicabinet.trade.mapper.CabinetOrderLineMapper;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R20-13：活动限购计数的 SQL 谓词判据（离线，不需要数据库）。
 *
 * <p>缺陷是「谓词漏了状态过滤」：{@code cabinet_order_line.activity_id} 一有值就计数，
 * 未支付（PENDING）与已取消（CANCELLED）的单也白占名额 ⇒ user_limit=1 的用户只要留一张
 * 未支付单就永远享受不到活动价。这种缺陷**纯业务层单测抓不到**（限购判定逻辑没变，
 * 变的是 SQL 取数范围），所以在这里直接钉住两条计数 SQL 的谓词形态。
 *
 * <p>用反射读 {@code @Select} 真值而不是 grep 源码：源码里的 javadoc 也写着 PENDING/CANCELLED，
 * 按文本搜会被注释骗过（门禁假绿）。
 */
class PromotionUsageLimitPredicateTest {

    @Test
    void countOrdersForActivity_filtersPendingAndCancelledOrders() throws Exception {
        String sql = sqlOf("countOrdersForActivity", Long.class, Long.class);
        assertStatusGate(sql, "countOrdersForActivity");
    }

    @Test
    void countOrdersForActivityExcluding_filtersPendingAndCancelledOrders() throws Exception {
        String sql = sqlOf("countOrdersForActivityExcluding", Long.class, Long.class, String.class);
        assertStatusGate(sql, "countOrdersForActivityExcluding");
        assertTrue(sql.contains("l.order_id <> #{excludeOrderId}"), "排除本单的判据不能被顺手删掉");
    }

    private static void assertStatusGate(String sql, String method) {
        String normalized = sql.replaceAll("\\s+", " ").toUpperCase();
        assertTrue(normalized.contains("JOIN CABINET_ORDER O"),
                method + " 必须 JOIN 订单表才能按订单状态过滤");
        assertTrue(normalized.contains("O.STATUS NOT IN ('PENDING', 'CANCELLED')")
                        || normalized.contains("O.STATUS NOT IN ('PENDING','CANCELLED')"),
                method + " 必须排除 PENDING/CANCELLED，否则未支付/已取消的单白占限购名额");
        assertFalse(normalized.contains("EXISTS ( SELECT 1 FROM CABINET_ORDER"),
                method + " 不应用 EXISTS 子查询取用户（状态谓词会漏在子查询外）");
    }

    private static String sqlOf(String name, Class<?>... params) throws Exception {
        Method m = CabinetOrderLineMapper.class.getMethod(name, params);
        Select select = m.getAnnotation(Select.class);
        assertTrue(select != null, name + " 缺 @Select");
        return String.join(" ", select.value());
    }
}
