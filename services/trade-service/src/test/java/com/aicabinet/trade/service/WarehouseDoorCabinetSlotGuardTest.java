package com.aicabinet.trade.service;

import com.aicabinet.common.dto.ReplenishmentSuggestDto;
import com.aicabinet.trade.domain.DeviceSkuInventory;
import com.aicabinet.trade.domain.DeviceSkuInventoryId;
import com.aicabinet.trade.domain.Warehouse;
import com.aicabinet.trade.domain.WarehouseInventory;
import com.aicabinet.trade.domain.WarehouseManager;
import com.aicabinet.trade.domain.WarehouseOutbound;
import com.aicabinet.trade.domain.WarehouseOutboundLine;
import com.aicabinet.trade.mapper.DeviceInfoMapper;
import com.aicabinet.trade.mapper.DeviceSkuInventoryMapper;
import com.aicabinet.trade.mapper.ReplenishmentRouteMapper;
import com.aicabinet.trade.mapper.ReplenishmentTaskMapper;
import com.aicabinet.trade.mapper.SkuCatalogMapper;
import com.aicabinet.trade.mapper.UserInfoMapper;
import com.aicabinet.trade.mapper.WarehouseInboundLineMapper;
import com.aicabinet.trade.mapper.WarehouseInboundMapper;
import com.aicabinet.trade.mapper.WarehouseInventoryMapper;
import com.aicabinet.trade.mapper.WarehouseManagerMapper;
import com.aicabinet.trade.mapper.WarehouseMapper;
import com.aicabinet.trade.mapper.WarehouseMovementMapper;
import com.aicabinet.trade.mapper.WarehouseOutboundLineMapper;
import com.aicabinet.trade.mapper.WarehouseOutboundMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 铁律 78 的三处「开门柜不看货道」守卫（审计 F3，2026-10-29）。
 *
 * <p>开门柜（判据＝将邑绑定）按商品备货，{@code device_slot} 对它是死数据，柜上常残留演示/历史货道行。
 * 旧实现只看 {@code hasSkuSlots} 不看柜型，于是同一批死数据会：①把建议量截到货道余量（补不够）、
 * ②拣货/发运时静默减量甚至删行（仓账与台账错位）、③「待分配」徽章常亮（页面上根本没有货道可分配）。
 *
 * <p>每组都配一个货架柜对照用例：证明生效的是**柜型分流**，不是把货道封顶整块关掉。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WarehouseDoorCabinetSlotGuardTest {

    private static final String DOOR = "EG-DOOR";
    private static final String SHELF = "CAB-SHELF";
    private static final String SKU = "100013";
    private static final Long OUTBOUND_ID = 600L;

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
    @Mock private DeviceInfoMapper deviceInfoRepository;
    @Mock private UserInfoMapper userInfoRepository;
    @Mock private PermissionService permissionService;
    @Mock private MerchantScopeService merchantScopeService;
    @Mock private AdminAuditService auditService;
    @Mock private WarehouseManagerMapper warehouseManagerRepository;

    private WarehouseService service;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), WarehouseOutboundLine.class);
        service = new WarehouseService(warehouseRepository, inventoryRepository,
                inboundRepository, inboundLineRepository, outboundRepository, outboundLineRepository,
                movementRepository, deviceInventoryRepository, taskRepository, routeRepository, skuCatalogRepository,
                deviceSlotService, salesVelocityService, inTransitService, inventoryLotService, distributedLockService,
                displaySnapshotHelper, deviceInfoRepository, null,
                userInfoRepository, permissionService, merchantScopeService, auditService,
                warehouseManagerRepository, null);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "self", service);
        lenient().when(distributedLockService.tryLock(anyString(), anyLong(), anyLong())).thenReturn(true);
    }

    // ── 建议侧：capSuggestBySlotHeadroom ──────────────────────────────

    private void stubSuggest(String device, int bookQty) {
        lenient().when(inTransitService.qtyBySkuForDevice(device)).thenReturn(Map.of());
        lenient().when(salesVelocityService.velocityBySku(device)).thenReturn(Map.of());
        lenient().when(deviceSlotService.suggestSlotsForDevice(device)).thenReturn(List.of());
        lenient().when(inventoryLotService.lotLedgerSkuIds(device)).thenReturn(Set.of());
        DeviceSkuInventory row = new DeviceSkuInventory();
        row.setId(new DeviceSkuInventoryId(device, SKU));
        row.setQuantity(bookQty);
        row.setCapacity(4);
        row.setLowThreshold(3);
        when(deviceInventoryRepository.findByIdDeviceId(device)).thenReturn(List.of(row));
        // 在架判据（2026-10-30）：建议只收 sku_catalog.status=ACTIVE（本用例只验柜型分流，SKU 在架）
        com.aicabinet.trade.domain.SkuCatalog catalogRow = new com.aicabinet.trade.domain.SkuCatalog();
        catalogRow.setSkuId(SKU);
        catalogRow.setStatus("ACTIVE");
        lenient().when(skuCatalogRepository.selectList(any())).thenReturn(List.of(catalogRow));
        // 残留货道行：hasSkuSlots 仍为真——这正是旧实现误封顶的入口
        lenient().when(deviceSlotService.hasSkuSlots(device, SKU)).thenReturn(true);
    }

    @Test
    void suggestForDevice_doorCabinetIgnoresResidualSlotHeadroom() {
        stubSuggest(DOOR, 0);
        when(deviceSlotService.isDoorCabinet(DOOR)).thenReturn(true);
        lenient().when(deviceSlotService.totalHeadroomForSku(DOOR, SKU)).thenReturn(1);

        List<ReplenishmentSuggestDto> out = service.suggestForDevice(DOOR);

        assertEquals(1, out.size());
        assertEquals(4, out.get(0).suggestQty(), "开门柜建议应按台账容量 4−0，不得被死货道余量 1 截断");
        verify(deviceSlotService, never()).totalHeadroomForSku(anyString(), anyString());
    }

    @Test
    void suggestForDevice_shelfCabinetStillCapsBySlotHeadroom() {
        stubSuggest(SHELF, 0);
        when(deviceSlotService.isDoorCabinet(SHELF)).thenReturn(false);
        when(deviceSlotService.totalHeadroomForSku(SHELF, SKU)).thenReturn(1);

        List<ReplenishmentSuggestDto> out = service.suggestForDevice(SHELF);

        assertEquals(1, out.size());
        assertEquals(1, out.get(0).suggestQty(), "货架柜仍按货道余量封顶（别把守卫做成整块关掉）");
    }

    // ── 拣货侧：shouldClampLineToHeadroom ─────────────────────────────

    private void stubOutboundForPick(String device, int lineQty) {
        WarehouseOutbound outbound = new WarehouseOutbound();
        outbound.setOutboundId(OUTBOUND_ID);
        outbound.setWarehouseId("900000000001");
        outbound.setStatus("DRAFT");
        when(outboundRepository.findByIdForUpdate(OUTBOUND_ID)).thenReturn(Optional.of(outbound));
        when(outboundRepository.findById(OUTBOUND_ID)).thenReturn(Optional.of(outbound));
        WarehouseOutboundLine line = new WarehouseOutboundLine();
        line.setLineId(1L);
        line.setOutboundId(OUTBOUND_ID);
        line.setDeviceId(device);
        line.setSkuId(SKU);
        line.setBatchNo("B-1");
        line.setQuantity(lineQty);
        List<WarehouseOutboundLine> lines = List.of(line);
        when(outboundLineRepository.findByOutboundIdOrderByLineIdAsc(OUTBOUND_ID)).thenReturn(lines);
        lenient().when(outboundLineRepository.save(any(WarehouseOutboundLine.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void markPicked_doorCabinetLineIsNotClampedOrDeleted() {
        stubOutboundForPick(DOOR, 5);
        when(deviceSlotService.isDoorCabinet(DOOR)).thenReturn(true);
        lenient().when(deviceSlotService.hasSkuSlots(DOOR, SKU)).thenReturn(true);
        lenient().when(deviceSlotService.totalHeadroomForSku(DOOR, SKU)).thenReturn(1);

        service.markPicked(OUTBOUND_ID);

        assertEquals(5, lastSavedLine().getQuantity(), "开门柜出库行不得被残留货道静默减量");
        verify(outboundLineRepository, never()).deleteById(any(Long.class));
        verify(deviceSlotService, never()).totalHeadroomForSku(anyString(), anyString());
    }

    @Test
    void markPicked_shelfCabinetLineIsStillClampedToHeadroom() {
        stubOutboundForPick(SHELF, 5);
        when(deviceSlotService.isDoorCabinet(SHELF)).thenReturn(false);
        when(deviceSlotService.hasSkuSlots(SHELF, SKU)).thenReturn(true);
        when(deviceSlotService.totalHeadroomForSku(SHELF, SKU)).thenReturn(2);

        service.markPicked(OUTBOUND_ID);

        assertEquals(2, lastSavedLine().getQuantity(), "货架柜照旧按货道余量截断");
    }

    /** 截断路径会先落一次「改量」再落一次「置 picked」，取最后一次即最终入库形状。 */
    private WarehouseOutboundLine lastSavedLine() {
        ArgumentCaptor<WarehouseOutboundLine> captor = ArgumentCaptor.forClass(WarehouseOutboundLine.class);
        verify(outboundLineRepository, atLeastOnce()).save(captor.capture());
        List<WarehouseOutboundLine> saved = captor.getAllValues();
        return saved.get(saved.size() - 1);
    }

    // ── 建单侧：allocateFefoToOutbound 的 boundSlots 判据 ─────────────

    @Test
    void tryCreateOutboundFromLines_doorCabinetGetsOneSlotlessLineNotSplitByHeadroom() {
        String wh = "900000000001";
        Warehouse warehouse = new Warehouse();
        warehouse.setWarehouseId(wh);
        warehouse.setStatus("ACTIVE");
        when(warehouseRepository.findById(wh)).thenReturn(Optional.of(warehouse));
        when(warehouseManagerRepository.listByWarehouseId(wh)).thenReturn(List.of(new WarehouseManager()));
        when(outboundRepository.save(any(WarehouseOutbound.class))).thenAnswer(inv -> {
            WarehouseOutbound created = inv.getArgument(0);
            created.setOutboundId(OUTBOUND_ID);
            return created;
        });
        WarehouseInventory lot = new WarehouseInventory();
        lot.setWarehouseId(wh);
        lot.setSkuId(SKU);
        lot.setBatchNo("B-1");
        lot.setQuantity(10);
        lot.setExpiryDate(java.time.LocalDate.now().plusMonths(6));
        when(inventoryRepository.findByWarehouseIdAndSkuIdOrderByExpiryDateAsc(wh, SKU)).thenReturn(List.of(lot));
        when(outboundLineRepository.sumAllocatedQty(wh, SKU, "B-1")).thenReturn(0);
        when(deviceSlotService.isDoorCabinet(DOOR)).thenReturn(true);
        lenient().when(deviceSlotService.hasSkuSlots(DOOR, SKU)).thenReturn(true);
        lenient().when(deviceSlotService.totalHeadroomForSku(DOOR, SKU)).thenReturn(1);

        Long id = service.tryCreateOutboundFromLines(1L, DOOR, 100L, Map.of(SKU, 5), wh);

        assertEquals(OUTBOUND_ID, id);
        ArgumentCaptor<WarehouseOutboundLine> captor = ArgumentCaptor.forClass(WarehouseOutboundLine.class);
        verify(outboundLineRepository).save(captor.capture());
        assertEquals(5, captor.getValue().getQuantity(), "要 5 件就该出 5 件，货道余量 1 不得截断");
        assertNull(captor.getValue().getSlotId(), "开门柜出库行不绑货道（与补货行恒无货道同口径）");
        verify(deviceSlotService, never()).allocateRestockQuantity(anyString(), anyString(), anyInt());
        verify(deviceSlotService, never()).totalHeadroomForSku(anyString(), anyString());
    }
}
