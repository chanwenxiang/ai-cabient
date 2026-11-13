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
 * <p>判据：① 只读（SQL 不得出现 UPDATE/DELETE）；② 账龄不可判（无流水）时**不报也不锁**，
 * 不得按 0 天误报；③ 告警与锁柜两个阈值独立（0=各自关闭）；④ 锁柜判据按商户单查，不扫全量。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MerchantWalletArrearsServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 13);
    private static final String M1 = "M1";

    @Mock private WalletAccountMapper accountMapper;
    @Mock private SystemConfigService configService;
    @Mock private OpsAlertDispatcher opsAlertDispatcher;

    private MerchantWalletArrearsService service(String alertDays, String lockDays) {
        when(configService.getValue(eq(MerchantWalletArrearsService.CONFIG_KEY), anyString()))
                .thenReturn(alertDays);
        when(configService.getValue(eq(MerchantWalletArrearsService.LOCK_CONFIG_KEY), anyString()))
                .thenReturn(lockDays);
        return new MerchantWalletArrearsService(accountMapper, configService, opsAlertDispatcher);
    }

    private static Map<String, Object> row(String merchantId, long balanceCents, long ageDays) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("c0", merchantId);
        m.put("c1", balanceCents);
        m.put("c2", ageDays < 0 ? null
                : Timestamp.from(TODAY.minusDays(ageDays).atStartOfDay(java.time.ZoneOffset.UTC).toInstant()));
        return m;
    }

    private void rows(Map<String, Object>... rows) {
        when(accountMapper.findNegativeArrears(eq(MerchantWalletArrearsService.DOMAIN_MERCHANT), any()))
                .thenReturn(List.of(rows));
    }

    @Test
    void noNegativeAccount_returnsEmptyAndNoAlert() {
        rows();
        assertEquals("", service("90", "90").dailySummary(TODAY));
        verify(opsAlertDispatcher, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    void ageBelowThreshold_silent() {
        rows(row(M1, -12_000L, 89));
        assertEquals("", service("90", "90").dailySummary(TODAY));
        verify(opsAlertDispatcher, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    void ageAtThreshold_alertsWithCountAndAmount() {
        rows(row(M1, -12_345L, 90));
        String summary = service("90", "90").dailySummary(TODAY);
        assertTrue(summary.contains("1 商户"), summary);
        assertTrue(summary.contains("123.45 元"), summary);
        assertTrue(summary.contains("最长账龄 90 天"), summary);
        verify(opsAlertDispatcher).send(eq(MerchantWalletArrearsService.ALERT_TYPE), anyString(),
                org.mockito.ArgumentMatchers.contains("会锁柜"));
    }

    @Test
    void mixedRows_onlyEligibleCounted_longestAgeWins() {
        rows(row(M1, -1_000L, 10), row("M2", -2_000L, 120), row("M3", -4_000L, 95));
        String summary = service("90", "90").dailySummary(TODAY);
        assertTrue(summary.contains("2 商户"), summary);
        assertTrue(summary.contains("60.00 元"), summary);
        assertTrue(summary.contains("最长账龄 120 天"), summary);
        // 最长账龄商户只在告警正文里点名（执行记录摘要不点商户名）
        verify(opsAlertDispatcher).send(eq(MerchantWalletArrearsService.ALERT_TYPE), anyString(),
                org.mockito.ArgumentMatchers.contains("M2"));
    }

    @Test
    void unjudgeableAge_isSkippedNotTreatedAsZero() {
        rows(row(M1, -999_999L, -1));
        assertEquals("", service("90", "90").dailySummary(TODAY));
        assertFalse(service("90", "90").inArrears(M1), "无流水 ⇒ 账龄不可判 ⇒ 不得锁柜");
        verify(opsAlertDispatcher, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    void zeroAlertThreshold_disablesProbe() {
        assertEquals("", service("0", "90").dailySummary(TODAY));
        verify(accountMapper, never()).findNegativeArrears(anyString(), any());
        verify(opsAlertDispatcher, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    void invalidAlertThreshold_fallsBackTo90() {
        rows(row(M1, -1_000L, 89));
        assertEquals("", service("not-a-number", "0").dailySummary(TODAY));
    }

    @Test
    void alertFailure_doesNotBreakSummary() {
        rows(row(M1, -12_345L, 200));
        org.mockito.Mockito.doThrow(new IllegalStateException("webhook down"))
                .when(opsAlertDispatcher).send(anyString(), anyString(), anyString());
        String summary = service("90", "90").dailySummary(TODAY);
        assertTrue(summary.contains("123.45 元"), "告警通道故障不得吞掉执行记录摘要");
    }

    // ---------- 锁柜判据（CB-086 第二段） ----------

    @Test
    void inArrears_trueAtLockThreshold_scopedToMerchant() {
        rows(row(M1, -5_000L, 90));
        assertTrue(service("90", "90").inArrears(M1));
        verify(accountMapper).findNegativeArrears(MerchantWalletArrearsService.DOMAIN_MERCHANT, M1);
    }

    @Test
    void inArrears_falseBelowLockThreshold_evenIfAlertFires() {
        rows(row(M1, -5_000L, 90));
        // 告警 90 / 锁柜 180：90 天只告警，不锁
        MerchantWalletArrearsService svc = service("90", "180");
        assertTrue(svc.dailySummary(TODAY).contains("1 商户"));
        assertFalse(svc.inArrears(M1));
    }

    @Test
    void inArrears_falseWhenLockDisabled() {
        rows(row(M1, -5_000L, 999));
        assertFalse(service("90", "0").inArrears(M1));
        verify(accountMapper, never()).findNegativeArrears(anyString(), eq(M1));
    }

    @Test
    void inArrears_falseWhenBalanceNonNegative() {
        rows(row(M1, 100L, 999));
        assertFalse(service("90", "90").inArrears(M1));
    }

    // ---------- SQL 判据：只读 + 负余额谓词 + 账龄起算方向 + 可选按商户收窄 ----------

    private static String selectBlock() throws Exception {
        try (InputStream in = MerchantWalletArrearsServiceTest.class
                .getResourceAsStream("/mapper/WalletAccountMapper.xml")) {
            assertNotNull(in, "找不到 WalletAccountMapper.xml（resources 未进测试类路径）");
            String all = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            int start = all.indexOf("id=\"findNegativeArrears\"");
            assertTrue(start > 0, "XML 里找不到 select findNegativeArrears");
            return all.substring(start, all.indexOf("</select>", start));
        }
    }

    @Test
    void arrearsSql_isReadOnlyAndPinsPredicates() throws Exception {
        String block = selectBlock();
        assertTrue(block.contains("balance_cents < 0"), "必须以负余额为谓词");
        assertTrue(block.contains("a.domain = #{domain}"), "必须按 domain 隔离（线长/员工钱包不混入）");
        assertTrue(block.contains("balance_after >= 0"), "账龄起算必须取「最后一次非负」");
        assertTrue(block.contains("a.owner_id = #{ownerId}"), "锁柜判据要能按商户单查（不扫全量）");
        assertFalse(block.toUpperCase().contains("UPDATE"), "只读探针不得出现 UPDATE");
        assertFalse(block.toUpperCase().contains("DELETE"), "只读探针不得出现 DELETE");
    }
}
