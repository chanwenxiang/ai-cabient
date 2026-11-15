package com.aicabinet.trade.service;

import com.aicabinet.common.dto.FundDailyBillDto;
import com.aicabinet.common.dto.FundLedgerEntryDto;
import com.aicabinet.trade.domain.OrderRevenueSplit;
import com.aicabinet.trade.mapper.CabinetOrderLineMapper;
import com.aicabinet.trade.mapper.CabinetOrderMapper;
import com.aicabinet.trade.mapper.DeviceInfoMapper;
import com.aicabinet.trade.mapper.FinanceMarginDailyLockMapper;
import com.aicabinet.trade.mapper.InventoryWriteOffMapper;
import com.aicabinet.trade.mapper.MerchantMapper;
import com.aicabinet.trade.mapper.OrderRevenueSplitMapper;
import com.aicabinet.trade.mapper.PaymentReconciliationMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FundBillServiceTest {

    @Test
    void isMerchantCreditedStatus_includesLedgerAndSettled() {
        assertTrue(FundBillService.isMerchantCreditedStatus("SUCCESS"));
        assertTrue(FundBillService.isMerchantCreditedStatus("SETTLED"));
        assertTrue(FundBillService.isMerchantCreditedStatus("LEDGER_ONLY"));
        assertFalse(FundBillService.isMerchantCreditedStatus("ACCRUED"));
        assertFalse(FundBillService.isMerchantCreditedStatus("WECHAT_SUBMITTED"));
        assertFalse(FundBillService.isMerchantCreditedStatus("VOIDED"));
    }

    @Test
    void dailyBillAggregation_skipsVoidedAndCountsLedgerOnlyAsCredited() {
        OrderRevenueSplit ledger = split("S1", "LEDGER_ONLY", 1000, 900);
        OrderRevenueSplit accrued = split("S2", "ACCRUED", 800, 720);
        OrderRevenueSplit voided = split("S3", "VOIDED", 500, 450);

        long credited = 0;
        long pending = 0;
        long gross = 0;
        for (OrderRevenueSplit s : new OrderRevenueSplit[] {ledger, accrued, voided}) {
            String status = s.getStatus().toUpperCase();
            if ("VOIDED".equals(status) || "REVERSED".equals(status)) {
                continue;
            }
            gross += s.getGrossCents();
            if (FundBillService.isMerchantCreditedStatus(status)) {
                credited += s.getMerchantCents();
            } else {
                pending += s.getMerchantCents();
            }
        }

        assertTrue(gross == 1800);
        assertTrue(credited == 900);
        assertTrue(pending == 720);
    }

    @Test
    void filterDailyBills_matchesMerchantAndDate() {
        var rows = List.of(
                new FundDailyBillDto("2026-09-01", "M001", "默认直营商户", 100, 10, 1, "ESTIMATED", 80, 0, 1, true),
                new FundDailyBillDto("2026-08-31", "M002", "Demo Shop", 200, 20, 2, "ESTIMATED", 160, 0, 2, true));
        assertEquals(1, FundBillService.filterDailyBills(rows, "默认").size());
        assertEquals(1, FundBillService.filterDailyBills(rows, "M002").size());
        assertEquals(1, FundBillService.filterDailyBills(rows, "2026-09").size());
        assertTrue(FundBillService.filterDailyBills(rows, "zzz_no_match").isEmpty());
    }

    @Test
    void filterLedgerRows_matchesOrderDeviceAndMerchant() {
        var row = new FundLedgerEntryDto(
                "S1:ORDER_PAYMENT", "ORDER_PAYMENT", "IN", 441,
                "M001", "默认直营商户", "CAB-001", "1788252279967241317",
                null, "WECHAT", Instant.parse("2026-09-01T08:00:00Z"));
        var deviceNames = Map.of("CAB-001", "门店一号柜");
        assertEquals(1, FundBillService.filterLedgerRows(List.of(row), "178825227996", deviceNames).size());
        assertEquals(1, FundBillService.filterLedgerRows(List.of(row), "门店", deviceNames).size());
        assertEquals(1, FundBillService.filterLedgerRows(List.of(row), "默认直营", deviceNames).size());
        assertTrue(FundBillService.filterLedgerRows(List.of(row), "not-a-real-keyword", deviceNames).isEmpty());
    }

    /**
     * R23X-04 / MA-266：日毛利锁写的是<b>全局日键</b>，必须同时满足
     * ① 写权限点 ops:fund:manage（不是读权限 ops:finance:view）；
     * ② 操作员为全局范围。受限运营按一下就能把全平台毛利率覆盖成自己那几台柜。
     */
    @Test
    void solidifyMargin_requiresWritePermissionAndGlobalScope() {
        PermissionService permissionService = mock(PermissionService.class);
        MerchantScopeService scope = mock(MerchantScopeService.class);
        FinanceMarginDailyLockMapper marginLockMapper = mock(FinanceMarginDailyLockMapper.class);
        FundBillService service = new FundBillService(
                mock(OrderRevenueSplitMapper.class), mock(DeviceInfoMapper.class), mock(MerchantMapper.class),
                marginLockMapper, mock(PaymentReconciliationMapper.class), mock(CabinetOrderMapper.class),
                mock(CabinetOrderLineMapper.class), mock(InventoryWriteOffMapper.class),
                scope, permissionService, mock(DistributedLockService.class), mock(SystemConfigService.class),
                null);

        when(scope.isGlobalScope(1L)).thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.solidifyMargin(1L, LocalDate.of(2026, 11, 14)));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        // 写权限必须是 ops:fund:manage；仍用读权限点即回归
        verify(permissionService).requirePermission(1L, "ops:fund:manage");
        verify(permissionService, never()).requireAnyPermission(anyLong(), anyString(), anyString());
        verify(marginLockMapper, never()).save(any());
    }

    /** 定时任务走 operatorId=null，不受「全局范围」闸影响（否则夜间固化全部失效）。 */
    @Test
    void solidifyMargin_nullOperatorSkipsScopeGate() {
        PermissionService permissionService = mock(PermissionService.class);
        MerchantScopeService scope = mock(MerchantScopeService.class);
        FinanceMarginDailyLockMapper marginLockMapper = mock(FinanceMarginDailyLockMapper.class);
        DistributedLockService lockService = mock(DistributedLockService.class);
        when(lockService.tryLock(anyString(), anyLong(), anyLong())).thenReturn(true);
        FundBillService service = new FundBillService(
                mock(OrderRevenueSplitMapper.class), mock(DeviceInfoMapper.class), mock(MerchantMapper.class),
                marginLockMapper, mock(PaymentReconciliationMapper.class), mock(CabinetOrderMapper.class),
                mock(CabinetOrderLineMapper.class), mock(InventoryWriteOffMapper.class), scope, permissionService,
                lockService, mock(SystemConfigService.class), null);

        service.solidifyMargin(null, LocalDate.of(2026, 11, 14));

        verify(permissionService, never()).requirePermission(any(), anyString());
        verify(scope, never()).isGlobalScope(any());
        verify(marginLockMapper).save(any());
    }

    private static OrderRevenueSplit split(String id, String status, long gross, long merchant) {
        OrderRevenueSplit s = new OrderRevenueSplit();
        s.setSplitId(id);
        s.setStatus(status);
        s.setGrossCents(gross);
        s.setMerchantCents(merchant);
        s.setCreatedAt(Instant.parse("2024-06-01T10:00:00Z"));
        return s;
    }
}
