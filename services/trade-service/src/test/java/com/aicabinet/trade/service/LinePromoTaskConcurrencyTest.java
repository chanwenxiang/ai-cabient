package com.aicabinet.trade.service;

import com.aicabinet.common.dto.UpsertLinePromoTaskRequest;
import com.aicabinet.trade.mapper.LinePromoTaskMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LinePromoTaskConcurrencyTest {

    @Mock private LinePromoTaskMapper taskMapper;
    @Mock private LineManagerService lineManagerService;
    @Mock private PermissionService permissionService;
    @Mock private AdminAuditService auditService;
    @Mock private DistributedLockService distributedLockService;

    private LinePromoTaskService service;

    @BeforeEach
    void setUp() {
        service = new LinePromoTaskService(taskMapper, lineManagerService, permissionService,
                auditService, distributedLockService);
    }

    @Test
    void upsert_whenTaskLockBusy_rejectsWithConflict() {
        when(distributedLockService.tryLock(
                LinePromoTaskService.linePromoTaskLockKey(9L), 60L, 5L))
                .thenReturn(false);

        UpsertLinePromoTaskRequest req = new UpsertLinePromoTaskRequest(
                1L, "任务A", null, 10, 100, LocalDate.now(), null, null);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.upsert(100L, 9L, req));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }

    @Test
    void upsert_whenManagerLockBusyOnCreate_rejectsWithConflict() {
        when(distributedLockService.tryLock(
                LinePromoTaskService.linePromoManagerLockKey(2L), 60L, 5L))
                .thenReturn(false);

        UpsertLinePromoTaskRequest req = new UpsertLinePromoTaskRequest(
                2L, "新任务", null, 5, 50, LocalDate.now(), null, null);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.upsert(100L, null, req));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }
}
