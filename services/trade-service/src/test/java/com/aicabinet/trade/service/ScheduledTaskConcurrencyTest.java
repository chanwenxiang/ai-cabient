package com.aicabinet.trade.service;

import com.aicabinet.trade.mapper.ScheduledTaskMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScheduledTaskConcurrencyTest {

    @Mock private ScheduledTaskMapper taskRepository;
    @Mock private ScheduledTaskRegistry registry;
    @Mock private DistributedLockService lockService;
    @Mock private AdminAuditService auditService;

    private ScheduledTaskService service;

    @BeforeEach
    void setUp() {
        service = new ScheduledTaskService(taskRepository, registry, lockService, auditService, false);
    }

    @Test
    void setEnabled_whenAdminLockBusy_rejectsWithConflict() {
        when(lockService.tryLock(
                ScheduledTaskService.scheduledTaskAdminLockKey("x"), 60L, 5L))
                .thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.setEnabled(1L, "x", true));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }

    @Test
    void setRemark_whenNotFound_unlocksAdminLock() {
        when(lockService.tryLock(
                ScheduledTaskService.scheduledTaskAdminLockKey("missing"), 60L, 5L))
                .thenReturn(true);
        when(taskRepository.findByIdForUpdate("missing")).thenReturn(java.util.Optional.empty());

        assertThrows(ResponseStatusException.class, () -> service.setRemark(1L, "missing", "note"));

        verify(lockService).unlock(ScheduledTaskService.scheduledTaskAdminLockKey("missing"));
    }
}
