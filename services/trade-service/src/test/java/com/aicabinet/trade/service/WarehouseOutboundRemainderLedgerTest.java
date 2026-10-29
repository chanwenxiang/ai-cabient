package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.WarehouseOutbound;
import com.aicabinet.trade.domain.WarehouseOutboundLine;
import com.aicabinet.trade.mapper.DeviceInfoMapper;
import com.aicabinet.trade.mapper.DeviceSkuInventoryMapper;
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
import com.aicabinet.trade.mapper.ReplenishmentRouteMapper;
import com.aicabinet.trade.mapper.ReplenishmentTaskMapper;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CB-074（2026-10-29）：出库行签收必须按 SKU 记实收，并把「领而未上」的差额留在**同一张单据的同一行**上。
 *
 * <p>旧实现只有「全量签收」一种形态（调用方传 {@code Integer.MAX_VALUE}），于是
 * 「领了 10 件只上柜 7 件」这件事在库里查不出来，月结「上柜」列其实是领货量。
 * 行业对照见 {@code docs/COMPETITOR_BENCHMARK.md} CB-074：Dynamics 365 BC 部分接收把差额
 * 留在订单行的「要接收的数量」上；**没有任何一家**把退回源头仓做成系统默认（退回是第二张单据）。
 *
 * <p>🔴 因此本类同时钉住「差额不得动库存」：签收路径只写 {@code remainder_qty} 与交接状态，
 * 既不产生 {@code warehouse_movement}（会与 {@code OUTBOUND_SHIP} 双扣分仓，铁律 79），
 * 也不给柜机补记（铁律 78）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WarehouseOutboundRemainderLedgerTest {

    private static final Long OUTBOUND_ID = 500L;
    private static final String DEVICE = "EG-DOOR";

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

    private WarehouseService warehouseService;

    @BeforeEach
    void setUp() {
        lenient().when(distributedLockService.tryLock(anyString(), eq(60L), eq(5L))).thenReturn(true);
        warehouseService = new WarehouseService(warehouseRepository, inventoryRepository,
                inboundRepository, inboundLineRepository, outboundRepository, outboundLineRepository,
                movementRepository, deviceInventoryRepository, taskRepository, routeRepository, skuCatalogRepository,
                deviceSlotService, salesVelocityService, inTransitService, inventoryLotService, distributedLockService,
                displaySnapshotHelper, deviceInfoRepository, null,
                userInfoRepository, permissionService, merchantScopeService, auditService, warehouseManagerRepository,
                null);
        org.springframework.test.util.ReflectionTestUtils.setField(warehouseService, "self", warehouseService);

        WarehouseOutbound outbound = new WarehouseOutbound();
        outbound.setOutboundId(OUTBOUND_ID);
        outbound.setStatus("IN_TRANSIT");
        outbound.setHandoverStatus("IN_TRANSIT");
        when(outboundRepository.findByIdForUpdate(OUTBOUND_ID)).thenReturn(Optional.of(outbound));
        lenient().when(outboundRepository.save(any(WarehouseOutbound.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    private WarehouseOutboundLine line(String skuId, int quantity) {
        WarehouseOutboundLine line = new WarehouseOutboundLine();
        line.setOutboundId(OUTBOUND_ID);
        line.setDeviceId(DEVICE);
        line.setSkuId(skuId);
        line.setBatchNo("B-" + skuId);
        line.setQuantity(quantity);
        line.setHandoverStatus("IN_TRANSIT");
        return line;
    }

    private void stubLines(WarehouseOutboundLine... lines) {
        List<WarehouseOutboundLine> list = List.of(lines);
        when(outboundLineRepository.findByOutboundIdAndDeviceIdOrderByLineIdAsc(OUTBOUND_ID, DEVICE))
                .thenReturn(list);
        when(outboundLineRepository.findByOutboundIdOrderByLineIdAsc(OUTBOUND_ID)).thenReturn(list);
    }

    private List<WarehouseOutboundLine> captureSavedLines() {
        ArgumentCaptor<WarehouseOutboundLine> captor = ArgumentCaptor.forClass(WarehouseOutboundLine.class);
        verify(outboundLineRepository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        return captor.getAllValues();
    }

    @Test
    void partialReceipt_recordsRemainderOnTheSkuThatWasNotShelved() {
        // 真机 2026-10-28 形态：领了雪碧 2 / 冰红茶 3 / 加多宝 3，现场只上柜雪碧 2
        stubLines(line("SKU-SPRITE", 2), line("SKU-TEA", 3), line("SKU-JDB", 3));

        warehouseService.markDeviceHandoverReceived(OUTBOUND_ID, DEVICE, Map.of("SKU-SPRITE", 2));

        List<WarehouseOutboundLine> saved = captureSavedLines();
        assertEquals(List.of("RECEIVED", "PARTIAL", "PARTIAL"),
                saved.stream().map(WarehouseOutboundLine::getHandoverStatus).toList());
        assertEquals(List.of(0, 3, 3), saved.stream().map(WarehouseOutboundLine::getRemainderQty).toList());
        assertEquals("PARTIAL", outboundStatusAfterSave());
        // 差额只落单据列：不动分仓流水，也不给柜机补记（铁律 78/79）
        verify(movementRepository, org.mockito.Mockito.never())
                .insert(any(com.aicabinet.trade.domain.WarehouseMovement.class));
        verify(inventoryLotService, org.mockito.Mockito.never()).addRestock(anyString(), anyString(), any());
    }

    @Test
    void receiptSplitAcrossLinesOfSameSku_keepsRemainderOnTheUnservedLine() {
        // 同 SKU 拆两行（不同批次）：实收 4 ⇒ 第一行满、第二行差 2，逐行分摊而不是整单看总额
        stubLines(line("SKU-COLA", 3), line("SKU-COLA", 3));

        warehouseService.markDeviceHandoverReceived(OUTBOUND_ID, DEVICE, Map.of("SKU-COLA", 4));

        List<WarehouseOutboundLine> saved = captureSavedLines();
        assertEquals(List.of(0, 2), saved.stream().map(WarehouseOutboundLine::getRemainderQty).toList());
        assertEquals(List.of("RECEIVED", "PARTIAL"),
                saved.stream().map(WarehouseOutboundLine::getHandoverStatus).toList());
    }

    @Test
    void overReceipt_clampsRemainderToZeroInsteadOfGoingNegative() {
        // 现场比领货多上柜（补货员顺带清了旧库存）：出库行不能记成负差额
        stubLines(line("SKU-COLA", 2));

        warehouseService.markDeviceHandoverReceived(OUTBOUND_ID, DEVICE, Map.of("SKU-COLA", 5));

        assertEquals(List.of(0), captureSavedLines().stream()
                .map(WarehouseOutboundLine::getRemainderQty).toList());
        assertEquals("RECEIVED", outboundStatusAfterSave());
    }

    @Test
    void emptyReceipt_ledgersWholeShipmentAsUnshelved() {
        // 铁律 80：现场一件没上，行数量如实减到 0 ⇒ 全部计为「领而未上」，货还在补货员手上
        stubLines(line("SKU-COLA", 4), line("SKU-TEA", 1));

        warehouseService.markDeviceHandoverReceived(OUTBOUND_ID, DEVICE, Map.of());

        List<WarehouseOutboundLine> saved = captureSavedLines();
        assertEquals(List.of(4, 1), saved.stream().map(WarehouseOutboundLine::getRemainderQty).toList());
        assertEquals(List.of("PARTIAL", "PARTIAL"),
                saved.stream().map(WarehouseOutboundLine::getHandoverStatus).toList());
    }

    @Test
    void fullReceiptEntry_marksEveryLineReceivedWithNoRemainder() {
        stubLines(line("SKU-COLA", 4), line("SKU-TEA", 1));

        warehouseService.markDeviceHandoverReceivedFull(OUTBOUND_ID, DEVICE);

        List<WarehouseOutboundLine> saved = captureSavedLines();
        assertEquals(List.of(0, 0), saved.stream().map(WarehouseOutboundLine::getRemainderQty).toList());
        assertEquals(List.of("RECEIVED", "RECEIVED"),
                saved.stream().map(WarehouseOutboundLine::getHandoverStatus).toList());
        assertEquals("RECEIVED", outboundStatusAfterSave());
    }

    /** 单头状态取自最后一次 save 的实体（同一实例，save 已回写）。 */
    private String outboundStatusAfterSave() {
        ArgumentCaptor<WarehouseOutbound> captor = ArgumentCaptor.forClass(WarehouseOutbound.class);
        verify(outboundRepository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        return captor.getValue().getHandoverStatus();
    }
}
