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

    private InTransitService inTransitService;

    @BeforeEach
    void setUp() {
        inTransitService = new InTransitService(transitRepository);
    }

    @Test
    void recordFromOutbound_persistsInTransitRows() {
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
}
