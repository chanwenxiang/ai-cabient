package com.aicabinet.trade.service;

import com.aicabinet.common.dto.LinePromoTaskDto;
import com.aicabinet.common.dto.PageResult;
import com.aicabinet.trade.domain.LinePromoTask;
import com.aicabinet.trade.mapper.LinePromoTaskMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * S10（取数窗口诚实性，MA-002 同款）：地推任务列表端点原为裸 List + mapper `LIMIT 200`
 * 静默截断（无 total 回显）。2026-11-13 修复为分页 PageResult（items/page/size/total）。
 * 本类钉住分页契约：total 走独立 count 查询、页大小钳制 [1,200]、负页归零。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LinePromoTaskServiceListTest {

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
    }

    private static LinePromoTask task(long id, String title) {
        LinePromoTask t = new LinePromoTask();
        t.setTaskId(id);
        t.setManagerId(1L);
        t.setTitle(title);
        t.setTargetQty(10);
        t.setDoneQty(0);
        t.setBountyCents(100);
        t.setStatus("OPEN");
        t.setDueDate(LocalDate.now());
        t.setCreatedAt(Instant.now());
        t.setUpdatedAt(Instant.now());
        return t;
    }

    @Test
    void list_returnsPagedResultWithTotalAndMappedItems() {
        when(taskMapper.countByManager(1L, "OPEN")).thenReturn(42L);
        when(taskMapper.findByManager(1L, "OPEN", 0, 20))
                .thenReturn(List.of(task(1L, "任务A"), task(2L, "任务B")));

        PageResult<LinePromoTaskDto> res = service.list(100L, 1L, "OPEN", 0, 20);

        assertEquals(42L, res.total());
        assertEquals(0, res.page());
        assertEquals(20, res.size());
        assertEquals(2, res.items().size());
        assertEquals("任务A", res.items().get(0).title());
        assertEquals(2L, res.items().get(1).taskId());
        verify(taskMapper).countByManager(1L, "OPEN");
    }

    @Test
    void list_clampsOversizedSizeAndComputesOffset() {
        when(taskMapper.countByManager(null, null)).thenReturn(0L);
        when(taskMapper.findByManager(null, null, 1000, 200)).thenReturn(List.of());

        PageResult<LinePromoTaskDto> res = service.list(100L, null, null, 5, 9999);

        assertEquals(200, res.size());
        assertEquals(5, res.page());
        verify(taskMapper).findByManager(null, null, 1000, 200);
    }

    @Test
    void list_clampsZeroSizeAndNegativePage() {
        when(taskMapper.countByManager(1L, null)).thenReturn(0L);
        when(taskMapper.findByManager(1L, null, 0, 1)).thenReturn(List.of());

        PageResult<LinePromoTaskDto> res = service.list(100L, 1L, null, -3, 0);

        assertEquals(1, res.size());
        assertEquals(0, res.page());
        verify(taskMapper).findByManager(1L, null, 0, 1);
    }
}
