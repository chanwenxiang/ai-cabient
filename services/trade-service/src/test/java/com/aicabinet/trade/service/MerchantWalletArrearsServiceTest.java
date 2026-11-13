package com.aicabinet.trade.service;

import com.aicabinet.trade.mapper.WalletAccountMapper;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CB-086：商户钱包欠款账龄探针。
 *
 * <p>判据三条：① 只读（SQL 里不得出现 UPDATE/DELETE）；② 账龄不可判（无流水）时**不报**，
 * 不得按 0 天误报；③ 只对「负余额连续满阈值」的商户计入合计，超阈值才推一条聚合告警。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MerchantWalletArrearsServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 13);

    @Mock private WalletAccountMapper accountMapper;
    @Mock private SystemConfigService configService;
    @Mock private OpsAlertDispatcher opsAlertDispatcher;

    private MerchantWalletArrearsService service(String thresholdDays) {
        when(configService.getValue(eq(MerchantWalletArrearsService.CONFIG_KEY), anyString()))
                .thenReturn(thresholdDays);
        return new MerchantWalletArrearsService(accountMapper, configService, opsAlertDispatcher);
    }

    private static Map<String, Object> row(String merchantId, long balanceCents, Instant since) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("c0", merchantId);
        m.put("c1", balanceCents);
        m.put("c2", since == null ? null : Timestamp.from(since));
        return m;
    }

    @Test
    void noNegativeAccount_returnsEmptyAndNoAlert() {
        when(accountMapper.findNegativeArrears("MERCHANT")).thenReturn(List.of());
        assertEquals("", service("90").dailySummary(TODAY));
        verify(opsAlertDispatcher, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    void ageBelowThreshold_silent() {
        when(accountMapper.findNegativeArrears("MERCHANT")).thenReturn(List.of(
                row("M1", -12_000L, TODAY.minusDays(89).atStartOfDay(java.time.ZoneOffset.UTC).toInstant())));
        assertEquals("", service("90").dailySummary(TODAY));
        verify(opsAlertDispatcher, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    void ageAtThreshold_alertsWithCountAndAmount() {
        when(accountMapper.findNegativeArrears("MERCHANT")).thenReturn(List.of(
                row("M1", -12_345L, TODAY.minusDays(90).atStartOfDay(java.time.ZoneOffset.UTC).toInstant())));
        String summary = service("90").dailySummary(TODAY);
        assertTrue(summary.contains("1 商户"), summary);
        assertTrue(summary.contains("123.45 元"), summary);
        assertTrue(summary.contains("最长账龄 90 天"), summary);
        verify(opsAlertDispatcher).send(
                eq(MerchantWalletArrearsService.ALERT_TYPE), anyString(), org.mockito.ArgumentMatchers.contains("不锁柜"));
    }

    @Test
    void mixedRows_onlyEligibleCounted_longestAgeWins() {
        when(accountMapper.findNegativeArrears("MERCHANT")).thenReturn(List.of(
                row("M1", -1_000L, TODAY.minusDays(10).atStartOfDay(java.time.ZoneOffset.UTC).toInstant()),
                row("M2", -2_000L, TODAY.minusDays(120).atStartOfDay(java.time.ZoneOffset.UTC).toInstant()),
                row("M3", -4_000L, TODAY.minusDays(95).atStartOfDay(java.time.ZoneOffset.UTC).toInstant())));
        String summary = service("90").dailySummary(TODAY);
        assertTrue(summary.contains("2 商户"), summary);
        assertTrue(summary.contains("60.00 元"), summary);
        assertTrue(summary.contains("最长账龄 120 天"), summary);
        // 最长账龄商户只在告警正文里点名（执行记录摘要不点商户名）
        verify(opsAlertDispatcher).send(eq(MerchantWalletArrearsService.ALERT_TYPE), anyString(),
                org.mockito.ArgumentMatchers.contains("M2"));
    }

    @Test
    void unjudgeableAge_isSkippedNotTreatedAsZero() {
        when(accountMapper.findNegativeArrears("MERCHANT")).thenReturn(List.of(
                row("M1", -999_999L, null)));
        assertEquals("", service("90").dailySummary(TODAY));
        verify(opsAlertDispatcher, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    void zeroThreshold_disablesProbe() {
        assertEquals("", service("0").dailySummary(TODAY));
        verify(accountMapper, never()).findNegativeArrears(anyString());
        verify(opsAlertDispatcher, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    void invalidThreshold_fallsBackTo90() {
        when(accountMapper.findNegativeArrears("MERCHANT")).thenReturn(List.of(
                row("M1", -1_000L, TODAY.minusDays(89).atStartOfDay(java.time.ZoneOffset.UTC).toInstant())));
        // 89 天 < 回落值 90 ⇒ 不出摘要（若回落成 0/1 会误报）
        assertEquals("", service("not-a-number").dailySummary(TODAY));
    }

    @Test
    void alertFailure_doesNotBreakSummary() {
        when(accountMapper.findNegativeArrears("MERCHANT")).thenReturn(List.of(
                row("M1", -12_345L, TODAY.minusDays(200).atStartOfDay(java.time.ZoneOffset.UTC).toInstant())));
        org.mockito.Mockito.doThrow(new IllegalStateException("webhook down"))
                .when(opsAlertDispatcher).send(anyString(), anyString(), anyString());
        String summary = service("90").dailySummary(TODAY);
        assertTrue(summary.contains("123.45 元"), "告警通道故障不得吞掉执行记录摘要");
    }

    // ---------- SQL 判据：只读 + 负余额谓词 + 账龄起算方向 ----------

    private static String xml() throws Exception {
        try (InputStream in = MerchantWalletArrearsServiceTest.class
                .getResourceAsStream("/mapper/WalletAccountMapper.xml")) {
            assertNotNull(in, "找不到 WalletAccountMapper.xml（resources 未进测试类路径）");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String selectBlock(String id) throws Exception {
        String all = xml();
        int start = all.indexOf("id=\"" + id + "\"");
        assertTrue(start > 0, "XML 里找不到 select " + id);
        int end = all.indexOf("</select>", start);
        return all.substring(start, end);
    }

    @Test
    void arrearsSql_isReadOnlyAndPinsPredicates() throws Exception {
        String block = selectBlock("findNegativeArrears");
        assertTrue(block.contains("balance_cents < 0"), "必须以负余额为谓词");
        assertTrue(block.contains("a.domain = #{domain}"), "必须按 domain 隔离（线长/员工钱包不混入）");
        assertTrue(block.contains("balance_after >= 0"), "账龄起算必须取「最后一次非负」");
        assertFalse(block.toUpperCase().contains("UPDATE"), "只读探针不得出现 UPDATE");
        assertFalse(block.toUpperCase().contains("DELETE"), "只读探针不得出现 DELETE");
    }
}
