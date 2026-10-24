package com.aicabinet.trade.service;

import com.aicabinet.common.dto.MerchantSelfReplenishLine;
import com.aicabinet.common.dto.MerchantSelfReplenishRequest;
import com.aicabinet.common.dto.ReplenishmentTaskDto;
import com.aicabinet.trade.domain.DeviceInfo;
import com.aicabinet.trade.domain.ReplenishmentRoute;
import com.aicabinet.trade.domain.ReplenishmentTask;
import com.aicabinet.trade.domain.Warehouse;
import com.aicabinet.trade.mapper.DeviceInfoMapper;
import com.aicabinet.trade.mapper.ReplenishmentRouteMapper;
import com.aicabinet.trade.mapper.ReplenishmentTaskMapper;
import com.aicabinet.trade.mapper.SkuCatalogMapper;
import com.aicabinet.trade.support.MerchantPortalGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 补货员自主发起补货（无审批）：仓跟人走的权限锚点 + 出库/任务生成（V346 家族）。 */
class MerchantSelfReplenishTest {

    private PermissionService permissionService;
    private MerchantPortalGuard merchantPortalGuard;
    private ReplenishmentService replenishmentService;
    private WarehouseService warehouseService;
    private DeviceInfoMapper deviceRepository;
    private SkuCatalogMapper skuCatalogRepository;
    private ReplenishmentRouteMapper routeRepository;
    private ReplenishmentTaskMapper taskRepository;
    private MerchantReplenishmentService service;

    @BeforeEach
    void setUp() {
        permissionService = Mockito.mock(PermissionService.class);
        merchantPortalGuard = Mockito.mock(MerchantPortalGuard.class);
        replenishmentService = Mockito.mock(ReplenishmentService.class);
        warehouseService = Mockito.mock(WarehouseService.class);
        deviceRepository = Mockito.mock(DeviceInfoMapper.class);
        skuCatalogRepository = Mockito.mock(SkuCatalogMapper.class);
        routeRepository = Mockito.mock(ReplenishmentRouteMapper.class);
        taskRepository = Mockito.mock(ReplenishmentTaskMapper.class);
        DistributedLockService distributedLockService = Mockito.mock(DistributedLockService.class);
        lenient().when(distributedLockService.tryLock(anyString(), anyLong(), anyLong())).thenReturn(true);
        service = new MerchantReplenishmentService(
                permissionService, Mockito.mock(MerchantFeaturePackService.class), merchantPortalGuard,
                replenishmentService, null, warehouseService,
                Mockito.mock(AdminAuditService.class), deviceRepository, skuCatalogRepository,
                routeRepository, taskRepository, Mockito.mock(FileAttachmentService.class),
                distributedLockService, null, null);
    }

    private MerchantSelfReplenishRequest body() {
        return bodyWithRequestNo(null);
    }

    private MerchantSelfReplenishRequest bodyWithRequestNo(String clientRequestNo) {
        return new MerchantSelfReplenishRequest(
                "CAB-001", null, List.of(new MerchantSelfReplenishLine("4709", 10)), clientRequestNo);
    }

    private MerchantSelfReplenishRequest bodyWithQty(int... qtys) {
        List<MerchantSelfReplenishLine> lines = new java.util.ArrayList<>();
        for (int qty : qtys) {
            lines.add(new MerchantSelfReplenishLine("4709", qty));
        }
        return new MerchantSelfReplenishRequest("CAB-001", null, lines, null);
    }

    private DeviceInfo deviceWithHomeWarehouse(String warehouseId) {
        DeviceInfo device = new DeviceInfo();
        device.setDeviceId("CAB-001");
        device.setDeviceName("测试柜");
        device.setHomeWarehouseId(warehouseId);
        return device;
    }

    @Test
    void selfInitiate_rejectsWhenDeviceHasNoHomeWarehouse() {
        when(deviceRepository.findById("CAB-001")).thenReturn(Optional.of(deviceWithHomeWarehouse(null)));
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> service.selfInitiateTask(9L, body()));
        verify(routeRepository, never()).save(any());
    }

    @Test
    void selfInitiate_rejectsWhenUserIsNotWarehouseManager() {
        when(deviceRepository.findById("CAB-001"))
                .thenReturn(Optional.of(deviceWithHomeWarehouse("WH-001")));
        Mockito.doThrow(new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.FORBIDDEN, "仅分仓负责人可对该仓发起补货"))
                .when(warehouseService).requireWarehouseManagedBy("WH-001", 9L);
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> service.selfInitiateTask(9L, body()));
        verify(routeRepository, never()).save(any());
    }

    @Test
    void selfInitiate_createsRouteTaskAndLinksOutbound() {
        when(deviceRepository.findById("CAB-001"))
                .thenReturn(Optional.of(deviceWithHomeWarehouse("WH-001")));
        when(skuCatalogRepository.findById("4709")).thenReturn(Optional.of(new com.aicabinet.trade.domain.SkuCatalog()));

        ReplenishmentRoute route = new ReplenishmentRoute();
        route.setRouteId(77L);
        when(routeRepository.save(any(ReplenishmentRoute.class))).thenReturn(route);

        ReplenishmentTask task = new ReplenishmentTask();
        task.setTaskId(88L);
        task.setDeviceId("CAB-001");
        when(taskRepository.save(any(ReplenishmentTask.class))).thenReturn(task);

        when(warehouseService.tryCreateOutboundFromLines(eq(77L), eq("CAB-001"), eq(9L),
                any(), eq((String) null), eq("merchant self replenish"))).thenReturn(501L);
        ReplenishmentTaskDto dto = new ReplenishmentTaskDto(88L, 77L, "CAB-001", 9L, "PENDING",
                null, null, null, null, null, null, 501L, null);
        when(replenishmentService.getTaskDto(88L)).thenReturn(dto);

        ReplenishmentTaskDto result = service.selfInitiateTask(9L, body());

        assertEquals(88L, result.taskId());
        verify(taskRepository).save(Mockito.argThat(t -> t.getOutboundId() != null && t.getOutboundId() == 501L));
        verify(replenishmentService).generateLinesFromOutbound(501L);
        verify(replenishmentService, never()).seedDraftRestockLines(anyLong(), anyString(), any());
    }

    /** 接口层审计（2026-10-20）H2：超时重试去重——窗口内同设备同用户已有 PENDING 任务 ⇒ 幂等返回原任务，不再出库。 */
    @Test
    void selfInitiate_dedupesRecentDuplicateWithinWindow() {
        when(deviceRepository.findById("CAB-001"))
                .thenReturn(Optional.of(deviceWithHomeWarehouse("WH-001")));
        ReplenishmentTask existing = new ReplenishmentTask();
        existing.setTaskId(66L);
        existing.setDeviceId("CAB-001");
        existing.setStatus("PENDING");
        existing.setNotes("self-initiated by user 9");
        when(taskRepository.findByDeviceIdAndNotesAndCreatedAtSince(
                        eq("CAB-001"), eq("self-initiated by user 9"), any()))
                .thenReturn(List.of(existing));
        ReplenishmentTaskDto dto = new ReplenishmentTaskDto(66L, null, "CAB-001", 9L, "PENDING",
                null, null, null, null, null, null, null, null);
        when(replenishmentService.getTaskDto(66L)).thenReturn(dto);

        ReplenishmentTaskDto result = service.selfInitiateTask(9L, body());

        assertEquals(66L, result.taskId());
        verify(routeRepository, never()).save(any());
        verify(taskRepository, never()).save(any());
        verify(warehouseService, never())
                .tryCreateOutboundFromLines(any(), anyString(), any(), any(), any(), anyString());
    }

    /** 去重只认 PENDING：窗口内的已完成/取消任务不算重复，仍正常创建。 */
    @Test
    void selfInitiate_doesNotDedupeWhenRecentTaskNotPending() {
        when(deviceRepository.findById("CAB-001"))
                .thenReturn(Optional.of(deviceWithHomeWarehouse("WH-001")));
        when(skuCatalogRepository.findById("4709")).thenReturn(Optional.of(new com.aicabinet.trade.domain.SkuCatalog()));
        ReplenishmentTask done = new ReplenishmentTask();
        done.setTaskId(65L);
        done.setStatus("COMPLETED");
        done.setNotes("self-initiated by user 9");
        when(taskRepository.findByDeviceIdAndNotesAndCreatedAtSince(
                        eq("CAB-001"), eq("self-initiated by user 9"), any()))
                .thenReturn(List.of(done));

        ReplenishmentRoute route = new ReplenishmentRoute();
        route.setRouteId(77L);
        when(routeRepository.save(any(ReplenishmentRoute.class))).thenReturn(route);
        ReplenishmentTask task = new ReplenishmentTask();
        task.setTaskId(88L);
        when(taskRepository.save(any(ReplenishmentTask.class))).thenReturn(task);
        when(warehouseService.tryCreateOutboundFromLines(any(), anyString(), any(), any(), any(), anyString()))
                .thenReturn(null);
        when(replenishmentService.getTaskDto(88L)).thenReturn(
                new ReplenishmentTaskDto(88L, 77L, "CAB-001", 9L, "PENDING",
                        null, null, null, null, null, null, null, null));

        ReplenishmentTaskDto result = service.selfInitiateTask(9L, body());

        assertEquals(88L, result.taskId());
        verify(taskRepository).save(any());
    }

    /** AU-090：单行数量上界（修复前仅判 qty>0，无上限）。 */
    @Test
    void selfInitiate_rejectsLineAbovePerLineCap() {
        when(deviceRepository.findById("CAB-001"))
                .thenReturn(Optional.of(deviceWithHomeWarehouse("WH-001")));

        org.springframework.web.server.ResponseStatusException ex =
                assertThrows(org.springframework.web.server.ResponseStatusException.class,
                        () -> service.selfInitiateTask(9L, bodyWithQty(1001)));

        assertEquals(org.springframework.http.HttpStatus.BAD_REQUEST, ex.getStatusCode());
        verify(routeRepository, never()).save(any());
        verify(taskRepository, never()).save(any());
    }

    /** AU-090：同 SKU 聚合越界必须在建路线/任务/出库之前拦截（修复前 Integer::sum 可溢出为负量直贯仓库）。 */
    @Test
    void selfInitiate_rejectsSameSkuAggregateAboveCap() {
        when(deviceRepository.findById("CAB-001"))
                .thenReturn(Optional.of(deviceWithHomeWarehouse("WH-001")));
        when(skuCatalogRepository.findById("4709"))
                .thenReturn(Optional.of(new com.aicabinet.trade.domain.SkuCatalog()));

        org.springframework.web.server.ResponseStatusException ex =
                assertThrows(org.springframework.web.server.ResponseStatusException.class,
                        () -> service.selfInitiateTask(9L, bodyWithQty(900, 900, 900, 900, 900, 900)));

        assertEquals(org.springframework.http.HttpStatus.BAD_REQUEST, ex.getStatusCode());
        verify(routeRepository, never()).save(any());
        verify(warehouseService, never())
                .tryCreateOutboundFromLines(any(), anyString(), any(), any(), any(), anyString());
    }

    /** AU-090：上限内的同 SKU 多行正常聚合（边界 4×1000=4000 ≤ 5000）。 */
    @Test
    void selfInitiate_aggregatesSameSkuWithinCap() {
        when(deviceRepository.findById("CAB-001"))
                .thenReturn(Optional.of(deviceWithHomeWarehouse("WH-001")));
        when(skuCatalogRepository.findById("4709"))
                .thenReturn(Optional.of(new com.aicabinet.trade.domain.SkuCatalog()));

        ReplenishmentRoute route = new ReplenishmentRoute();
        route.setRouteId(77L);
        when(routeRepository.save(any(ReplenishmentRoute.class))).thenReturn(route);
        ReplenishmentTask task = new ReplenishmentTask();
        task.setTaskId(88L);
        when(taskRepository.save(any(ReplenishmentTask.class))).thenReturn(task);
        when(warehouseService.tryCreateOutboundFromLines(any(), anyString(), any(), any(), any(), anyString()))
                .thenReturn(null);

        service.selfInitiateTask(9L, bodyWithQty(1000, 1000, 1000, 1000));

        verify(replenishmentService).seedDraftRestockLines(88L, "CAB-001", Map.of("4709", 4000));
    }

    // ===== V401 clientRequestNo 幂等契约（2026-10-23 审计残留 H2 收口）=====

    private void stubHappyPath() {
        when(deviceRepository.findById("CAB-001"))
                .thenReturn(Optional.of(deviceWithHomeWarehouse("WH-001")));
        when(skuCatalogRepository.findById("4709")).thenReturn(Optional.of(new com.aicabinet.trade.domain.SkuCatalog()));
        ReplenishmentRoute route = new ReplenishmentRoute();
        route.setRouteId(77L);
        when(routeRepository.save(any(ReplenishmentRoute.class))).thenReturn(route);
        ReplenishmentTask task = new ReplenishmentTask();
        task.setTaskId(88L);
        when(taskRepository.save(any(ReplenishmentTask.class))).thenReturn(task);
        when(warehouseService.tryCreateOutboundFromLines(any(), anyString(), any(), any(), any(), anyString()))
                .thenReturn(null);
        when(replenishmentService.getTaskDto(88L)).thenReturn(
                new ReplenishmentTaskDto(88L, 77L, "CAB-001", 9L, "PENDING",
                        null, null, null, null, null, null, null, null));
    }

    /** 同 clientRequestNo 重放 ⇒ 幂等返回原任务，且**不走** 90s 短窗（窗口判据区分不了两次合法提交）。 */
    @Test
    void selfInitiate_replaysSameClientRequestNo() {
        when(deviceRepository.findById("CAB-001"))
                .thenReturn(Optional.of(deviceWithHomeWarehouse("WH-001")));
        ReplenishmentTask original = new ReplenishmentTask();
        original.setTaskId(66L);
        original.setDeviceId("CAB-001");
        original.setStatus("COMPLETED");
        when(taskRepository.findByClientRequestNo("req-abc")).thenReturn(Optional.of(original));
        when(replenishmentService.getTaskDto(66L)).thenReturn(
                new ReplenishmentTaskDto(66L, null, "CAB-001", 9L, "COMPLETED",
                        null, null, null, null, null, null, null, null));

        ReplenishmentTaskDto result = service.selfInitiateTask(9L, bodyWithRequestNo("req-abc"));

        assertEquals(66L, result.taskId());
        verify(taskRepository, never()).save(any());
        verify(routeRepository, never()).save(any());
        // 带键请求不得吃短窗判据（负向对照：键路径与窗口路径互斥）
        verify(taskRepository, never())
                .findByDeviceIdAndNotesAndCreatedAtSince(anyString(), anyString(), any());
    }

    /** 🔴 负向对照：带键请求**不得**被 90s 短窗误判——窗口内有 PENDING 也照样创建新任务。 */
    @Test
    void selfInitiate_withClientRequestNo_bypassesLegacyWindowDedup() {
        stubHappyPath();
        when(taskRepository.findByClientRequestNo("req-new")).thenReturn(Optional.empty());
        ReplenishmentTask pending = new ReplenishmentTask();
        pending.setTaskId(65L);
        pending.setStatus("PENDING");
        pending.setNotes("self-initiated by user 9");
        when(taskRepository.findByDeviceIdAndNotesAndCreatedAtSince(
                        eq("CAB-001"), eq("self-initiated by user 9"), any()))
                .thenReturn(List.of(pending));

        ReplenishmentTaskDto result = service.selfInitiateTask(9L, bodyWithRequestNo("req-new"));

        assertEquals(88L, result.taskId());
        verify(taskRepository).save(Mockito.argThat(t -> "req-new".equals(t.getClientRequestNo())));
    }

    /** 键被其他设备占用 ⇒ 409 拒绝（防误出库 + 防越权读他人任务）。 */
    @Test
    void selfInitiate_rejectsClientRequestNoOwnedByOtherDevice() {
        when(deviceRepository.findById("CAB-001"))
                .thenReturn(Optional.of(deviceWithHomeWarehouse("WH-001")));
        ReplenishmentTask other = new ReplenishmentTask();
        other.setTaskId(66L);
        other.setDeviceId("CAB-999");
        when(taskRepository.findByClientRequestNo("req-stolen")).thenReturn(Optional.of(other));

        org.springframework.web.server.ResponseStatusException ex =
                assertThrows(org.springframework.web.server.ResponseStatusException.class,
                        () -> service.selfInitiateTask(9L, bodyWithRequestNo("req-stolen")));

        assertEquals(org.springframework.http.HttpStatus.CONFLICT, ex.getStatusCode());
        verify(taskRepository, never()).save(any());
    }

    /** 键超长 ⇒ 400（列宽 64，防 DB 截断破坏唯一性语义）。 */
    @Test
    void selfInitiate_rejectsOverlongClientRequestNo() {
        when(deviceRepository.findById("CAB-001"))
                .thenReturn(Optional.of(deviceWithHomeWarehouse("WH-001")));

        org.springframework.web.server.ResponseStatusException ex =
                assertThrows(org.springframework.web.server.ResponseStatusException.class,
                        () -> service.selfInitiateTask(9L, bodyWithRequestNo("x".repeat(65))));

        assertEquals(org.springframework.http.HttpStatus.BAD_REQUEST, ex.getStatusCode());
        verify(taskRepository, never()).save(any());
    }

    /** 空白键视为未传：回退 90s 短窗（旧客户端兼容），且不写入键。 */
    @Test
    void selfInitiate_blankClientRequestNo_fallsBackToWindowDedup() {
        when(deviceRepository.findById("CAB-001"))
                .thenReturn(Optional.of(deviceWithHomeWarehouse("WH-001")));
        ReplenishmentTask existing = new ReplenishmentTask();
        existing.setTaskId(66L);
        existing.setStatus("PENDING");
        existing.setNotes("self-initiated by user 9");
        when(taskRepository.findByDeviceIdAndNotesAndCreatedAtSince(
                        eq("CAB-001"), eq("self-initiated by user 9"), any()))
                .thenReturn(List.of(existing));
        when(replenishmentService.getTaskDto(66L)).thenReturn(
                new ReplenishmentTaskDto(66L, null, "CAB-001", 9L, "PENDING",
                        null, null, null, null, null, null, null, null));

        ReplenishmentTaskDto result = service.selfInitiateTask(9L, bodyWithRequestNo("   "));

        assertEquals(66L, result.taskId());
        verify(taskRepository, never()).save(any());
        verify(taskRepository, never()).findByClientRequestNo(anyString());
    }
}
