package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.OrderSplitReturnLeg;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R20-9 / 铁律 84④：SQL 判据读**注解真值**，不 grep 源码。
 *
 * <p>javadoc 里同样写着 status / ON CONFLICT 这些关键词，按文本搜会被自己的注释骗成假绿；
 * 反射拿到的才是 MyBatis 真正会执行的字符串。这里还把 @Select 的开放腿谓词与 V463
 * partial index 的谓词逐字对齐（铁律 88①：谓词不蕴含，索引就永远用不上）。
 */
class OrderSplitReturnLegSqlPredicateTest {

    private static final String OPEN_PREDICATE = "status IN ('PENDING','PROCESSING','FAILED')";

    private static String sql(String name,
                              Class<? extends Annotation> type,
                              Class<?>... params) throws Exception {
        Method method = OrderSplitReturnLegMapper.class.getMethod(name, params);
        Annotation annotation = method.getAnnotation(type);
        assertNotNull(annotation, name + " 缺 @" + type.getSimpleName());
        if (annotation instanceof Select select) {
            return String.join("\n", select.value());
        }
        if (annotation instanceof Update update) {
            return String.join("\n", update.value());
        }
        if (annotation instanceof Insert insert) {
            return String.join("\n", insert.value());
        }
        throw new AssertionError("unsupported annotation " + type);
    }

    @Test
    void findOpenLegs_hasFullIndexPredicateAndStableOrder() throws Exception {
        String sql = sql("findOpenLegs", Select.class, int.class);
        assertTrue(sql.contains(OPEN_PREDICATE), "开放腿查询谓词必须完整，否则 partial index 用不上：" + sql);
        assertTrue(sql.contains("ORDER BY leg_id ASC"), "批量取必须自带排序（铁律 88②）");
        assertTrue(sql.contains("LIMIT #{limit}"), "必须有上限，不能全表拖回内存");
    }

    @Test
    void aggregates_useExplicitStatusPredicates() throws Exception {
        String success = sql("sumSuccessCents", Select.class, String.class);
        assertTrue(success.contains("status = 'SUCCESS'"), "守恒聚合只数已成功腿：" + success);
        String open = sql("sumOpenCents", Select.class, String.class);
        assertTrue(open.contains(OPEN_PREDICATE), "告警聚合必须按完整开放谓词：" + open);
    }

    @Test
    void updateOutcome_neverResurrectsSuccessLeg() throws Exception {
        String sql = sql("updateOutcomeIfNotSuccess", Update.class,
                long.class, String.class, String.class);
        assertTrue(sql.contains("status <> 'SUCCESS'"), "SUCCESS 是终态，条件更新必须挡住复活（铁律 87②）：" + sql);
        assertTrue(sql.contains("updated_at = NOW()"), "状态推进要留更新时间");
    }

    @Test
    void insertLeg_isIdempotentOnChannelReturnNo() throws Exception {
        String sql = sql("insertLegIfAbsent", Insert.class, OrderSplitReturnLeg.class);
        assertTrue(sql.contains("ON CONFLICT (out_return_no) DO NOTHING"),
                "并发/回放同号不得插出第二条腿：" + sql);
        assertTrue(sql.contains("INSERT INTO order_split_return_leg"), "写入必须落到腿表：" + sql);
    }

    @Test
    void migrationPredicate_matchesAnnotationPredicateVerbatim() throws Exception {
        String migration = resource("db/migration/V463__order_split_return_leg.sql");
        String sql = sql("findOpenLegs", Select.class, int.class);
        assertTrue(migration.contains(OPEN_PREDICATE), "V463 partial index 谓词缺失");
        assertTrue(sql.contains(OPEN_PREDICATE), "@Select 谓词与索引不一致");
        assertTrue(migration.contains("ON CONFLICT (out_return_no) DO NOTHING"), "存量回填必须幂等");
        assertTrue(migration.contains("out_return_no   VARCHAR(128) NOT NULL UNIQUE"),
                "渠道单号必须唯一（多轮不得合并成一个号）");
        assertTrue(migration.contains("CHECK (cents > 0)"), "腿金额必须为正");
    }

    private static String resource(String path) {
        try (InputStream in = OrderSplitReturnLegSqlPredicateTest.class.getClassLoader()
                .getResourceAsStream(path)) {
            assertNotNull(in, "classpath 缺 " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
