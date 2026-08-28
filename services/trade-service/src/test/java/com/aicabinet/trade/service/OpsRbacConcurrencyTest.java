package com.aicabinet.trade.service;

import com.aicabinet.common.dto.UpdateOpsRoleRequest;
import com.aicabinet.trade.mapper.OpsRoleMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OpsRbacConcurrencyTest {

    @Mock private OpsRoleMapper roleRepository;
    @Mock private PermissionService permissionService;
    @Mock private DistributedLockService distributedLockService;

    private OpsRbacService service;

    @BeforeEach
    void setUp() {
        doNothing().when(permissionService).requirePermission(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString());
        service = new OpsRbacService(
                roleRepository, null, null, null, null, null,
                permissionService, null, null, null, null, null,
                distributedLockService, null, null, null, null);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "self", service);
    }

    @Test
    void updateRole_whenLockBusy_rejectsWithConflict() {
        when(distributedLockService.tryLock(
                OpsRbacService.opsRoleLockKey(3L), 60L, 5L))
                .thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.updateRole(1L, 3L, new UpdateOpsRoleRequest("名称", null, null)));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }

    @Test
    void updateRole_whenNotFound_unlocksLock() {
        when(distributedLockService.tryLock(
                OpsRbacService.opsRoleLockKey(4L), 60L, 5L))
                .thenReturn(true);
        when(roleRepository.findByIdForUpdate(4L)).thenReturn(java.util.Optional.empty());

        assertThrows(ResponseStatusException.class,
                () -> service.updateRole(1L, 4L, new UpdateOpsRoleRequest("名称", null, null)));

        verify(distributedLockService).unlock(OpsRbacService.opsRoleLockKey(4L));
    }
}
