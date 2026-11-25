package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.DeviceSkuInventory;
import com.aicabinet.trade.domain.DeviceSkuInventoryId;
import com.aicabinet.trade.domain.DeviceSkuLot;
import com.aicabinet.trade.domain.InventoryMovement;
import com.aicabinet.trade.domain.SkuCatalog;
import com.aicabinet.trade.mapper.DeviceSkuInventoryMapper;
import com.aicabinet.trade.mapper.DeviceSkuLotMapper;
import com.aicabinet.trade.mapper.InventoryMovementMapper;
import com.aicabinet.trade.mapper.PullOffTaskMapper;
import com.aicabinet.trade.mapper.SkuCatalogMapper;
import com.aicabinet.trade.mapper.DeviceSlotMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InventoryLotServiceTest {

    @Mock private DeviceSkuLotMapper lotRepository;
    @Mock private InventoryMovementMapper movementRepository;
    @Mock private DeviceSkuInventoryMapper inventoryRepository;
    @Mock private SkuCatalogMapper skuCatalogRepository;
    @Mock private PullOffTaskMapper pullOffTaskRepository;
    @Mock private DeviceSlotMapper slotRepository;
    @Mock private DistributedLockService distributedLockService;

    private InventoryLotService service;
    private final Map<String, DeviceSkuLot> lots = new LinkedHashMap<>();
    private DeviceSkuInventory aggregate;

    @BeforeEach
    void setUp() {
        service = spy(new InventoryLotService(lotRepository, movementRepository, inventoryRepository,
                skuCatalogRepository, pullOffTaskRepository, slotRepository, distributedLockService, null));
        ReflectionTestUtils.setField(service, "self", service);
        when(distributedLockService.tryLock(anyString(), anyLong(), anyLong())).thenReturn(true);

        DeviceSkuLot otherSku = new DeviceSkuLot();
        otherSku.setLotId("L-OTHER");
        otherSku.setDeviceId("CAB-MIXED");
        otherSku.setSkuId("SKU-B");
        otherSku.setBatchNo("B-OTHER");
        otherSku.setQuantity(7);
        when(lotRepository.findByDeviceId("CAB-MIXED")).thenReturn(List.of(otherSku));
        when(lotRepository.findByDeviceIdAndSkuIdOrderByExpiryDateAsc("CAB-MIXED", "SKU-A"))
                .thenAnswer(inv -> new ArrayList<>(lots.values()));
        when(lotRepository.findByDeviceIdAndSkuIdAndBatchNo(anyString(), anyString(), anyString()))
                .thenAnswer(inv -> Optional.ofNullable(lots.get(inv.getArgument(2))));
        when(lotRepository.save(any(DeviceSkuLot.class))).thenAnswer(inv -> {
            DeviceSkuLot lot = inv.getArgument(0);
            lots.put(lot.getBatchNo(), lot);
            return 1;
        });
        when(lotRepository.sumSellableQuantity("CAB-MIXED", "SKU-A"))
                .thenAnswer(inv -> lots.values().stream().mapToInt(DeviceSkuLot::getQuantity).sum());
        when(lotRepository.deductQuantityAtomic(anyString(), anyInt())).thenAnswer(inv -> {
            DeviceSkuLot lot = lots.values().stream()
                    .filter(candidate -> candidate.getLotId().equals(inv.getArgument(0)))
                    .findFirst().orElseThrow();
            int take = inv.getArgument(1);
            if (lot.getQuantity() < take) return 0;
            lot.setQuantity(lot.getQuantity() - take);
            return 1;
        });
        when(movementRepository.save(any(InventoryMovement.class))).thenAnswer(inv -> inv.getArgument(0));

        aggregate = new DeviceSkuInventory();
        aggregate.setId(new DeviceSkuInventoryId("CAB-MIXED", "SKU-A"));
        aggregate.setQuantity(5);
        aggregate.setVersion(0);
        when(inventoryRepository.findById(aggregate.getId())).thenReturn(Optional.of(aggregate));
        when(inventoryRepository.save(any(DeviceSkuInventory.class))).thenAnswer(inv -> inv.getArgument(0));

        SkuCatalog sku = new SkuCatalog();
        sku.setShelfLifeDays(30);
        when(skuCatalogRepository.findById("SKU-A")).thenReturn(Optional.of(sku));
    }

    @Test
    void stocktakeAdjust_mixedLotDevice_usesBareAggregateAsBaseline() {
        service.stocktakeAdjust("CAB-MIXED", "SKU-A", 3, 1L, "REF-1");

        ArgumentCaptor<InventoryMovement> movement = ArgumentCaptor.forClass(InventoryMovement.class);
        verify(movementRepository).save(movement.capture());
        assertEquals(-2, movement.getValue().getDeltaQty());
        assertEquals("ADJ", movement.getValue().getMovementType());
        assertEquals(3, aggregate.getQuantity());
    }

    @Test
    void availableSellableQuantity_mixedLotDevice_usesAggregateForSkuWithoutLots() {
        // 同柜 SKU-B 已启用批次账，不能因此把 SKU-A 的裸库存读成 0。
        assertEquals(5, service.availableSellableQuantity("CAB-MIXED", "SKU-A"));

        DeviceSkuLot lot = new DeviceSkuLot();
        lot.setLotId("L-A");
        lot.setDeviceId("CAB-MIXED");
        lot.setSkuId("SKU-A");
        lot.setBatchNo("B-A");
        lot.setQuantity(2);
        lots.put("B-A", lot);

        assertEquals(2, service.availableSellableQuantity("CAB-MIXED", "SKU-A"),
                "SKU-A 一旦有批次，可售量必须切到批次汇总而不是裸库存");
    }
}
