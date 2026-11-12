package com.aicabinet.trade.mapper;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.time.Instant;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R20-P2-7 钉测（2026-11-12）：对账充值求和/取号的 **SQL 层**渠道过滤。
 *
 * <p>为什么单独钉 XML：单测里 mapper 是 mock，{@code when(...sumPaidAmountBetween(any(), any(), eq("WECHAT")))} 只能证明
 * <b>调用方传了</b>渠道，证明不了 <b>SQL 真的用了</b>它——谓词漏写会静默退化成「全渠道求和」，
 * 正是「假差异抵消真差异」这个缺陷的形态。此处离线解析 mapper XML（不连库、不要 Docker）。</p>
 */
class RechargeOrderMapperXmlTest {

    private static Configuration configuration;

    @BeforeAll
    static void parseMapper() throws Exception {
        configuration = new Configuration();
        try (InputStream in = Resources.getResourceAsStream("mapper/RechargeOrderMapper.xml")) {
            assertNotNull(in, "mapper/RechargeOrderMapper.xml 必须在 classpath 上");
            new XMLMapperBuilder(in, configuration, "mapper/RechargeOrderMapper.xml",
                    configuration.getSqlFragments()).parse();
        }
    }

    private static String sqlFor(String statementId, String channel) {
        MappedStatement ms = configuration.getMappedStatement(
                "com.aicabinet.trade.mapper.RechargeOrderMapper." + statementId);
        Map<String, Object> params = new HashMap<>();
        params.put("start", Instant.parse("2026-11-11T16:00:00Z"));
        params.put("end", Instant.parse("2026-11-12T16:00:00Z"));
        params.put("channel", channel);
        BoundSql bound = ms.getBoundSql(params);
        return bound.getSql().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    @Test
    void sumPaidAmountBetween_withChannel_filtersByChannel() {
        String sql = sqlFor("sumPaidAmountBetween", "WECHAT");
        assertTrue(sql.contains("upper(r.channel) = upper(?)"),
                "单渠道对账必须把渠道谓词下推到 SQL，否则会把别家渠道的充值算进本账：" + sql);
        assertTrue(sql.contains("r.status in ('paid', 'refunded')"), "既有口径不能被改写：" + sql);
    }

    @Test
    void sumPaidAmountBetween_nullChannel_keepsAggregateScope() {
        String sql = sqlFor("sumPaidAmountBetween", null);
        assertFalse(sql.contains("channel"),
                "channel=null 是 Mock 聚合账单口径，不得再过滤渠道：" + sql);
    }

    @Test
    void findPaidOrderIdsBetween_honoursSameScope() {
        // 金额与单号必须同 scope：只过滤其一，会让「合计对得上但订单全判 LEDGER_ONLY」
        assertTrue(sqlFor("findPaidOrderIdsBetween", "ALIPAY").contains("upper(r.channel) = upper(?)"));
        assertFalse(sqlFor("findPaidOrderIdsBetween", null).contains("channel"));
    }
}
