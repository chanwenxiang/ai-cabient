package com.aicabinet.trade.service;

import com.aicabinet.common.dto.LineManagerDto;
import com.aicabinet.trade.domain.LineManager;
import com.aicabinet.trade.domain.LineWalletAccount;
import com.aicabinet.trade.mapper.DeviceInfoMapper;
import com.aicabinet.trade.mapper.LineDeviceMapper;
import com.aicabinet.trade.mapper.LineManagerMapper;
import com.aicabinet.trade.mapper.LineWalletLedgerMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LineManagerToDtoReadOnlyTest {

    @Mock private LineManagerMapper managerMapper;
    @Mock private LineDeviceMapper deviceMapper;
    @Mock private LineWalletLedgerMapper ledgerMapper;
    @Mock private DeviceInfoMapper deviceInfoMapper;
    @Mock private LineWalletService lineWalletService;
    @Mock private PermissionService permissionService;
    @Mock private AdminAuditService auditService;
    @Mock private DistributedLockService distributedLockService;

    private LineManagerService service;

    @BeforeEach
    void setUp() {
        service = new LineManagerService(
                managerMapper,
                deviceMapper,
                ledgerMapper,
                deviceInfoMapper,
                lineWalletService,
                permissionService,
                auditService,
                distributedLockService,
                null);
    }

    @Test
    void toDtoUsesFindAccountWithoutEnsureInsert() {
        LineManager manager = new LineManager();
        manager.setManagerId(9L);
        manager.setManagerName("线长甲");
        manager.setPhone("13800000009");
        manager.setStatus(LineManagerService.STATUS_ACTIVE);
        manager.setCommissionRateBps(200);
        manager.setCommissionFixedCents(0);
        manager.setCreatedAt(Instant.parse("2026-09-01T00:00:00Z"));
        manager.setUpdatedAt(Instant.parse("2026-09-01T00:00:00Z"));

        when(lineWalletService.findAccount(9L)).thenReturn(null);
        when(deviceMapper.findActiveByManagerId(9L)).thenReturn(List.of());

        LineManagerDto dto = service.toDto(manager);

        assertEquals(0L, dto.balanceCents());
        assertEquals(0L, dto.frozenCents());
        verify(lineWalletService).findAccount(9L);
        verify(lineWalletService, never()).ensureAccount(anyLong());
    }

    @Test
    void toDtoMapsExistingWalletBalances() {
        LineManager manager = new LineManager();
        manager.setManagerId(9L);
        manager.setManagerName("线长甲");
        manager.setPhone("13800000009");
        manager.setStatus(LineManagerService.STATUS_ACTIVE);
        manager.setCommissionRateBps(200);
        manager.setCommissionFixedCents(0);
        manager.setCreatedAt(Instant.parse("2026-09-01T00:00:00Z"));
        manager.setUpdatedAt(Instant.parse("2026-09-01T00:00:00Z"));

        LineWalletAccount account = new LineWalletAccount();
        account.setManagerId(9L);
        account.setBalanceCents(1500L);
        account.setFrozenCents(200L);
        when(lineWalletService.findAccount(9L)).thenReturn(account);
        when(deviceMapper.findActiveByManagerId(9L)).thenReturn(List.of());

        LineManagerDto dto = service.toDto(manager);

        assertEquals(1500L, dto.balanceCents());
        assertEquals(200L, dto.frozenCents());
        verify(lineWalletService, never()).ensureAccount(anyLong());
    }
}
