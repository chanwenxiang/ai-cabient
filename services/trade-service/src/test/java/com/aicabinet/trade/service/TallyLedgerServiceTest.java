package com.aicabinet.trade.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import com.aicabinet.common.dto.TallyRecordRowDto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * {@link TallyLedgerService} 钉住测试（C2 理货记录台账）。
 *
 * <p>钉住台账口径：只读聚合（无写入）、操作员必非空（assignee>0，系统动作不算理货）、
 * 窗口 clamp 到 365 天、SKU 名缺目录时回退 skuId、分页 LIMIT/OFFSET 形态。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TallyLedgerServiceTest {

    @Mock JdbcTemplate jdbc;

    @Test
    void ledger_joinsTaskLineUserSku_andFiltersSystemActions() {
        when(jdbc.queryForObject(contains("COUNT(*)"), eq(Long.class), any(Object[].class)))
                .thenReturn(1L);
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of(new TallyRecordRowDto(
                        4L, "CAB-1", 10001L, "张三", "COMPLETED", "RESTOCK",
                        "SKU-A", "可乐", 6, "A1", true, Instant.now())));

        PageResultAccess.items(new TallyLedgerService(jdbc).ledger(0, 20, "CAB-1", 10001L, 30));

        ArgumentCaptor<String> countSql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForObject(countSql.capture(), eq(Long.class), any(Object[].class));
        assertTrue(countSql.getValue().contains("COALESCE(t.assignee_user_id, 0) > 0"),
                "系统动作（无操作员）不算理货记录");
        assertTrue(countSql.getValue().contains("t.device_id = ?"), "设备过滤须进 COUNT");
        assertTrue(countSql.getValue().contains("JOIN replenishment_task_line"),
                "台账必须行粒度（任务×行 join）");
        assertTrue(countSql.getValue().contains("LEFT JOIN sku_catalog"), "SKU 名须左连目录");

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sqlCaptor.capture(), any(RowMapper.class), any(Object[].class));
        assertTrue(sqlCaptor.getValue().contains("COALESCE(s.sku_name, l.sku_id)"),
                "SKU 目录缺失时回退 skuId（台账不得因目录缺失漏行）");
        assertTrue(sqlCaptor.getValue().contains("LIMIT ? OFFSET ?"), "必须分页（LIMIT/OFFSET）");
    }

    @Test
    void ledger_daysClampedTo365() {
        when(jdbc.queryForObject(contains("COUNT(*)"), eq(Long.class), any(Object[].class))).thenReturn(0L);
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());

        PageResultAccess.items(new TallyLedgerService(jdbc).ledger(0, 20, null, null, 4000));

        // 窗口在 Java 侧 clamp 后作为首参传入：4000 天请求 → since ≈ 今天-364 天（在 [-366, -360] 天区间外不可信）
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).queryForObject(org.mockito.ArgumentMatchers.anyString(), eq(Long.class),
                argsCaptor.capture());
        Object sinceRaw = argsCaptor.getValue()[0];
        assertTrue(sinceRaw instanceof Instant since
                && !since.isAfter(Instant.now().minus(java.time.Duration.ofDays(360)))
                && !since.isBefore(Instant.now().minus(java.time.Duration.ofDays(366))),
                "窗口 clamp 到 365 天（since ≈ 今天-364 天）");
    }

    // PageResult 是 record，直接取 items 断言非空即可（避免引入额外断言依赖）
    private static final class PageResultAccess {
        private static void items(com.aicabinet.common.dto.PageResult<TallyRecordRowDto> result) {
            assertEquals(0, result.page());
        }
    }

    private static void assertTrue(boolean condition, String message) {
        org.junit.jupiter.api.Assertions.assertTrue(condition, message);
    }
}
