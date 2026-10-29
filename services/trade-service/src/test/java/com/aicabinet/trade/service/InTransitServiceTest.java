package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.WarehouseInTransit;
import com.aicabinet.trade.domain.WarehouseOutboundLine;
import com.aicabinet.trade.mapper.WarehouseInTransitMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InTransitServiceTest {

    @Mock
    private WarehouseInTransitMapper transitRepository;
    @Mock
    private DistributedLockService distributedLockService;
    @Mock
    private DisplaySnapshotHelper displaySnapshotHelper;

    private InTransitService inTransitService;

    @BeforeEach
    void setUp() {
        inTransitService = new InTransitService(transitRepository, distributedLockService, displaySnapshotHelper, null);
        org.springframework.test.util.ReflectionTestUtils.setField(inTransitService, "self", inTransitService);
    }

    @Test
    void recordFromOutbound_persistsInTransitRows() {
        when(distributedLockService.tryLock(
                InTransitService.inTransitLockKey(88L, "CAB-001"), 60L, 5L))
                .thenReturn(true);
        WarehouseOutboundLine line = new WarehouseOutboundLine();
        line.setDeviceId("CAB-001");
        line.setSkuId("SKU-A");
        line.setBatchNo("B-001");
        line.setQuantity(5);

        inTransitService.recordFromOutbound(88L, List.of(line));

        ArgumentCaptor<WarehouseInTransit> captor = ArgumentCaptor.forClass(WarehouseInTransit.class);
        verify(transitRepository).save(captor.capture());
        WarehouseInTransit saved = captor.getValue();
        assertEquals(88L, saved.getOutboundId());
        assertEquals("CAB-001", saved.getDeviceId());
        assertEquals("SKU-A", saved.getSkuId());
        assertEquals("B-001", saved.getBatchNo());
        assertEquals(5, saved.getQuantity());
        assertEquals("IN_TRANSIT", saved.getStatus());
    }

    @Test
    void qtyBySkuForDevice_sumsOpenTransit() {
        WarehouseInTransit a = new WarehouseInTransit();
        a.setSkuId("SKU-A");
        a.setQuantity(3);
        WarehouseInTransit b = new WarehouseInTransit();
        b.setSkuId("SKU-A");
        b.setQuantity(2);
        when(transitRepository.findByDeviceIdAndStatus("CAB-001", "IN_TRANSIT"))
                .thenReturn(List.of(a, b));

        assertEquals(5, inTransitService.qtyBySkuForDevice("CAB-001").get("SKU-A"));
    }

    @Test
    void listInTransitPage_overdueOnly_passes24hCutoffToRepository() {
        when(transitRepository.searchPage(anyString(), anyString(), any(), anyInt(), anyInt()))
                .thenReturn(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>());

        inTransitService.listInTransitPage("CAB-001", true, 0, 20);

        ArgumentCaptor<java.time.Instant> cutoff = ArgumentCaptor.forClass(java.time.Instant.class);
        verify(transitRepository).searchPage(eq("CAB-001"), eq("IN_TRANSIT"), cutoff.capture(), eq(0), eq(20));
        // 口径锁死 24h（前端 TRANSIT_OVERDUE_HOURS / OpsWorkbenchQueryService 同值）；容差 2 分钟
        java.time.Instant expected = java.time.Instant.now().minus(InTransitService.TRANSIT_OVERDUE_HOURS,
                java.time.temporal.ChronoUnit.HOURS);
        assertTrue(Math.abs(cutoff.getValue().toEpochMilli() - expected.toEpochMilli()) < 120_000,
                "cutoff 应为 now-24h，实际 " + cutoff.getValue());
    }

    @Test
    void listInTransitPage_withoutOverdue_passesNullCutoff() {
        when(transitRepository.searchPage(anyString(), anyString(), any(), anyInt(), anyInt()))
                .thenReturn(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>());

        inTransitService.listInTransitPage("CAB-001", false, 0, 20);

        verify(transitRepository).searchPage(eq("CAB-001"), eq("IN_TRANSIT"), isNull(), eq(0), eq(20));
    }
}
