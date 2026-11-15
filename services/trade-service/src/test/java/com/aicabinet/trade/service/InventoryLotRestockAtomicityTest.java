package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.DeviceSkuLot;
import com.aicabinet.trade.mapper.DeviceSkuInventoryMapper;
import com.aicabinet.trade.mapper.DeviceSkuLotMapper;
import com.aicabinet.trade.mapper.DeviceSlotMapper;
import com.aicabinet.trade.mapper.InventoryMovementMapper;
import com.aicabinet.trade.mapper.PullOffTaskMapper;
import com.aicabinet.trade.mapper.SkuCatalogMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MA-176：`addRestock` 对**已有批次**必须走单语句原子累加，不能「读→算→绝对 save」。
 *
 * <p>DeviceSkuLot 没有 version，`save` 是全列覆盖；设备锁失效窗口内，补货的旧快照会把
 * 并发的下架/报损/FEFO 扣减覆盖回去（账面虚高、可再卖）。同文件的退款回补路径
 * （{@code doRestoreToBatch}）早已修成原子累加，本条是 R18-2 同族残留。
 */
@ExtendWith(MockitoExtension.class)
class InventoryLotRestockAtomicityTest {

    private static final String DEV = "DEV-1";
    private static final String SKU = "SKU-1";
    private static final String BATCH = "B-1";
    private static final String LOT_ID = "L-EXISTING";
    private static final LocalDate EXPIRY = LocalDate.now().plusDays(60);

    @Mock private DeviceSkuLotMapper lotRepository;
    @Mock private InventoryMovementMapper movementRepository;
    @Mock private DeviceSkuInventoryMapper inventoryRepository;
    @Mock private SkuCatalogMapper skuCatalogRepository;
    @Mock private PullOffTaskMapper pullOffTaskRepository;
    @Mock private DeviceSlotMapper slotRepository;
    @Mock private DistributedLockService distributedLockService;

    private InventoryLotService service;

    @BeforeEach
    void setUp() {
        service = new InventoryLotService(lotRepository, movementRepository, inventoryRepository,
                skuCatalogRepository, pullOffTaskRepository, slotRepository, distributedLockService, null);
        // syncAggregateInventory 会再查一堆表；这里只钉补货写批次的方式，用 mock self 让它 no-op。
        org.springframework.test.util.ReflectionTestUtils.setField(
                service, "self", mock(InventoryLotService.class));
        when(distributedLockService.tryLock(anyString(), anyLong(), anyLong())).thenReturn(true);
        // validateRestockExpiry / resolveLotStatus 都读 SKU 配置；null SKU ⇒ 兜底值
        when(skuCatalogRepository.findById(SKU)).thenReturn(Optional.empty());
    }

    private static InventoryLotService.RestockCommand command(int qty) {
        return new InventoryLotService.RestockCommand(
                BATCH, LocalDate.now().minusDays(1), EXPIRY, qty,
                new InventoryLotService.LotMovementRef("REPLENISH", "R-1", 1L));
    }

    private static DeviceSkuLot existingLot(int qty) {
        DeviceSkuLot lot = new DeviceSkuLot();
        lot.setLotId(LOT_ID);
        lot.setDeviceId(DEV);
        lot.setSkuId(SKU);
        lot.setBatchNo(BATCH);
        lot.setExpiryDate(EXPIRY);
        lot.setQuantity(qty);
        lot.setStatus("ON_SALE");
        return lot;
    }

    /** 已有批次：数量必须走原子累加，且绝不能 save 覆盖整行。 */
    @Test
    void existingBatch_usesAtomicIncrementAndNeverSaves() {
        when(lotRepository.findByDeviceIdAndSkuIdAndBatchNo(DEV, SKU, BATCH))
                .thenReturn(Optional.of(existingLot(10)));

        service.addRestock(DEV, SKU, command(5));

        verify(lotRepository).increaseQuantityAtomic(LOT_ID, 5);
        verify(lotRepository).updateStatusAndSlotById(LOT_ID, "ON_SALE", null);
        verify(lotRepository, never()).save(any());
    }

    /** 正向控制：新建批次仍走一次 INSERT（quantity 随行写入），没有被改成原子累加。 */
    @Test
    void newBatch_stillInsertsWithQuantity() {
        when(lotRepository.findByDeviceIdAndSkuIdAndBatchNo(DEV, SKU, BATCH))
                .thenReturn(Optional.empty());

        service.addRestock(DEV, SKU, command(5));

        ArgumentCaptor<DeviceSkuLot> captor = ArgumentCaptor.forClass(DeviceSkuLot.class);
        verify(lotRepository).save(captor.capture());
        assertEquals(5, captor.getValue().getQuantity(), "新建批次 quantity 必须随 INSERT 一次写入");
        verify(lotRepository, never()).increaseQuantityAtomic(anyString(), org.mockito.ArgumentMatchers.anyInt());
    }

    /** 原子累加的入参必须是本次补货量，不是内存里的旧值 + 增量（那正是绝对覆盖的形态）。 */
    @Test
    void existingBatch_passesDeltaNotAbsoluteQuantity() {
        when(lotRepository.findByDeviceIdAndSkuIdAndBatchNo(DEV, SKU, BATCH))
                .thenReturn(Optional.of(existingLot(10)));

        service.addRestock(DEV, SKU, command(7));

        verify(lotRepository).increaseQuantityAtomic(LOT_ID, 7);
        verify(lotRepository, never()).increaseQuantityAtomic(LOT_ID, 17);
    }
}
