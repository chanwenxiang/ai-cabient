package com.aicabinet.trade.service;

import com.aicabinet.common.dto.SiteContractDto;
import com.aicabinet.common.dto.UpsertSiteContractRequest;
import com.aicabinet.trade.domain.DeviceInfo;
import com.aicabinet.trade.domain.SiteContract;
import com.aicabinet.trade.mapper.DeviceInfoMapper;
import com.aicabinet.trade.mapper.SiteContractMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SiteContractServiceTest {

    private static final long OPERATOR_ID = 1900000001L;

    @Mock private SiteContractMapper contractRepository;
    @Mock private DeviceInfoMapper deviceRepository;
    @Mock private PermissionService permissionService;
    @Mock private AdminAuditService auditService;
    @Mock private DistributedLockService distributedLockService;

    private SiteContractService service;

    @BeforeEach
    void setUp() {
        org.mockito.Mockito.lenient().when(distributedLockService.tryLock(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong())).thenReturn(true);
        service = new SiteContractService(contractRepository, deviceRepository,
                permissionService, auditService, distributedLockService);
    }

    @Test
    void statusFor_shouldMarkExpiringAndExpired() {
        LocalDate today = LocalDate.now();
        assertEquals("ACTIVE", SiteContractService.statusFor(today.plusDays(60)));
        assertEquals("EXPIRING", SiteContractService.statusFor(today.plusDays(20)));
        assertEquals("EXPIRED", SiteContractService.statusFor(today.minusDays(1)));
        assertEquals("ACTIVE", SiteContractService.statusFor(null));
    }

    @Test
    void upsert_shouldCreateWithComputedStatus() {
        DeviceInfo device = new DeviceInfo();
        device.setDeviceId("CAB-001");
        device.setDeviceName("测试柜");
        when(deviceRepository.findById("CAB-001")).thenReturn(Optional.of(device));
        when(contractRepository.findByDeviceIdForUpdate("CAB-001")).thenReturn(Optional.empty());

        SiteContractDto dto = service.upsert(OPERATOR_ID, "CAB-001",
                new UpsertSiteContractRequest("科技园 A 座", "深圳", "张先生", "13800138000",
                        LocalDate.now(), LocalDate.now().plusDays(15), 50000, null));

        assertEquals("测试柜", dto.deviceName());
        assertEquals("EXPIRING", dto.status());
        verify(contractRepository).insert(any(SiteContract.class));
        verify(auditService).record(eq(OPERATOR_ID), any(), any(), any(), any());
    }
}
