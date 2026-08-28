package com.aicabinet.trade.service;

import com.aicabinet.common.dto.UpsertSiteContractRequest;
import com.aicabinet.trade.mapper.DeviceInfoMapper;
import com.aicabinet.trade.mapper.SiteContractMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SiteContractConcurrencyTest {

    @Mock private SiteContractMapper contractRepository;
    @Mock private DeviceInfoMapper deviceRepository;
    @Mock private PermissionService permissionService;
    @Mock private AdminAuditService auditService;
    @Mock private DistributedLockService distributedLockService;

    private SiteContractService service;

    @BeforeEach
    void setUp() {
        service = new SiteContractService(contractRepository, deviceRepository,
                permissionService, auditService, distributedLockService);
    }

    @Test
    void upsert_whenLockBusy_rejectsWithConflict() {
        when(distributedLockService.tryLock(
                SiteContractService.contractLockKey("CAB-001"), 60L, 5L))
                .thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.upsert(1L, "CAB-001",
                        new UpsertSiteContractRequest("科技园", "深圳", "张先生", "13800138000",
                                LocalDate.now(), LocalDate.now().plusDays(60), 50000, null)));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }

    @Test
    void upsert_whenDeviceNotFound_unlocksLock() {
        when(distributedLockService.tryLock(
                SiteContractService.contractLockKey("CAB-002"), 60L, 5L))
                .thenReturn(true);
        when(deviceRepository.findById("CAB-002")).thenReturn(Optional.empty());

        assertThrows(ResponseStatusException.class,
                () -> service.upsert(1L, "CAB-002",
                        new UpsertSiteContractRequest("科技园", "深圳", "张先生", "13800138000",
                                LocalDate.now(), LocalDate.now().plusDays(60), 50000, null)));

        verify(distributedLockService).unlock(SiteContractService.contractLockKey("CAB-002"));
    }
}
