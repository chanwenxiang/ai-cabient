package com.aicabinet.trade.service;

import com.aicabinet.trade.mapper.OpsDeviceOrgMapper;
import com.aicabinet.trade.mapper.OpsOrgNodeMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrgConcurrencyTest {

    @Mock private OpsOrgNodeMapper nodeRepository;
    @Mock private OpsDeviceOrgMapper deviceOrgRepository;
    @Mock private PermissionService permissionService;
    @Mock private AdminAuditService auditService;
    @Mock private DistributedLockService distributedLockService;

    private OrgService service;

    @BeforeEach
    void setUp() {
        service = new OrgService(nodeRepository, deviceOrgRepository, permissionService,
                auditService, distributedLockService);
    }

    @Test
    void toggleNode_whenLockBusy_rejectsWithConflict() {
        when(distributedLockService.tryLock(eq(OrgService.orgNodeLockKey(2L)), eq(60L), eq(5L)))
                .thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.toggleNode(1L, 2L, false));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }

    @Test
    void assignDevices_whenNodeLockBusy_rejectsWithConflict() {
        when(distributedLockService.tryLock(eq(OrgService.orgNodeLockKey(2L)), eq(60L), eq(5L)))
                .thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.assignDevices(1L, 2L, List.of()));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }

    @Test
    void toggleNode_whenNodeNotFound_unlocksLock() {
        when(distributedLockService.tryLock(eq(OrgService.orgNodeLockKey(3L)), eq(60L), eq(5L)))
                .thenReturn(true);
        when(nodeRepository.findByIdForUpdate(3L)).thenReturn(java.util.Optional.empty());

        assertThrows(ResponseStatusException.class, () -> service.toggleNode(1L, 3L, true));

        verify(distributedLockService).unlock(OrgService.orgNodeLockKey(3L));
    }
}
