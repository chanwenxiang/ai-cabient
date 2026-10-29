package com.aicabinet.trade.service;

import com.aicabinet.common.dto.ReplenishmentSuggestDto;
import com.aicabinet.trade.domain.Warehouse;
import com.aicabinet.trade.domain.WarehouseInventory;
import com.aicabinet.trade.domain.WarehouseManager;
import com.aicabinet.trade.domain.WarehouseOutbound;
import com.aicabinet.trade.domain.WarehouseOutboundLine;
import com.aicabinet.trade.domain.DeviceInfo;
import com.aicabinet.trade.domain.ReplenishmentRoute;
import com.aicabinet.trade.domain.ReplenishmentTask;
import com.aicabinet.trade.domain.UserInfo;
import com.aicabinet.trade.mapper.*;
import com.aicabinet.trade.support.ApiMessages;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WarehouseServiceTest {

    static {
        // 建单失败路径里 unlinkTasksFromOutbound 用 LambdaUpdateWrapper 清 outbound_id：
        // 纯 Mockito 环境无 SqlSession，需手动注册 TableInfo 缓存才能解析列名
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                ReplenishmentTask.class);
    }

    private static final String TEST_WAREHOUSE_ID = "900000000001";

    /** 分仓保存的操作人（合成编号，非真账号）：负责人变更审计要钉住「谁改的」。 */
    private static final Long UPSERT_OPERATOR = 7001L;

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

    /** stubOutboundSave 记录的「最近一次 save 返回体」，供 findById 回读（模拟已落库）。 */
    private final java.util.concurrent.atomic.AtomicReference<WarehouseOutbound> savedOutbound =
            new java.util.concurrent.atomic.AtomicReference<>();

    @BeforeEach
    void setUp() {
        when(distributedLockService.tryLock(anyString(), eq(60L), eq(5L))).thenReturn(true);
        warehouseService = new WarehouseService(warehouseRepository, inventoryRepository,
                inboundRepository, inboundLineRepository, outboundRepository, outboundLineRepository,
                movementRepository, deviceInventoryRepository, taskRepository, routeRepository, skuCatalogRepository,
                deviceSlotService, salesVelocityService, inTransitService, inventoryLotService, distributedLockService,
                displaySnapshotHelper, deviceInfoRepository, null,
                userInfoRepository, permissionService, merchantScopeService, auditService, warehouseManagerRepository, null);
        org.springframework.test.util.ReflectionTestUtils.setField(warehouseService, "self", warehouseService);
        Warehouse defaultWh = new Warehouse();
        defaultWh.setWarehouseId(TEST_WAREHOUSE_ID);
        defaultWh.setStatus("ACTIVE");
        defaultWh.setManagerUserId(9L);
        when(warehouseRepository.findAll()).thenReturn(List.of(defaultWh));
        when(warehouseRepository.findById(TEST_WAREHOUSE_ID)).thenReturn(Optional.of(defaultWh));
        // V412：resolveOutboundWarehouseId 的「有没有负责人」改读 warehouse_manager 集合，
        // 不再看 warehouse.manager_user_id 派名列 ⇒ 出库路径用例必须显式挂人才能过闸门。
        when(warehouseManagerRepository.listByWarehouseId(TEST_WAREHOUSE_ID))
                .thenReturn(List.of(managerRow(TEST_WAREHOUSE_ID, 9L)));
        when(warehouseManagerRepository.listByWarehouseId("WH-SAT"))
                .thenReturn(List.of(managerRow("WH-SAT", 40L)));
    }

    @Test
    void listWarehouses_shouldReturnAll() {
        var w1 = new Warehouse();
        w1.setWarehouseId("WH-001");
        w1.setWarehouseName("主仓库");

        when(warehouseRepository.searchPage(null, 0, 500)).thenReturn(pageOf(w1));

        var result = warehouseService.listWarehouses();

        assertEquals(1, result.size());
        assertEquals("WH-001", result.get(0).warehouseId());
        assertEquals("主仓库", result.get(0).warehouseName());
        verify(warehouseRepository, times(1)).searchPage(null, 0, 500);
    }

    @Test
    void listWarehouses_shouldReturnEmpty_whenNoData() {
        when(warehouseRepository.searchPage(null, 0, 500)).thenReturn(pageOf());

        var result = warehouseService.listWarehouses();

        assertTrue(result.isEmpty());
    }

    @Test
    void listInventory_shouldFilterByWarehouse() {
        when(inventoryRepository.findByWarehouseIdOrderByExpiryDateAsc("WH-001")).thenReturn(List.of());

        var result = warehouseService.listInventory("WH-001");

        assertTrue(result.isEmpty());
        verify(inventoryRepository).findByWarehouseIdOrderByExpiryDateAsc("WH-001");
    }

    @Test
    void tryCreateOutboundFromLines_returnsNull_whenZeroStock() {
        stubOutboundSave(501L);
        when(inventoryRepository.findByWarehouseIdAndSkuIdOrderByExpiryDateAsc(
                TEST_WAREHOUSE_ID, "SKU-1")).thenReturn(List.of());

        Long id = warehouseService.tryCreateOutboundFromLines(
                1L, null, 100L, Map.of("SKU-1", 5), TEST_WAREHOUSE_ID);

        assertNull(id);
        verify(outboundRepository).deleteById(501L);
        verify(outboundLineRepository, never()).save(any());
    }

    @Test
    void tryCreateOutboundFromLines_keepsOutbound_whenPartialStock() {
        stubOutboundSave(502L);
        WarehouseInventory lot = inventoryLot("SKU-1", "B1", 3);
        when(inventoryRepository.findByWarehouseIdAndSkuIdOrderByExpiryDateAsc(
                TEST_WAREHOUSE_ID, "SKU-1")).thenReturn(List.of(lot));
        when(outboundLineRepository.sumAllocatedQty(
                TEST_WAREHOUSE_ID, "SKU-1", "B1")).thenReturn(0);

        Long id = warehouseService.tryCreateOutboundFromLines(
                2L, null, 100L, Map.of("SKU-1", 10), TEST_WAREHOUSE_ID);

        assertEquals(502L, id);
        ArgumentCaptor<WarehouseOutboundLine> lineCaptor = ArgumentCaptor.forClass(WarehouseOutboundLine.class);
        verify(outboundLineRepository).save(lineCaptor.capture());
        assertEquals(3, lineCaptor.getValue().getQuantity());
        verify(outboundRepository, never()).deleteById(anyLong());
    }

    @Test
    void tryCreateOutboundFromLines_allocatesFull_whenEnoughStock() {
        stubOutboundSave(503L);
        WarehouseInventory lot = inventoryLot("SKU-1", "B1", 10);
        when(inventoryRepository.findByWarehouseIdAndSkuIdOrderByExpiryDateAsc(
                TEST_WAREHOUSE_ID, "SKU-1")).thenReturn(List.of(lot));
        when(outboundLineRepository.sumAllocatedQty(
                TEST_WAREHOUSE_ID, "SKU-1", "B1")).thenReturn(0);

        Long id = warehouseService.tryCreateOutboundFromLines(
                3L, null, 100L, new LinkedHashMap<>(Map.of("SKU-1", 5)), TEST_WAREHOUSE_ID);

        assertEquals(503L, id);
        ArgumentCaptor<WarehouseOutboundLine> lineCaptor = ArgumentCaptor.forClass(WarehouseOutboundLine.class);
        verify(outboundLineRepository).save(lineCaptor.capture());
        assertEquals(5, lineCaptor.getValue().getQuantity());
        verify(outboundRepository, never()).deleteById(anyLong());
    }

    @Test
    void tryCreateOutboundFromLines_fefo_deductsNearerExpiryFirst() {
        stubOutboundSave(504L);
        WarehouseInventory near = inventoryLot("SKU-1", "NEAR", 2, LocalDate.now().plusDays(10));
        WarehouseInventory far = inventoryLot("SKU-1", "FAR", 10, LocalDate.now().plusMonths(6));
        // Repository contract: already ordered by expiry ASC
        when(inventoryRepository.findByWarehouseIdAndSkuIdOrderByExpiryDateAsc(
                TEST_WAREHOUSE_ID, "SKU-1")).thenReturn(List.of(near, far));
        when(outboundLineRepository.sumAllocatedQty(
                TEST_WAREHOUSE_ID, "SKU-1", "NEAR")).thenReturn(0);
        when(outboundLineRepository.sumAllocatedQty(
                TEST_WAREHOUSE_ID, "SKU-1", "FAR")).thenReturn(0);

        Long id = warehouseService.tryCreateOutboundFromLines(
                4L, null, 100L, new LinkedHashMap<>(Map.of("SKU-1", 5)), TEST_WAREHOUSE_ID);

        assertEquals(504L, id);
        ArgumentCaptor<WarehouseOutboundLine> lineCaptor = ArgumentCaptor.forClass(WarehouseOutboundLine.class);
        verify(outboundLineRepository, times(2)).save(lineCaptor.capture());
        List<WarehouseOutboundLine> lines = lineCaptor.getAllValues();
        assertEquals("NEAR", lines.get(0).getBatchNo());
        assertEquals(2, lines.get(0).getQuantity());
        assertEquals("FAR", lines.get(1).getBatchNo());
        assertEquals(3, lines.get(1).getQuantity());
    }

    @Test
    void tryCreateOutboundFromLines_usesDeviceHomeWarehouse() {
        stubOutboundSave(505L);
        DeviceInfo device = new DeviceInfo();
        device.setDeviceId("CAB-1");
        device.setHomeWarehouseId("WH-SAT");
        when(deviceInfoRepository.findById("CAB-1")).thenReturn(Optional.of(device));
        Warehouse sat = new Warehouse();
        sat.setWarehouseId("WH-SAT");
        sat.setManagerUserId(40L);
        sat.setStatus("ACTIVE");
        when(warehouseRepository.findById("WH-SAT")).thenReturn(Optional.of(sat));
        when(inventoryRepository.findByWarehouseIdAndSkuIdOrderByExpiryDateAsc("WH-SAT", "SKU-1"))
                .thenReturn(List.of());

        Long id = warehouseService.tryCreateOutboundFromLines(
                5L, "CAB-1", 100L, Map.of("SKU-1", 2), null);

        assertNull(id);
        verify(inventoryRepository).findByWarehouseIdAndSkuIdOrderByExpiryDateAsc("WH-SAT", "SKU-1");
    }

    @Test
    void resolveOutboundWarehouseId_rejectsUnmanagedAndMixedHomes() {
        Warehouse unmanaged = new Warehouse();
        unmanaged.setWarehouseId(TEST_WAREHOUSE_ID);
        unmanaged.setManagerUserId(null);
        when(warehouseRepository.findById(TEST_WAREHOUSE_ID)).thenReturn(Optional.of(unmanaged));
        when(warehouseManagerRepository.listByWarehouseId(TEST_WAREHOUSE_ID))
                .thenReturn(List.of());
        var unmanagedEx = assertThrows(ResponseStatusException.class,
                () -> warehouseService.resolveOutboundWarehouseId(TEST_WAREHOUSE_ID, null, List.of()));
        assertTrue(String.valueOf(unmanagedEx.getReason()).contains("负责人"));

        DeviceInfo a = new DeviceInfo();
        a.setDeviceId("A");
        a.setHomeWarehouseId("WH-1");
        DeviceInfo b = new DeviceInfo();
        b.setDeviceId("B");
        b.setHomeWarehouseId("WH-2");
        when(deviceInfoRepository.findById("A")).thenReturn(Optional.of(a));
        when(deviceInfoRepository.findById("B")).thenReturn(Optional.of(b));
        ReplenishmentTask t1 = new ReplenishmentTask();
        t1.setDeviceId("A");
        ReplenishmentTask t2 = new ReplenishmentTask();
        t2.setDeviceId("B");
        var mixed = assertThrows(ResponseStatusException.class,
                () -> warehouseService.resolveOutboundWarehouseId(null, null, List.of(t1, t2)));
        assertEquals(ApiMessages.REPLENISHMENT_HOME_WAREHOUSE_MIXED, mixed.getReason());
    }

    // ── FS-G7-3：路线出库单「半满足」显性化（备注 + 日志），FEFO 分配与建单成败判定不变 ──
    @Test
    void createOutboundForRoute_keepsNotes_whenWarehouseCoversEverySuggestion() {
        WarehouseService svc = routedService(601L, routeTask(11L, "CAB-1"));
        doReturn(List.of(suggest("CAB-1", "SKU-1", 5))).when(svc).suggestForDevice("CAB-1", true);
        stubLot("SKU-1", "B1", 10);

        var dto = svc.createOutboundForRoute(7L, TEST_WAREHOUSE_ID, 100L);

        assertEquals("auto from route 7", dto.notes());
        verify(outboundRepository, times(1)).save(any(WarehouseOutbound.class));
    }

    @Test
    void createOutboundForRoute_annotatesNotes_whenWarehouseOnlyPartiallyCovers() {
        WarehouseService svc = routedService(602L, routeTask(12L, "CAB-1"));
        doReturn(List.of(suggest("CAB-1", "SKU-1", 10), suggest("CAB-1", "SKU-2", 4)))
                .when(svc).suggestForDevice("CAB-1", true);
        stubLot("SKU-1", "B1", 3);
        stubLot("SKU-2", "B2", 4);

        var dto = svc.createOutboundForRoute(8L, TEST_WAREHOUSE_ID, 100L);

        assertEquals("auto from route 8；半满足 1 项：SKU-1 需10/实3", dto.notes());
    }

    @Test
    void createOutboundForRoute_annotatesZeroAllocatedSku_whenWarehouseHasNoneOfIt() {
        // 活栈实跑踩到的形态：出库单只覆盖「仓内有货的 SKU」，其余缺口此前完全静默
        WarehouseService svc = routedService(603L, routeTask(13L, "CAB-1"));
        doReturn(List.of(suggest("CAB-1", "SKU-1", 3), suggest("CAB-1", "SKU-2", 6)))
                .when(svc).suggestForDevice("CAB-1", true);
        stubLot("SKU-1", "B1", 3);
        when(inventoryRepository.findByWarehouseIdAndSkuIdOrderByExpiryDateAsc(TEST_WAREHOUSE_ID, "SKU-2"))
                .thenReturn(List.of());

        var dto = svc.createOutboundForRoute(9L, TEST_WAREHOUSE_ID, 100L);

        assertEquals("auto from route 9；半满足 1 项：SKU-2 需6/实0", dto.notes());
        verify(outboundLineRepository, times(1)).save(any(WarehouseOutboundLine.class));
    }

    @Test
    void createOutboundForRoute_stillRollsBackToInsufficient_whenNothingAllocatable() {
        WarehouseService svc = routedService(604L, routeTask(14L, "CAB-1"));
        doReturn(List.of(suggest("CAB-1", "SKU-1", 5))).when(svc).suggestForDevice("CAB-1", true);
        when(inventoryRepository.findByWarehouseIdAndSkuIdOrderByExpiryDateAsc(TEST_WAREHOUSE_ID, "SKU-1"))
                .thenReturn(List.of());

        var ex = assertThrows(ResponseStatusException.class,
                () -> svc.createOutboundForRoute(10L, TEST_WAREHOUSE_ID, 100L));

        assertEquals(ApiMessages.REPLENISHMENT_WAREHOUSE_STOCK_INSUFFICIENT, ex.getReason());
        verify(outboundRepository).deleteById(604L);
        assertFalse(String.valueOf(savedOutbound.get().getNotes()).contains("半满足"));
    }

    @Test
    void formatShortfall_listsTenAndPointsToLogsForTheRest() {
        List<String> many = java.util.stream.IntStream.rangeClosed(1, 12)
                .mapToObj(i -> "SKU-" + i + " 需" + (i + 1) + "/实1")
                .toList();

        String note = WarehouseService.formatShortfall(many);

        assertTrue(note.startsWith("半满足 12 项：SKU-1 需2/实1；SKU-2 需3/实1"), note);
        assertTrue(note.contains("SKU-10 需11/实1"), note);
        assertFalse(note.contains("SKU-11"), note);
        assertTrue(note.endsWith("（余 2 项见服务日志）"), note);
    }

    /** 路线建单内部走 self.suggestForDevice / self.getOutbound ⇒ 用 spy 接住这两个自重入入口。 */
    private WarehouseService routedService(long outboundId, ReplenishmentTask... tasks) {
        WarehouseService svc = spy(warehouseService);
        org.springframework.test.util.ReflectionTestUtils.setField(svc, "self", svc);
        stubOutboundSave(outboundId);
        when(outboundRepository.findByRouteId(anyLong())).thenReturn(Optional.empty());
        when(outboundRepository.findById(outboundId))
                .thenAnswer(inv -> Optional.of(savedOutbound.get()));
        when(taskRepository.findByRouteId(anyLong())).thenReturn(List.of(tasks));
        return svc;
    }

    private void stubLot(String skuId, String batchNo, int qty) {
        when(inventoryRepository.findByWarehouseIdAndSkuIdOrderByExpiryDateAsc(TEST_WAREHOUSE_ID, skuId))
                .thenReturn(List.of(inventoryLot(skuId, batchNo, qty)));
        when(outboundLineRepository.sumAllocatedQty(TEST_WAREHOUSE_ID, skuId, batchNo)).thenReturn(0);
    }

    private static ReplenishmentTask routeTask(long taskId, String deviceId) {
        ReplenishmentTask task = new ReplenishmentTask();
        task.setTaskId(taskId);
        task.setDeviceId(deviceId);
        return task;
    }

    private static ReplenishmentSuggestDto suggest(String deviceId, String skuId, int suggestQty) {
        return new ReplenishmentSuggestDto(deviceId, skuId, 0, 0, 0, suggestQty);
    }

    private static Page<Warehouse> pageOf(Warehouse... rows) {
        Page<Warehouse> page = new Page<>(1, 500);
        page.setRecords(List.of(rows));
        page.setTotal(rows.length);
        return page;
    }

    private void stubOutboundSave(long outboundId) {
        when(outboundRepository.save(any(WarehouseOutbound.class))).thenAnswer(inv -> {
            WarehouseOutbound o = inv.getArgument(0);
            // TableId is private; save path relies on MyBatis assigning id — mirror via reflection-free stub
            WarehouseOutbound saved = new WarehouseOutbound();
            saved.setWarehouseId(o.getWarehouseId());
            saved.setRouteId(o.getRouteId());
            saved.setAssigneeUserId(o.getAssigneeUserId());
            saved.setStatus(o.getStatus());
            saved.setNotes(o.getNotes());
            try {
                var field = WarehouseOutbound.class.getDeclaredField("outboundId");
                field.setAccessible(true);
                field.set(saved, outboundId);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
            savedOutbound.set(saved);
            return saved;
        });
    }

    private static WarehouseInventory inventoryLot(String skuId, String batchNo, int qty) {
        return inventoryLot(skuId, batchNo, qty, LocalDate.now().plusMonths(6));
    }

    @Test
    void upsertWarehouse_setsManager_whenPositiveId() {
        Warehouse existing = existingWarehouse();
        when(warehouseRepository.findById("WH-001")).thenReturn(Optional.of(existing));
        when(warehouseRepository.save(any(Warehouse.class))).thenAnswer(inv -> inv.getArgument(0));
        stubAssignableManager(42L);

        var dto = warehouseService.upsertWarehouse(
                UPSERT_OPERATOR, "WH-001", "主仓库", "addr", "ACTIVE", List.of(42L));

        assertEquals(42L, dto.managerUserId());
        // 集合真的落库：整表重写（1 人 ⇒ 1 删 1 插），主责人派生列 = 列表第一位
        verify(warehouseManagerRepository).deleteByWarehouseId("WH-001");
        verify(warehouseManagerRepository).insert(any(WarehouseManager.class));
        // CB-070 第 4 点：换人要留前后值（编号可还原 + 人名给人看），且记的是操作人而不是负责人
        verify(auditService)
                .appendLog(
                        UPSERT_OPERATOR,
                        "WAREHOUSE_MANAGER",
                        "WAREHOUSE",
                        "WH-001",
                        "manager_user_id=[]->[42(补货负责人)]");
    }

    /** CB-070：重复保存同一个人（改名称/地址）不重新校验被授权人——否则账号后来被停用就会挡住无关编辑。 */
    @Test
    void upsertWarehouse_skipsManagerValidation_whenManagerUnchanged() {
        Warehouse existing = existingWarehouse();
        existing.setManagerUserId(42L);
        when(warehouseRepository.findById("WH-001")).thenReturn(Optional.of(existing));
        when(warehouseRepository.save(any(Warehouse.class))).thenAnswer(inv -> inv.getArgument(0));

        // 该仓已绑 42：本次提交同一集合 ⇒ 既不该重新校验，也不该重写集合
        when(warehouseManagerRepository.listByWarehouseId("WH-001"))
                .thenReturn(List.of(managerRow("WH-001", 42L)));

        var dto = warehouseService.upsertWarehouse(
                UPSERT_OPERATOR, "WH-001", "改名后的仓", "addr", "ACTIVE", List.of(42L));

        assertEquals(42L, dto.managerUserId());
        // 判据取「只有校验路径才会查的权限」——selectById 在回填 DTO 时本来就会调，钉不住这个行为
        verify(permissionService, never()).hasPermission(any(), any());
        verify(warehouseManagerRepository, never()).deleteByWarehouseId(any());
        // 没换人就不该长审计行——否则每次改地址都往台账里灌一条「42->42」，台账会被自己的噪音淹没。
        verify(auditService, never()).appendLog(any(), any(), any(), any(), any());
    }

    /** CB-070：负责人用户编号写错必须当场拒绝并说清去哪核对，而不是留下一个查无此人的仓。 */
    @Test
    void upsertWarehouse_rejectsManager_whenUserMissing() {
        Warehouse existing = existingWarehouse();
        when(warehouseRepository.findById("WH-001")).thenReturn(Optional.of(existing));
        when(userInfoRepository.selectById(4242L)).thenReturn(null);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> warehouseService.upsertWarehouse(
                        UPSERT_OPERATOR, "WH-001", "主仓库", "addr", "ACTIVE", List.of(4242L)));

        assertTrue(ex.getReason().contains("不存在"));
        verify(warehouseRepository, never()).save(any(Warehouse.class));
        // 校验当场挡住 ⇒ 一条审计也不该落（异常会让同事务的审计一起回滚，这里连调用都不该发生）
        verify(auditService, never()).appendLog(any(), any(), any(), any(), any());
    }

    @Test
    void upsertWarehouse_rejectsManager_whenUserDisabled() {
        Warehouse existing = existingWarehouse();
        when(warehouseRepository.findById("WH-001")).thenReturn(Optional.of(existing));
        UserInfo disabled = new UserInfo();
        disabled.setUserId(42L);
        disabled.setName("小王");
        disabled.setStatus("INACTIVE");
        when(userInfoRepository.selectById(42L)).thenReturn(disabled);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> warehouseService.upsertWarehouse(
                        UPSERT_OPERATOR, "WH-001", "主仓库", "addr", "ACTIVE", List.of(42L)));

        assertTrue(ex.getReason().contains("已停用"));
        verify(warehouseRepository, never()).save(any(Warehouse.class));
    }

    /** CB-070 判据 2（自加门）：跨商户指派要挡住——补货扣的是这个仓的账，人得管得到这批柜机。 */
    @Test
    void upsertWarehouse_rejectsManager_whenMerchantMismatch() {
        Warehouse existing = existingWarehouse();
        when(warehouseRepository.findById("WH-001")).thenReturn(Optional.of(existing));
        stubAssignableManager(42L);
        DeviceInfo inWarehouse = new DeviceInfo();
        inWarehouse.setDeviceId("CAB-001");
        inWarehouse.setMerchantId("MCH-A");
        inWarehouse.setHomeWarehouseId("WH-001");
        when(deviceInfoRepository.findAllOrderByDeviceIdAsc()).thenReturn(List.of(inWarehouse));
        when(merchantScopeService.allowedMerchantIds(42L)).thenReturn(Set.of("MCH-B"));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> warehouseService.upsertWarehouse(
                        UPSERT_OPERATOR, "WH-001", "主仓库", "addr", "ACTIVE", List.of(42L)));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        assertTrue(ex.getReason().contains("不属于分仓 WH-001 内柜机的商户"));
    }

    /** CB-070 判据 3：没有「发起补货」权限的人当分仓负责人 = 挂了名也发不起补货，保存时就该提示。 */
    @Test
    void upsertWarehouse_rejectsManager_whenNoReplenishRequestPerm() {
        Warehouse existing = existingWarehouse();
        when(warehouseRepository.findById("WH-001")).thenReturn(Optional.of(existing));
        when(userInfoRepository.selectById(42L)).thenReturn(new UserInfo());
        when(permissionService.hasPermission(42L, "merchant:replenishment:request")).thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> warehouseService.upsertWarehouse(
                        UPSERT_OPERATOR, "WH-001", "主仓库", "addr", "ACTIVE", List.of(42L)));

        assertTrue(ex.getReason().contains("发起补货"));
        verify(warehouseRepository, never()).save(any(Warehouse.class));
    }

    /** CB-068 A 档：盘点闸门保留，但拒绝文案要报出仓号 + 当前负责人，别让人猜成因。 */
    @Test
    void requireWarehouseManagedBy_namesWarehouseAndCurrentManager() {
        Warehouse existing = existingWarehouse();
        existing.setManagerUserId(7L);
        when(warehouseRepository.findById("WH-001")).thenReturn(Optional.of(existing));
        UserInfo manager = new UserInfo();
        manager.setUserId(7L);
        manager.setName("张三");
        manager.setPhoneNumber("13800001234");
        when(userInfoRepository.selectById(7L)).thenReturn(manager);
        when(warehouseManagerRepository.listByWarehouseId("WH-001"))
                .thenReturn(List.of(managerRow("WH-001", 7L)));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> warehouseService.requireWarehouseManagedBy("WH-001", 9L));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        assertTrue(ex.getReason().contains("WH-001"));
        assertTrue(ex.getReason().contains("张三"));
    }

    private static WarehouseManager managerRow(String warehouseId, long userId) {
        WarehouseManager row = new WarehouseManager();
        row.setWarehouseId(warehouseId);
        row.setUserId(userId);
        return row;
    }

    // ── V412 多人负责人 ────────────────────────────────────────────────────────────

    /** 一仓两人：顺序即主责人（第一位同步进派生列），审计同时列出两人。 */
    @Test
    void upsertWarehouse_bindsMultipleManagers_inSubmittedOrder() {
        Warehouse existing = existingWarehouse();
        when(warehouseRepository.findById("WH-001")).thenReturn(Optional.of(existing));
        when(warehouseRepository.save(any(Warehouse.class))).thenAnswer(inv -> inv.getArgument(0));
        stubAssignableManager(42L);
        stubAssignableManager(43L);

        var dto = warehouseService.upsertWarehouse(
                UPSERT_OPERATOR, "WH-001", "主仓库", "addr", "ACTIVE", List.of(42L, 43L));

        assertEquals(42L, dto.managerUserId(), "主责人必须是提交列表的第一位");
        ArgumentCaptor<WarehouseManager> rows = ArgumentCaptor.forClass(WarehouseManager.class);
        verify(warehouseManagerRepository, times(2)).insert(rows.capture());
        assertEquals(List.of(42L, 43L), rows.getAllValues().stream().map(WarehouseManager::getUserId).toList());
        verify(auditService)
                .appendLog(any(), any(), any(), any(),
                        eq("manager_user_id=[]->[42(补货负责人),43(补货负责人)]"));
    }

    /** 换主责人（集合不变、顺序变）也必须整表重写：差量增删表达不了「把第二位提上来」。 */
    @Test
    void upsertWarehouse_rewritesManagers_whenOrderChanges() {
        Warehouse existing = existingWarehouse();
        existing.setManagerUserId(42L);
        when(warehouseRepository.findById("WH-001")).thenReturn(Optional.of(existing));
        when(warehouseRepository.save(any(Warehouse.class))).thenAnswer(inv -> inv.getArgument(0));
        when(warehouseManagerRepository.listByWarehouseId("WH-001"))
                .thenReturn(List.of(managerRow("WH-001", 42L), managerRow("WH-001", 43L)));
        stubAssignableManager(42L);
        stubAssignableManager(43L);

        var dto = warehouseService.upsertWarehouse(
                UPSERT_OPERATOR, "WH-001", "主仓库", "addr", "ACTIVE", List.of(43L, 42L));

        assertEquals(43L, dto.managerUserId());
        // 两人都已在集合里 ⇒ 顺序调整不该触发任何「新增人」校验（账号后来停用不能挡住排序）
        verify(permissionService, never()).hasPermission(any(), any());
        verify(warehouseManagerRepository).deleteByWarehouseId("WH-001");
        verify(auditService)
                .appendLog(any(), any(), any(), any(),
                        eq("manager_user_id=[42(补货负责人),43(补货负责人)]->[43(补货负责人),42(补货负责人)]"));
    }

    /** 移除一人是撤权、不是授权：不校验被移除者（他账号停用也照样能摘掉）。 */
    @Test
    void upsertWarehouse_removesManager_withoutValidatingRemovedUser() {
        Warehouse existing = existingWarehouse();
        existing.setManagerUserId(42L);
        when(warehouseRepository.findById("WH-001")).thenReturn(Optional.of(existing));
        when(warehouseRepository.save(any(Warehouse.class))).thenAnswer(inv -> inv.getArgument(0));
        when(warehouseManagerRepository.listByWarehouseId("WH-001"))
                .thenReturn(List.of(managerRow("WH-001", 42L), managerRow("WH-001", 43L)));

        var dto = warehouseService.upsertWarehouse(
                UPSERT_OPERATOR, "WH-001", "主仓库", "addr", "ACTIVE", List.of(42L));

        assertEquals(42L, dto.managerUserId());
        // 判据钉在「只有授权路径才会查的权限」：selectById 在回填 DTO 时对被移除者也会查一次
        verify(permissionService, never()).hasPermission(eq(43L), any());
        ArgumentCaptor<WarehouseManager> rows = ArgumentCaptor.forClass(WarehouseManager.class);
        verify(warehouseManagerRepository).insert(rows.capture());
        assertEquals(42L, rows.getValue().getUserId());
    }

    /** 追加一人：只校验新增的 42，已在册的 43 不重复校验。 */
    @Test
    void upsertWarehouse_validatesOnlyNewlyAddedManager() {
        Warehouse existing = existingWarehouse();
        existing.setManagerUserId(43L);
        when(warehouseRepository.findById("WH-001")).thenReturn(Optional.of(existing));
        when(warehouseRepository.save(any(Warehouse.class))).thenAnswer(inv -> inv.getArgument(0));
        when(warehouseManagerRepository.listByWarehouseId("WH-001"))
                .thenReturn(List.of(managerRow("WH-001", 43L)));
        stubAssignableManager(42L);

        warehouseService.upsertWarehouse(
                UPSERT_OPERATOR, "WH-001", "主仓库", "addr", "ACTIVE", List.of(43L, 42L));

        // 43 已在册：不该为它查权限；42 是新增：必须查
        verify(permissionService, never()).hasPermission(eq(43L), any());
        verify(permissionService).hasPermission(eq(42L), any());
    }

    /** 多人里任意一位都能过盘点闸门（单值列时代第二个人会被挡）。 */
    @Test
    void requireWarehouseManagedBy_acceptsAnyManagerInSet() {
        Warehouse existing = existingWarehouse();
        when(warehouseRepository.findById("WH-001")).thenReturn(Optional.of(existing));
        when(warehouseManagerRepository.isManager("WH-001", 9L)).thenReturn(true);

        var ok = warehouseService.requireWarehouseManagedBy("WH-001", 9L);

        assertEquals("WH-001", ok.getWarehouseId());
    }

    /** 第二位负责人单独发起盘点也能过：闸门按集合判，不再只认主责人派名列。 */
    @Test
    void requireWarehouseManagedBy_acceptsSecondManager() {
        Warehouse existing = existingWarehouse();
        existing.setManagerUserId(42L);
        when(warehouseRepository.findById("WH-001")).thenReturn(Optional.of(existing));
        when(warehouseManagerRepository.isManager("WH-001", 43L)).thenReturn(true);

        var ok = warehouseService.requireWarehouseManagedBy("WH-001", 43L);

        assertEquals("WH-001", ok.getWarehouseId(),
                "派名列写的是 42，但 43 在负责人集合里就该放行");
    }

    /** 重复 user 编号（同一人多选两次）+ 脏值（0/null）只落一行，且不影响主责人顺序。 */
    @Test
    void upsertWarehouse_deduplicatesManagerIds() {
        Warehouse existing = existingWarehouse();
        when(warehouseRepository.findById("WH-001")).thenReturn(Optional.of(existing));
        when(warehouseRepository.save(any(Warehouse.class))).thenAnswer(inv -> inv.getArgument(0));
        stubAssignableManager(42L);

        warehouseService.upsertWarehouse(
                // 刻意混入 null / 0：前端多选控件可能回脏值，归一化必须在落库前吃掉
                UPSERT_OPERATOR, "WH-001", "主仓库", "addr", "ACTIVE",
                java.util.Arrays.asList(42L, 0L, 42L, null));

        ArgumentCaptor<WarehouseManager> rows = ArgumentCaptor.forClass(WarehouseManager.class);
        verify(warehouseManagerRepository, times(1)).insert(rows.capture());
        assertEquals(42L, rows.getValue().getUserId());
    }

    private void stubAssignableManager(long userId) {
        UserInfo user = new UserInfo();
        user.setUserId(userId);
        user.setName("补货负责人");
        user.setStatus("ACTIVE");
        when(userInfoRepository.selectById(userId)).thenReturn(user);
        when(permissionService.hasPermission(userId, "merchant:replenishment:request")).thenReturn(true);
    }

    @Test
    void upsertWarehouse_clearsManager_whenZero() {
        Warehouse existing = existingWarehouse();
        existing.setManagerUserId(9L);
        when(warehouseRepository.findById("WH-001")).thenReturn(Optional.of(existing));
        when(warehouseRepository.save(any(Warehouse.class))).thenAnswer(inv -> inv.getArgument(0));
        UserInfo old = new UserInfo();
        old.setUserId(9L);
        old.setName("老王");
        old.setPhoneNumber("13900000001");
        old.setStatus("ACTIVE");
        when(userInfoRepository.selectById(9L)).thenReturn(old);

        when(warehouseManagerRepository.listByWarehouseId("WH-001"))
                .thenReturn(List.of(managerRow("WH-001", 9L)));

        var dto = warehouseService.upsertWarehouse(
                UPSERT_OPERATOR, "WH-001", "主仓库", "addr", "ACTIVE", List.of());

        assertNull(dto.managerUserId());
        // 🔴 清空是「不校验」的那条路（撤权不是授权），因此**留痕是它唯一的兜底**；手机号必须落掩码值，
        // 因为审计页把 detail 原样吐给 ops:audit:list 持有者，读侧只掩码操作人手机。
        verify(auditService)
                .appendLog(
                        UPSERT_OPERATOR,
                        "WAREHOUSE_MANAGER",
                        "WAREHOUSE",
                        "WH-001",
                        "manager_user_id=[9(老王 139****0001)]->[]");
    }

    @Test
    void upsertWarehouse_keepsManager_whenOmitted() {
        Warehouse existing = existingWarehouse();
        existing.setManagerUserId(9L);
        when(warehouseRepository.findById("WH-001")).thenReturn(Optional.of(existing));
        when(warehouseRepository.save(any(Warehouse.class))).thenAnswer(inv -> inv.getArgument(0));

        var dto = warehouseService.upsertWarehouse(UPSERT_OPERATOR, "WH-001", "主仓库", "addr", "ACTIVE", null);

        assertEquals(9L, dto.managerUserId());
        verify(auditService, never()).appendLog(any(), any(), any(), any(), any());
    }

    private static Warehouse existingWarehouse() {
        Warehouse existing = new Warehouse();
        existing.setWarehouseId("WH-001");
        existing.setWarehouseName("主仓库");
        existing.setStatus("ACTIVE");
        existing.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        return existing;
    }

    private static WarehouseInventory inventoryLot(String skuId, String batchNo, int qty, LocalDate expiryDate) {
        WarehouseInventory lot = new WarehouseInventory();
        lot.setWarehouseId(TEST_WAREHOUSE_ID);
        lot.setSkuId(skuId);
        lot.setBatchNo(batchNo);
        lot.setExpiryDate(expiryDate);
        lot.setQuantity(qty);
        return lot;
    }

    // ── cleanupStaleOutbounds「PLANNED+无任务」判据（2026-10-29 历史待定收口）──

    /** 组装 PLANNED 路线 + 其下 DRAFT 出库单（带一条无设备明细，避开「空草稿」判据）；save 后 findById 回读。 */
    private WarehouseOutbound plannedDraftOutbound(long outboundId, long routeId,
                                                   java.util.concurrent.atomic.AtomicReference<WarehouseOutbound> ref) {
        WarehouseOutbound outbound = new WarehouseOutbound();
        outbound.setOutboundId(outboundId);
        outbound.setRouteId(routeId);
        outbound.setWarehouseId(TEST_WAREHOUSE_ID);
        outbound.setStatus("DRAFT");
        when(routeRepository.findAllByOrderByRouteIdAsc()).thenReturn(List.of(plannedRoute(routeId)));
        when(outboundRepository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(outbound));
        when(outboundRepository.save(any(WarehouseOutbound.class))).thenAnswer(inv -> {
            ref.set(inv.getArgument(0));
            return inv.getArgument(0);
        });
        // 取消链路：加锁读取（findByIdForUpdate）与取消后回读（findById）都给同一实体
        when(outboundRepository.findByIdForUpdate(outboundId)).thenReturn(Optional.of(outbound));
        when(outboundRepository.findById(outboundId)).thenAnswer(inv -> Optional.ofNullable(ref.get()));
        // deviceId 留空 ⇒ 取消链路 distinctOutboundDevices 为空 ⇒ 直接整单收口（不打仓/在途）
        WarehouseOutboundLine line = new WarehouseOutboundLine();
        line.setOutboundId(outboundId);
        when(outboundLineRepository.findByOutboundIdOrderByLineIdAsc(outboundId)).thenReturn(List.of(line));
        return outbound;
    }

    private static ReplenishmentRoute plannedRoute(long routeId) {
        ReplenishmentRoute route = new ReplenishmentRoute();
        route.setRouteId(routeId);
        route.setStatus("PLANNED");
        return route;
    }

    @Test
    void cleanupStale_plannedRouteNoTask_cancelled() {
        var ref = new java.util.concurrent.atomic.AtomicReference<WarehouseOutbound>();
        plannedDraftOutbound(501L, 601L, ref);
        // 无任务：outbound_id 与 route_id 两级查空 ⇒ 新判据命中
        when(taskRepository.findByOutboundId(501L)).thenReturn(List.of());
        when(taskRepository.findByRouteId(601L)).thenReturn(List.of());

        var result = warehouseService.cleanupStaleOutbounds(UPSERT_OPERATOR);

        assertEquals(1, result.cancelledPlannedNoTask());
        assertEquals(List.of(501L), result.cancelledOutboundIds());
        assertEquals("CANCELLED", ref.get().getStatus());
        assertEquals("CANCELLED", ref.get().getHandoverStatus());
    }

    @Test
    void cleanupStale_plannedRouteWithTask_kept() {
        var ref = new java.util.concurrent.atomic.AtomicReference<WarehouseOutbound>();
        plannedDraftOutbound(502L, 602L, ref);
        when(taskRepository.findByOutboundId(502L)).thenReturn(List.of());
        // 路线下仍有任务（活跃链路：createOutboundForRoute 同事务回写）⇒ 不得误清
        ReplenishmentTask live = new ReplenishmentTask();
        live.setTaskId(702L);
        live.setRouteId(602L);
        live.setOutboundId(502L);
        live.setStatus("PENDING");
        when(taskRepository.findByRouteId(602L)).thenReturn(List.of(live));

        var result = warehouseService.cleanupStaleOutbounds(UPSERT_OPERATOR);

        assertEquals(0, result.cancelledPlannedNoTask());
        assertTrue(result.cancelledOutboundIds().isEmpty());
        assertEquals(1, result.skipped());
        assertEquals("DRAFT", ref.get() == null ? "DRAFT" : ref.get().getStatus());
    }

    @Test
    void listOutboundsPage_threadsStatusToRepository() {
        // D28-B5：状态筛选下推服务端——service 原样透传（trim/大写在 mapper 内做）
        when(outboundRepository.searchPage("kw", "WH-1", "draft", 0, 20))
                .thenReturn(new Page<>());

        var result = warehouseService.listOutboundsPage("kw", "WH-1", "draft", 0, 20);

        verify(outboundRepository).searchPage("kw", "WH-1", "draft", 0, 20);
        assertEquals(0, result.items().size());
        assertEquals(0, result.total());
    }

    @Test
    void listOutboundsFiltered_usesFindAllFiltered() {
        // D28-B3：服务端 CSV 导出按仓/状态过滤走 findAllFiltered，不再全表 findAll
        when(outboundRepository.findAllFiltered("WH-1", "SHIPPED")).thenReturn(List.of());

        warehouseService.listOutbounds("WH-1", "SHIPPED");

        verify(outboundRepository).findAllFiltered("WH-1", "SHIPPED");
    }
}
