package com.aicabinet.trade.service;

import com.aicabinet.trade.mapper.CabinetOrderMapper;
import com.aicabinet.trade.mapper.DeviceInfoMapper;
import com.aicabinet.trade.mapper.ShoppingSessionMapper;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Collection;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R20 批 D：**下推判据**的离线断言（不需要数据库）。
 *
 * <p>这四条缺陷的共性：Java 侧逻辑没变，变的是「哪些过滤留在内存里」。这类回归纯业务单测
 * 抓不住——方法照旧返回正确结果，只是把全表拉回内存再筛。所以判据必须钉在 SQL 本身。
 *
 * <p>用反射读注解真值，不用 grep 源码：javadoc 里写着同样的关键字（例如本类断言的
 * {@code RETIRED} / {@code GROUP BY}），按文本搜会被自己的注释骗成假绿。
 */
class QueryPushdownPredicateTest {

    private static final String RETIRED = "RETIRED";
    private static final String WAREHOUSE = "WAREHOUSE";
    private static final String SCRAPPED = "SCRAPPED";
    private static final String DECOMMISSIONED = "DECOMMISSIONED";

    /**
     * P2-16：附近柜机的三个过滤（有坐标 / 生命周期投放态 / 地理矩形）都必须在 SQL 里。
     * 少任何一条就等于把全表载回内存——线上柜机越多越慢，且一次「附近柜机」拉全表。
     */
    @Test
    void findNearbyCandidates_pushesCoordinatesLifecycleAndBox() throws Exception {
        String sql = sqlOf(DeviceInfoMapper.class, "findNearbyCandidates",
                double.class, double.class, Double.class, Double.class);
        String n = normalize(sql);

        assertTrue(n.contains("LATITUDE IS NOT NULL") && n.contains("LONGITUDE IS NOT NULL"),
                "无坐标的柜机必须由 SQL 排除（内存版 hasCoordinates 已并入 SQL）");
        assertTrue(n.contains("LATITUDE BETWEEN #{MINLAT} AND #{MAXLAT}"),
                "纬度矩形必须下推，否则仍要全表扫描");
        assertTrue(n.contains("LONGITUDE BETWEEN #{MINLON} AND #{MAXLON}"),
                "经度矩形必须下推（跨日界线时才由 <if> 关掉）");
        assertTrue(n.contains("IS_DELETED = FALSE"),
                "注解 SQL 不受 @TableLogic 管，逻辑删除谓词必须手写");

        // 生命周期：归一化口径必须与内存版 isExcludedLifecycle 一致（trim + upper 后比对）
        assertTrue(n.contains("UPPER(TRIM(LIFECYCLE_STATUS))"),
                "生命周期判定要先 trim+upper，否则 ' retired ' 这类脏值会漏排");
        assertTrue(n.contains("LIFECYCLE_STATUS IS NULL"),
                "NULL 必须显式放行：SQL 三值逻辑里 NULL NOT IN (...) 为 NULL，会整批滤掉历史柜机");
        for (String life : new String[]{RETIRED, WAREHOUSE, SCRAPPED, DECOMMISSIONED}) {
            assertTrue(n.contains("'" + life + "'"), "排除集合缺 " + life);
        }
    }

    /**
     * P2-17：各柜开门数必须一条 group-by（原来是「柜机数」次 count）。
     */
    @Test
    void countOpensGroupByDeviceSince_isSingleGroupByAggregate() throws Exception {
        String sql = sqlOf(ShoppingSessionMapper.class, "countOpensGroupByDeviceSince", Instant.class);
        String n = normalize(sql);

        assertTrue(n.contains("GROUP BY DEVICE_ID"), "必须按设备分组一次取回，不能退回逐台 count");
        assertTrue(n.contains("COUNT(*)"), "聚合列名必须与消费侧 agg.get(\"opens\") 对齐");
        assertTrue(n.contains("AS OPENS"), "结果列别名必须是 opens（Map 返回不做驼峰转换）");
        assertTrue(n.contains("CREATED_AT > #{SINCE}"),
                "时间窗谓词与逐台版同口径（严格大于，含不含边界会差一分钟的量）");
        assertTrue(n.contains("DEVICE_ID IS NOT NULL"),
                "device_id 为空的历史脏行不得进桶，否则会造出一台不存在的柜机");
    }

    /**
     * P2-15：用券订单必须按活动一次聚合，且去重后再求和。
     *
     * <p>{@code SELECT DISTINCT} 是关键判据：join 后同一订单若匹配多行，
     * {@code COUNT(DISTINCT ...)} 与 {@code SUM(...)} 会让「单数」与「金额」口径分叉（金额翻倍）。
     */
    @Test
    void sumCouponOrdersByActivitySince_joinsAndDeduplicates() throws Exception {
        String sql = sqlOf(CabinetOrderMapper.class, "sumCouponOrdersByActivitySince",
                Collection.class, Instant.class);
        String n = normalize(sql);

        assertTrue(n.contains("SELECT DISTINCT"), "先按 (活动, 订单, 金额) 去重，再 COUNT/SUM");
        assertTrue(n.contains("JOIN COUPON_DEFINITION"), "订单→券实例→券定义才能归到活动");
        assertTrue(n.contains("JOIN CABINET_ORDER"), "缺订单表就拿不到营收与券面额");
        assertTrue(n.contains("GROUP BY T.ACTIVITY_ID"), "必须按活动分组一次取回");
        assertTrue(n.contains("O.CREATED_AT >= #{SINCE}"), "时间窗口径：>= since（含当天起点）");
        assertFalse(n.contains("COUNT(DISTINCT"),
                "去重已由子查询完成，外层再用 COUNT(DISTINCT) 会掩盖重复行被 SUM 放大的问题");
    }

    private static String normalize(String sql) {
        // 注解里是 XML（<script>），比较符常被写成实体 &gt;/&lt;；断言前先还原成真实符号，
        // 否则「SQL 里明明有 >=」会被 &GT;= 判成缺失（假红）。
        return sql.replace("&gt;", ">").replace("&lt;", "<")
                .replaceAll("\\s+", " ").toUpperCase();
    }

    private static String sqlOf(Class<?> mapper, String name, Class<?>... params) throws Exception {
        Method m = mapper.getMethod(name, params);
        Select select = m.getAnnotation(Select.class);
        assertTrue(select != null, name + " 缺 @Select");
        return String.join(" ", select.value());
    }
}
