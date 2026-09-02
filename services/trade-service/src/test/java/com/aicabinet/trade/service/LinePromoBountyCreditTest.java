package com.aicabinet.trade.service;

import com.aicabinet.common.dto.UpsertLinePromoTaskRequest;
import com.aicabinet.trade.domain.LinePromoTask;
import com.aicabinet.trade.mapper.LinePromoTaskMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LinePromoBountyCreditTest {

    @Mock private LinePromoTaskMapper taskMapper;
    @Mock private LineManagerService lineManagerService;
    @Mock private PermissionService permissionService;
    @Mock private AdminAuditService auditService;
    @Mock private DistributedLockService distributedLockService;
    @Mock private LineWalletService lineWalletService;

    private LinePromoTaskService service;

    @BeforeEach
    void setUp() {
        service = new LinePromoTaskService(taskMapper, lineManagerService, permissionService,
                auditService, distributedLockService, lineWalletService);
        when(distributedLockService.tryLock(any(), anyLong(), anyLong())).thenReturn(true);
    }

    @Test
    void upsert_openToDone_creditsBountyOnce() {
        LinePromoTask existing = new LinePromoTask();
        existing.setTaskId(9L);
        existing.setManagerId(2L);
        existing.setTitle("地推A");
        existing.setTargetQty(10);
        existing.setDoneQty(5);
        existing.setBountyCents(500);
        existing.setStatus("OPEN");
        when(taskMapper.findByIdForUpdate(9L)).thenReturn(Optional.of(existing));
        when(taskMapper.updateById(any(LinePromoTask.class))).thenReturn(1);

        UpsertLinePromoTaskRequest req = new UpsertLinePromoTaskRequest(
                2L, "地推A", null, 10, 500, LocalDate.now(), "DONE", 10);

        service.upsert(100L, 9L, req);

        verify(lineWalletService).creditIfAbsent(
                eq(2L), eq(500L), eq("BOUNTY"), eq("LINE_PROMO"), eq("9"), any());
    }

    @Test
    void upsert_alreadyDone_doesNotCreditAgain() {
        LinePromoTask existing = new LinePromoTask();
        existing.setTaskId(9L);
        existing.setManagerId(2L);
        existing.setTitle("地推A");
        existing.setTargetQty(10);
        existing.setDoneQty(10);
        existing.setBountyCents(500);
        existing.setStatus("DONE");
        when(taskMapper.findByIdForUpdate(9L)).thenReturn(Optional.of(existing));
        when(taskMapper.updateById(any(LinePromoTask.class))).thenReturn(1);

        UpsertLinePromoTaskRequest req = new UpsertLinePromoTaskRequest(
                2L, "地推A", null, 10, 500, LocalDate.now(), "DONE", 10);

        service.upsert(100L, 9L, req);

        verify(lineWalletService, never()).creditIfAbsent(
                anyLong(), anyLong(), any(), any(), any(), any());
    }

    @Test
    void upsert_autoDoneByQty_creditsBounty() {
        when(taskMapper.insert(any(LinePromoTask.class))).thenAnswer(inv -> {
            LinePromoTask t = inv.getArgument(0);
            t.setTaskId(42L);
            return 1;
        });

        UpsertLinePromoTaskRequest req = new UpsertLinePromoTaskRequest(
                3L, "新任务", "R1", 5, 200, LocalDate.now(), "OPEN", 5);

        service.upsert(100L, null, req);

        ArgumentCaptor<String> remark = ArgumentCaptor.forClass(String.class);
        verify(lineWalletService).creditIfAbsent(
                eq(3L), eq(200L), eq("BOUNTY"), eq("LINE_PROMO"), eq("42"), remark.capture());
        assertTrue(remark.getValue().contains("新任务"));
    }

    @Test
    void upsert_zeroBounty_skipsCredit() {
        when(taskMapper.insert(any(LinePromoTask.class))).thenAnswer(inv -> {
            LinePromoTask t = inv.getArgument(0);
            t.setTaskId(7L);
            return 1;
        });

        UpsertLinePromoTaskRequest req = new UpsertLinePromoTaskRequest(
                3L, "无赏金", null, 1, 0, LocalDate.now(), "OPEN", 1);

        service.upsert(100L, null, req);

        verify(lineWalletService, never()).creditIfAbsent(
                anyLong(), anyLong(), any(), any(), any(), any());
        verify(lineWalletService, never()).credit(
                anyLong(), anyLong(), any(), any(), any(), any());
    }
}
