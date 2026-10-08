package com.aicabinet.trade.service;

import com.aicabinet.common.dto.SupplierReconciliationDto;
import com.aicabinet.trade.domain.Supplier;
import com.aicabinet.trade.domain.SupplierPayable;
import com.aicabinet.trade.mapper.SupplierMapper;
import com.aicabinet.trade.mapper.SupplierPayableEntryMapper;
import com.aicabinet.trade.mapper.SupplierPayableMapper;
import com.aicabinet.trade.mapper.SupplierPaymentMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * V326 供应商月度对账单测试：公式（CB-016 行业口径）+ 组装 + 边界。
 * SQL 聚合条件的实库验证在迁移部署后做（SELECT COUNT/CHECKSUM 核对）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SupplierReconciliationServiceTest {

    @Mock private PermissionService permissionService;
    @Mock private SupplierPayableEntryMapper entryMapper;
    @Mock private SupplierPaymentMapper paymentMapper;
    @Mock private SupplierPayableMapper payableMapper;
    @Mock private SupplierMapper supplierMapper;

    private SupplierReconciliationService service;

    @BeforeEach
    void setUp() {
        service = new SupplierReconciliationService(permissionService, entryMapper,
                paymentMapper, payableMapper, supplierMapper);
    }

    // --- 行业公式（纯函数）---

    @Test
    void closingFormula_shouldMatchIndustryEquation() {
        // 勤策公式：期末 = 期初 + 本期应付 − 本期付款（退货并入应付负向）
        assertEquals(12_000L, SupplierReconciliationService.closingOf(20_000, 10_000, 3_000, 15_000));
        assertEquals(0L, SupplierReconciliationService.closingOf(0, 0, 0, 0));
        // 付款大于余额（理论上 pay() 有校验不会发生，公式本身不崩）
        assertEquals(-500L, SupplierReconciliationService.closingOf(0, 1000, 0, 1500));
    }

    // --- 参数解析 ---

    @Test
    void parseMonth_shouldAcceptValidAndRejectInvalid() {
        assertEquals(YearMonth.of(2026, 10), SupplierReconciliationService.parseMonth("2026-10"));
        assertEquals(YearMonth.of(2026, 1), SupplierReconciliationService.parseMonth(" 2026-01 "));
        assertThrows(ResponseStatusException.class, () -> SupplierReconciliationService.parseMonth(null));
        assertThrows(ResponseStatusException.class, () -> SupplierReconciliationService.parseMonth(""));
        assertThrows(ResponseStatusException.class, () -> SupplierReconciliationService.parseMonth("2026-13"));
        assertThrows(ResponseStatusException.class, () -> SupplierReconciliationService.parseMonth("2026/10"));
    }

    // --- asLong 数值收口（PG BIGINT 驱动给 Long；SUM 可能给 BigDecimal）---

    @Test
    void assembly_shouldHandleVariousNumericTypes() {
        assertEquals(100L, invokeAsLong(100L));
        assertEquals(100L, invokeAsLong(new BigDecimal("100")));
        assertEquals(100L, invokeAsLong("100"));
        assertEquals(0L, invokeAsLong(null));
    }

    private long invokeAsLong(Object v) {
        // asLong 是 private static；用一个小 Map 走 monthlyReconciliation 之外的反射不值得，
        // 这里通过公开行为覆盖：直接构造 selectMaps 返回值走一次完整调用。
        // asLong 的正确性等价于本测试的数值类型断言（反射调用仅为覆盖边界）。
        try {
            var m = SupplierReconciliationService.class.getDeclaredMethod("asLong", Object.class);
            m.setAccessible(true);
            return (Long) m.invoke(null, v);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // --- 完整调用（mock SQL 聚合返回值，验证装配与交叉验证位）---

    @Nested
    class MonthlyReconciliation {

        @BeforeEach
        void stubCommon() {
            lenient().when(supplierMapper.selectById("SUP-001"))
                    .thenReturn(supplier("SUP-001", "供应商甲"));
        }

        @Test
        void shouldAssembleFiveColumnsAndConsistencyFlag() {
            // selectMaps 调用顺序：entry(opening) → entry(RECEIVE) → entry(RETURN)
    // → payment(month) → entry(all) → payment(all)。按 mock+select 表达式区分。
            when(entryMapper.selectMaps(any())).thenAnswer(inv -> {
                var w = (com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<?>) inv.getArgument(0);
                // 🔴 MP 3.5.5 参数惰性 format：.eq() 只存模板，getSqlSegment() 被调用时才把值
                // put 进 paramNameValuePairs（实测 before size=0 / after size=4）。
                // 必须先触发 getSqlSegment()，containsValue 才有东西可查。
                w.getSqlSegment();
                // MP 参数化后 SQL 片段里只有占位符 ⇒ 用 paramNameValuePairs 判类型，
                // 用 sqlSelect 明文判口径（net_cents=期初/全量，sum_cents=单类型月内）
                boolean hasReceive = w.getParamNameValuePairs().containsValue("RECEIVE");
                boolean hasReturn = w.getParamNameValuePairs().containsValue("RETURN");
                String select = String.valueOf(w.getSqlSelect());
                if (select.contains("net_cents")) {
                    return List.of(Map.of("net_cents", 20_000L));   // opening / ledger 全口径
                }
                if (hasReceive) {
                    return List.of(Map.of("sum_cents", 10_000L));
                }
                if (hasReturn) {
                    return List.of(Map.of("sum_cents", 3_000L));
                }
                return List.of(Map.of("sum_cents", 0L));
            });
            when(paymentMapper.selectMaps(any())).thenReturn(List.of(Map.of("sum_cents", 15_000L)));
            when(payableMapper.selectList(any())).thenReturn(List.of(payable(13_000L, 0L)));

            SupplierReconciliationDto dto = service.monthlyReconciliation(1L, "SUP-001", "2026-10");

            assertEquals("SUP-001", dto.supplierId());
            assertEquals("供应商甲", dto.supplierName());
            assertEquals("2026-10", dto.month());
            assertEquals(20_000L, dto.openingCents());
            assertEquals(10_000L, dto.receivedCents());
            assertEquals(3_000L, dto.returnedCents());
            assertEquals(15_000L, dto.paidCents());
            // 期末 = 20000 + 10000 − 3000 − 15000 = 12000
            assertEquals(12_000L, dto.closingCents());
            // 流水推算余额 = 20000 − 15000 = 5000；主表余额 13000 ⇒ 不一致（本例故意构造）
            assertEquals(5_000L, dto.ledgerBalanceCents());
            assertEquals(13_000L, dto.mainBalanceCents());
            assertFalse(dto.ledgerConsistent(), "构造的数字不一致时必须亮红，对账单不可信");
        }

        @Test
        void shouldReportConsistentWhenLedgerMatchesMainTable() {
            when(entryMapper.selectMaps(any())).thenAnswer(inv -> {
                var w = (com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<?>) inv.getArgument(0);
                String select = String.valueOf(w.getSqlSelect());
                if (select.contains("net_cents")) {
                    return List.of(Map.of("net_cents", 20_000L));
                }
                return List.of(Map.of("sum_cents", 0L));
            });
            // 全口径：entries 净额 20000，付款全量 8000 ⇒ 流水余额 12000；月内付款同为 8000（单月场景）
            when(paymentMapper.selectMaps(any())).thenReturn(List.of(Map.of("sum_cents", 8_000L)));
            when(payableMapper.selectList(any())).thenReturn(List.of(payable(20_000L, 8_000L)));

            SupplierReconciliationDto dto = service.monthlyReconciliation(1L, "SUP-001", "2026-10");

            assertEquals(12_000L, dto.ledgerBalanceCents());
            assertEquals(12_000L, dto.mainBalanceCents());
            assertTrue(dto.ledgerConsistent(), "流水与主表一致时对账单可信");
        }

        @Test
        void shouldRejectWithoutPermission() {
            org.mockito.Mockito.doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "no perm"))
                    .when(permissionService).requirePermission(1L, "ops:procurement:list");
            assertThrows(ResponseStatusException.class,
                    () -> service.monthlyReconciliation(1L, "SUP-001", "2026-10"));
        }
    }

    private Supplier supplier(String id, String name) {
        Supplier s = new Supplier();
        s.setSupplierId(id);
        s.setSupplierName(name);
        return s;
    }

    private SupplierPayable payable(long amountCents, long paidCents) {
        SupplierPayable p = new SupplierPayable();
        p.setPayableId(9L);
        p.setSupplierId("SUP-001");
        p.setAmountCents(amountCents);
        p.setPaidAmountCents(paidCents);
        return p;
    }
}
