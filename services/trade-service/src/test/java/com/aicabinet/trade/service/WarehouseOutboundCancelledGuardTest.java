package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.WarehouseOutbound;
import com.aicabinet.trade.domain.WarehouseOutboundLine;
import com.aicabinet.trade.mapper.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** H25：作废出库单/已作废行不得再拣货发货扣库；行项作废时同步清除拣货标记。 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WarehouseOutboundCancelledGuardTest {

    @Mock private WarehouseMapper warehouseRepository;
    @Mock private WarehouseInventoryMapper inventoryRepository;
    @Mock private WarehouseInboundMapper inboundRepository;
    @Mock private WarehouseInboundLineMapper inboundLineRepository;
    @Mock private WarehouseOutboundMapper outboundRepository;
    @Mock private WarehouseOutboundLineMapper outboundLineRepository;
    @Mock private WarehouseMovementMapper movementRepository;
    @Mock private DeviceSkuInventoryMapper deviceInventoryRepository;
    @Mock private ReplenishmentTaskMapper taskRepository;
    @Mock private ReplenishmentRouteMapper routeRepository;
    @Mock private SkuCatalogMapper skuCatalogRepository;
    @Mock private DeviceSlotService deviceSlotService;
    @Mock private SalesVelocityService salesVelocityService;
    @Mock private InTransitService inTransitService;
    @Mock private InventoryLotService inventoryLotService;
    @Mock private DistributedLockService distributedLockService;
    @Mock private DisplaySnapshotHelper displaySnapshotHelper;

    private WarehouseService service;

    @BeforeEach
    void setUp() {
        service = new WarehouseService(warehouseRepository, inventoryRepository,
                inboundRepository, inboundLineRepository, outboundRepository, outboundLineRepository,
                movementRepository, deviceInventoryRepository, taskRepository, routeRepository, skuCatalogRepository,
                deviceSlotService, salesVelocityService, inTransitService, inventoryLotService,
                distributedLockService, displaySnapshotHelper, null);
        lenient().when(distributedLockService.tryLock(any(), anyLong(), anyLong())).thenReturn(true);
    }

    private WarehouseOutbound outbound(String status) {
        WarehouseOutbound outbound = new WarehouseOutbound();
        outbound.setOutboundId(9L);
        outbound.setWarehouseId("WH-1");
        outbound.setStatus(status);
        return outbound;
    }

    private WarehouseOutboundLine pickedLine(long lineId) {
        WarehouseOutboundLine line = new WarehouseOutboundLine();
        line.setLineId(lineId);
        line.setOutboundId(9L);
        line.setDeviceId("CAB-1");
        line.setSkuId("SKU-1");
        line.setBatchNo("B1");
        line.setQuantity(5);
        line.setPicked(true);
        return line;
    }

    @Test
    void markPicked_cancelledOutbound_rejected() {
        WarehouseOutbound cancelled = outbound("CANCELLED");
        when(outboundRepository.findByIdForUpdate(9L)).thenReturn(Optional.of(cancelled));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.markPicked(9L));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }

    @Test
    void ship_cancelledOutbound_rejectedWithoutStockDeduction() {
        WarehouseOutbound cancelled = outbound("CANCELLED");
        when(outboundRepository.findByIdForUpdate(9L)).thenReturn(Optional.of(cancelled));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.shipOutbound(1L, 9L));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        verify(movementRepository, never()).save(any());
    }

    @Test
    void ship_allLinesCancelled_rejected() {
        WarehouseOutbound picked = outbound("PICKED");
        when(outboundRepository.findByIdForUpdate(9L)).thenReturn(Optional.of(picked));
        WarehouseOutboundLine line = pickedLine(1L);
        line.setHandoverStatus("CANCELLED");
        when(outboundLineRepository.findByOutboundIdOrderByLineIdAsc(9L)).thenReturn(List.of(line));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.shipOutbound(1L, 9L));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        verify(movementRepository, never()).save(any());
    }

    @Test
    void cancelDeviceLines_clearsPickedFlag() {
        WarehouseOutbound draft = outbound("DRAFT");
        when(outboundRepository.findByIdForUpdate(9L)).thenReturn(Optional.of(draft));
        WarehouseOutboundLine line = pickedLine(1L);
        when(outboundLineRepository.findByOutboundIdAndDeviceIdOrderByLineIdAsc(9L, "CAB-1"))
                .thenReturn(List.of(line));
        // 全部行作废后触发整单作废收尾
        when(outboundLineRepository.findByOutboundIdOrderByLineIdAsc(9L)).thenReturn(List.of(line));

        service.cancelUnreceivedOutboundForDevice(9L, "CAB-1", 1L);

        assertFalse(line.isPicked(), "作废行必须清除拣货标记，避免发货前置校验被放行");
        assertEquals("CANCELLED", line.getHandoverStatus());
        assertEquals("CANCELLED", draft.getStatus());
        verify(outboundLineRepository).save(line);
    }
}
