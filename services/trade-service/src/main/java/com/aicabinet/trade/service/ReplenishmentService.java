package com.aicabinet.trade.service;



import com.aicabinet.common.dto.*;
import com.aicabinet.trade.support.ApiMessages;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aicabinet.trade.domain.DeviceInfo;
import com.aicabinet.trade.domain.DeviceSkuInventory;

import com.aicabinet.trade.domain.DeviceSkuInventoryId;

import com.aicabinet.trade.domain.DeviceSkuLot;

import com.aicabinet.trade.domain.MerchantReplenishmentRequestLine;

import com.aicabinet.trade.domain.MerchantReplenishmentRequest;

import com.aicabinet.trade.domain.PullOffTask;

import com.aicabinet.trade.domain.ReplenishmentRoute;

import com.aicabinet.trade.domain.ReplenishmentTask;

import com.aicabinet.trade.domain.ReplenishmentTaskLine;

import com.aicabinet.trade.domain.WarehouseOutboundLine;

import com.aicabinet.trade.mapper.DeviceInfoMapper;
import com.aicabinet.trade.mapper.DeviceSkuInventoryMapper;

import com.aicabinet.trade.mapper.DeviceSkuLotMapper;

import com.aicabinet.trade.mapper.MerchantReplenishmentRequestLineMapper;

import com.aicabinet.trade.mapper.MerchantReplenishmentRequestMapper;

import com.aicabinet.trade.mapper.PullOffTaskMapper;

import com.aicabinet.trade.mapper.ReplenishmentRouteMapper;

import com.aicabinet.trade.mapper.ReplenishmentTaskLineMapper;

import com.aicabinet.trade.mapper.ReplenishmentTaskMapper;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import org.springframework.http.HttpStatus;

import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import org.springframework.transaction.annotation.Transactional;

import org.springframework.web.server.ResponseStatusException;



import java.time.Instant;

import java.time.LocalDate;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;

import java.util.Map;



@Service

public class ReplenishmentService {
    private static final String STATUS_IN_PROGRESS = "IN_PROGRESS";
    private static final String STATUS_COMPLETED = "COMPLETED";
    private static final String STATUS_CANCELLED = "CANCELLED";
    private static final String RESTOCK = "RESTOCK";
    private static final String STATUS_PENDING = "PENDING";
    private static final String PLANNED = "PLANNED";


    private static final Logger log = LoggerFactory.getLogger(ReplenishmentService.class);

    private final DeviceSkuInventoryMapper inventoryRepository;
    /** 经 Spring 代理调用本类 @Transactional 方法，避免自调用失效。 */
    private final ReplenishmentService self;

    private final ReplenishmentRouteMapper routeRepository;

    private final ReplenishmentTaskMapper taskRepository;

    private final ReplenishmentTaskLineMapper taskLineRepository;

    private final RoutePlanningService routePlanningService;

    private final InventoryLotService inventoryLotService;

    private final DeviceSkuLotMapper lotRepository;

    private final PullOffTaskMapper pullOffTaskRepository;

    private final ObjectMapper objectMapper;

    private final WarehouseService warehouseService;

    private final DeviceInfoMapper deviceRepository;

    private final DeviceSlotService deviceSlotService;

    private final InTransitService inTransitService;

    private final SessionService sessionService;

    private final MerchantReplenishmentRequestMapper merchantRequestRepository;

    private final MerchantReplenishmentRequestLineMapper merchantRequestLineRepository;
    private final NotificationService notificationService;
    private final DistributedLockService distributedLockService;



    public ReplenishmentService(DeviceSkuInventoryMapper inventoryRepository,

                                ReplenishmentRouteMapper routeRepository,

                                ReplenishmentTaskMapper taskRepository,

                                ReplenishmentTaskLineMapper taskLineRepository,

                                RoutePlanningService routePlanningService,

                                InventoryLotService inventoryLotService,

                                DeviceSkuLotMapper lotRepository,

                                PullOffTaskMapper pullOffTaskRepository,

                                ObjectMapper objectMapper,

                                WarehouseService warehouseService,
                                DeviceInfoMapper deviceRepository,
                                DeviceSlotService deviceSlotService,
                                InTransitService inTransitService,
                                @org.springframework.context.annotation.Lazy SessionService sessionService,
                                MerchantReplenishmentRequestMapper merchantRequestRepository,
                                MerchantReplenishmentRequestLineMapper merchantRequestLineRepository,
                                NotificationService notificationService,
                                DistributedLockService distributedLockService, @Lazy ReplenishmentService self) {

        this.inventoryRepository = inventoryRepository;

        this.routeRepository = routeRepository;

        this.taskRepository = taskRepository;

        this.taskLineRepository = taskLineRepository;

        this.routePlanningService = routePlanningService;

        this.inventoryLotService = inventoryLotService;

        this.lotRepository = lotRepository;

        this.pullOffTaskRepository = pullOffTaskRepository;

        this.objectMapper = objectMapper;

        this.warehouseService = warehouseService;
        this.deviceRepository = deviceRepository;
        this.deviceSlotService = deviceSlotService;
        this.inTransitService = inTransitService;
        this.sessionService = sessionService;
        this.merchantRequestRepository = merchantRequestRepository;
        this.merchantRequestLineRepository = merchantRequestLineRepository;
        this.notificationService = notificationService;
        this.distributedLockService = distributedLockService;

        this.self = self;
    }



    @Transactional(readOnly = true)

    public List<DeviceInventoryDto> listInventory(String deviceId, boolean lowStockOnly) {

        List<DeviceSkuInventory> rows;

        if (deviceId != null && !deviceId.isBlank()) {
            String dev = deviceId.trim();
            rows = inventoryRepository.findByIdDeviceId(dev);
        } else if (lowStockOnly) {
            rows = inventoryRepository.findLowStockLimit(500);
        } else {
            rows = inventoryRepository.findAllLimit(2000);
        }

        Map<String, Map<String, Integer>> sellableByDevice = new java.util.HashMap<>();
        Map<String, Boolean> ledgerByDevice = new java.util.HashMap<>();
        List<DeviceInventoryDto> dtos = rows.stream()
                .map(inv -> toInventoryDto(inv, sellableByDevice, ledgerByDevice))
                .toList();
        if (lowStockOnly) {
            return dtos.stream()
                    .filter(d -> d.quantity() <= d.lowThreshold())
                    .toList();
        }
        return dtos;

    }



    @Transactional

    public DeviceInventoryDto upsertInventory(Long operatorId, DeviceInventoryDto body) {

        DeviceSkuInventoryId id = new DeviceSkuInventoryId(body.deviceId(), body.skuId());

        DeviceSkuInventory inv = inventoryRepository.findById(id).orElseGet(() -> {

            DeviceSkuInventory n = new DeviceSkuInventory();

            n.setId(id);

            return n;

        });

        // 有批次账本时 quantity 只能由 lot 汇总同步，禁止手改汇总表造成虚库存
        if (inventoryLotService.deviceUsesLotLedger(body.deviceId())) {
            inventoryLotService.syncAggregateInventory(body.deviceId(), body.skuId());
            inv = inventoryRepository.findById(id).orElse(inv);
        } else {
            inv.setQuantity(body.quantity());
        }

        inv.setCapacity(body.capacity());

        inv.setLowThreshold(body.lowThreshold());

        return toInventoryDto(inventoryRepository.save(inv));

    }



    @Transactional(readOnly = true)

    public List<ReplenishmentRouteDto> listRoutes() {

        return routeRepository.findAllByOrderByRouteIdAsc().stream()

                .map(this::toRouteDto)

                .toList();

    }

    @Transactional(readOnly = true)
    public PageResult<ReplenishmentRouteDto> listRoutesPage(String deviceId, int page, int size) {
        int p = Math.max(page, 0);
        int s = Math.min(Math.max(size, 1), 100);
        java.util.Collection<Long> routeIds = null;
        if (deviceId != null && !deviceId.isBlank()) {
            routeIds = taskRepository.findDistinctRouteIdsByDeviceId(deviceId.trim());
            if (routeIds.isEmpty()) {
                return new PageResult<>(List.of(), p, s, 0);
            }
        }
        var result = routeRepository.searchPage(routeIds, p, s);
        List<ReplenishmentRouteDto> items = result.getRecords().stream().map(this::toRouteDto).toList();
        return new PageResult<>(items, p, s, result.getTotal());
    }

    @Transactional(readOnly = true)
    public PageResult<PullOffTaskDto> listOpenPullOffTasksPage(int page, int size) {
        int p = Math.max(page, 0);
        int s = Math.min(Math.max(size, 1), 100);
        var result = pullOffTaskRepository.searchPage("OPEN", p, s);
        List<PullOffTaskDto> items = result.getRecords().stream().map(this::toPullOffDto).toList();
        return new PageResult<>(items, p, s, result.getTotal());
    }

    @Transactional(readOnly = true)
    public ReplenishmentShortagePageDto listShortagePage(
            Long operatorId, String deviceId, int page, int size) {
        List<ReplenishmentShortageRowDto> all = deviceSlotService.listShortageRows(operatorId, deviceId);
        int p = Math.max(page, 0);
        int s = Math.min(Math.max(size, 1), 100);
        int from = Math.min(p * s, all.size());
        int to = Math.min(from + s, all.size());
        List<ReplenishmentShortageRowDto> items = all.subList(from, to);
        List<String> deviceIds = all.stream()
                .map(ReplenishmentShortageRowDto::deviceId)
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .sorted()
                .toList();
        return new ReplenishmentShortagePageDto(items, p, s, all.size(), deviceIds);
    }

    @Transactional(readOnly = true)
    public PageResult<ReplenishmentFulfillmentTaskDto> listFulfillmentTasksPage(
            String deviceId, String status, int page, int size) {
        int p = Math.max(page, 0);
        int s = Math.min(Math.max(size, 1), 100);
        var result = taskRepository.searchPage(deviceId, status, p, s);
        Map<Long, ReplenishmentRoute> routeById = new HashMap<>();
        for (ReplenishmentTask task : result.getRecords()) {
            Long routeId = task.getRouteId();
            if (routeId != null && !routeById.containsKey(routeId)) {
                routeRepository.findById(routeId).ifPresent(r -> routeById.put(routeId, r));
            }
        }
        List<ReplenishmentFulfillmentTaskDto> items = result.getRecords().stream()
                .map(task -> toFulfillmentTaskDto(task, routeById.get(task.getRouteId())))
                .toList();
        return new PageResult<>(items, p, s, result.getTotal());
    }

    @Transactional(readOnly = true)
    public ReplenishmentOpsSummaryDto opsSummary() {
        long pending = taskRepository.countByStatusIn(List.of(STATUS_PENDING, STATUS_IN_PROGRESS));
        long fulfilled = taskRepository.countByStatusIn(List.of(STATUS_COMPLETED));
        Long planned = routeRepository.selectCount(Wrappers.<ReplenishmentRoute>lambdaQuery()
                .in(ReplenishmentRoute::getStatus, PLANNED, STATUS_IN_PROGRESS));
        long plannedRoutes = planned == null ? 0 : planned;
        Long pendingReq = merchantRequestRepository.selectCount(
                Wrappers.<MerchantReplenishmentRequest>lambdaQuery()
                        .eq(MerchantReplenishmentRequest::getStatus, "SUBMITTED"));
        long pendingRequests = pendingReq == null ? 0 : pendingReq;
        return new ReplenishmentOpsSummaryDto(pending, fulfilled, plannedRoutes, pendingRequests);
    }



    @Transactional

    public ReplenishmentRouteDto planAndCreateRoute(Long operatorId, PlanRouteRequest request) {

        assertHasReplenishmentGap(request);

        RoutePlanningService.PlannedRoute planned = routePlanningService.plan(request);



        ReplenishmentRoute route = new ReplenishmentRoute();

        route.setRouteName(request.routeName() != null ? request.routeName() : "补货路线");

        route.setAssigneeUserId(request.assigneeUserId());

        route.setPlannedDate(request.plannedDate() != null ? request.plannedDate() : LocalDate.now());

        route.setStatus(PLANNED);

        route.setTotalDistanceM(planned.totalDistanceM());

        route.setStartLatitude(request.startLatitude());

        route.setStartLongitude(request.startLongitude());

        try {

            route.setRouteGeoJson(objectMapper.writeValueAsString(planned.waypoints()));

        } catch (Exception ignored) {

        }

        route = routeRepository.save(route);



        for (RouteWaypointDto wp : planned.waypoints()) {

            ReplenishmentTask task = new ReplenishmentTask();

            task.setRouteId(route.getRouteId());

            task.setDeviceId(wp.deviceId());

            task.setAssigneeUserId(route.getAssigneeUserId());

            task.setStatus(STATUS_PENDING);

            task.setNotes("seq=" + wp.sequence() + " dist=" + wp.distanceFromPrevM() + "m");

            taskRepository.save(task);
            notifyTaskAssigned(task);

        }

        // 同事务生成出库单：失败则整单回滚，避免吞异常导致 UnexpectedRollback→500 或半成功路线
        warehouseService.createOutboundForRoute(route.getRouteId(), null, route.getAssigneeUserId());

        return toRouteDto(route);

    }

    /** 满柜/无缺口时直接 400，避免半成功路线 + 事务回滚 500。 */
    private void assertHasReplenishmentGap(PlanRouteRequest request) {
        List<String> deviceIds = request.deviceIds();
        if (deviceIds == null || deviceIds.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请至少选择一台设备");
        }
        boolean anyGap = false;
        for (String deviceId : deviceIds) {
            if (deviceId != null && !deviceId.isBlank()) {
                List<ReplenishmentSuggestDto> suggestions = warehouseService.suggestForDevice(deviceId.trim(), true);
                if (suggestions.stream().anyMatch(s -> s.suggestQty() > 0)) {
                    anyGap = true;
                    break;
                }
            }
        }
        if (!anyGap) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ApiMessages.REPLENISHMENT_NO_GAP);
        }
    }



    @Transactional(readOnly = true)
    public List<ReplenishmentSuggestDto> suggestForDevice(String deviceId) {
        return warehouseService.suggestForDevice(deviceId);
    }

    @Transactional(readOnly = true)
    public List<SlotReplenishmentSuggestDto> suggestSlotsForDevice(String deviceId) {
        return deviceSlotService.suggestSlotsForDevice(deviceId);
    }

    /**
     * 历史空任务回填：关联要货单且尚无任何任务行时，按要货数量 seed。
     */
    @Transactional
    public int ensureSeededFromLinkedRequest(Long taskId) {
        if (taskId == null) {
            return 0;
        }
        ReplenishmentTask task = taskRepository.findById(taskId).orElse(null);
        if (task == null || task.getRequestId() == null) {
            return 0;
        }
        if (!taskLineRepository.findByTaskIdOrderByLineIdAsc(taskId).isEmpty()) {
            return 0;
        }
        Map<String, Integer> skuQty = new LinkedHashMap<>();
        for (MerchantReplenishmentRequestLine line : merchantRequestLineRepository
                .findByRequestIdOrderByLineIdAsc(task.getRequestId())) {
            if (line.getSkuId() == null || line.getSkuId().isBlank()) {
                continue;
            }
            skuQty.merge(line.getSkuId(), Math.max(0, line.getRequestedQty()), Integer::sum);
        }
        return self.seedDraftRestockLines(taskId, task.getDeviceId(), skuQty);
    }

    /**
     * 无仓配出库时：按要货 SKU 数量生成现场 RESTOCK 行（有货道容量则分配 slot，否则仍落无货道行便于商户执行）。
     * 已有任务行时跳过，避免覆盖人工录入。
     */
    @Transactional
    public int seedDraftRestockLines(Long taskId, String deviceId, Map<String, Integer> skuQty) {
        if (taskId == null || deviceId == null || skuQty == null || skuQty.isEmpty()) {
            return 0;
        }
        if (!taskLineRepository.findByTaskIdOrderByLineIdAsc(taskId).isEmpty()) {
            return 0;
        }
        int created = 0;
        for (Map.Entry<String, Integer> entry : skuQty.entrySet()) {
            String skuId = entry.getKey();
            int need = entry.getValue() == null ? 0 : Math.max(0, entry.getValue());
            if (skuId == null || skuId.isBlank() || need <= 0) {
                continue;
            }
            List<DeviceSlotService.SlotRestockAllocation> allocations =
                    deviceSlotService.allocateRestockQuantity(deviceId, skuId, need);
            int allocated = 0;
            for (DeviceSlotService.SlotRestockAllocation alloc : allocations) {
                ReplenishmentTaskLine line = new ReplenishmentTaskLine();
                line.setTaskId(taskId);
                line.setLineType(RESTOCK);
                line.setSkuId(skuId);
                line.setQuantity(alloc.quantity());
                line.setSlotId(alloc.slotCode());
                line.setApplied(false);
                taskLineRepository.save(line);
                allocated += alloc.quantity();
                created++;
            }
            int remain = need - allocated;
            if (remain > 0) {
                ReplenishmentTaskLine line = new ReplenishmentTaskLine();
                line.setTaskId(taskId);
                line.setLineType(RESTOCK);
                line.setSkuId(skuId);
                line.setQuantity(remain);
                line.setApplied(false);
                taskLineRepository.save(line);
                created++;
                log.warn("seedDraftRestockLines: {} units without slot headroom task={} sku={}",
                        remain, taskId, skuId);
            }
        }
        return created;
    }

    /** 出库发运后：按出库行自动生成补货任务行（不覆盖已录入的未应用行）。 */
    @Transactional
    public void generateLinesFromOutbound(Long outboundId) {
        for (ReplenishmentTask task : taskRepository.findByOutboundId(outboundId)) {
            generateLinesForTask(task, outboundId);
        }
    }

    private void generateLinesForTask(ReplenishmentTask task, Long outboundId) {
        if (STATUS_COMPLETED.equals(task.getStatus())) {
            return;
        }
        if (!taskLineRepository.findByTaskIdAndAppliedFalse(task.getTaskId()).isEmpty()) {
            return;
        }
        List<WarehouseOutboundLine> outboundLines = warehouseService
                .outboundLinesForDevice(outboundId, task.getDeviceId());
        if (outboundLines.isEmpty()) {
            return;
        }
        for (WarehouseOutboundLine ol : outboundLines) {
            saveRestockLinesFromOutbound(task, ol);
        }
        if (!STATUS_IN_PROGRESS.equals(task.getStatus())) {
            task.setStatus(STATUS_IN_PROGRESS);
            taskRepository.save(task);
        }
    }

    private void saveRestockLinesFromOutbound(ReplenishmentTask task, WarehouseOutboundLine ol) {
        List<DeviceSlotService.SlotRestockAllocation> allocations =
                resolveOutboundSlotAllocations(task.getDeviceId(), ol);
        int allocated = 0;
        for (DeviceSlotService.SlotRestockAllocation alloc : allocations) {
            ReplenishmentTaskLine line = new ReplenishmentTaskLine();
            line.setTaskId(task.getTaskId());
            line.setLineType(RESTOCK);
            line.setSkuId(ol.getSkuId());
            line.setBatchNo(ol.getBatchNo());
            line.setExpiryDate(ol.getExpiryDate());
            line.setQuantity(alloc.quantity());
            line.setSlotId(alloc.slotCode());
            line.setApplied(false);
            taskLineRepository.save(line);
            allocated += alloc.quantity();
        }
        int remain = Math.max(0, ol.getQuantity() - allocated);
        if (remain > 0) {
            log.warn("generateLinesFromOutbound: truncated {} units over capacity task={} sku={} slot={} allocated={}",
                    remain, task.getTaskId(), ol.getSkuId(), ol.getSlotId(), allocated);
        }
    }

    @Transactional
    public ReplenishmentTaskDto checkInTask(Long operatorId, Long taskId, ReplenishmentCheckInRequest request) {
        return runWithTaskLock(taskId, () -> doCheckInTask(taskId, request));
    }

    private ReplenishmentTaskDto doCheckInTask(Long taskId, ReplenishmentCheckInRequest request) {
        ReplenishmentTask task = requireTaskForUpdate(taskId);
        if (request != null && request.latitude() != null && request.longitude() != null) {
            validateCheckInLocation(task.getDeviceId(), request.latitude(), request.longitude());
            task.setCheckInLat(request.latitude());
            task.setCheckInLng(request.longitude());
        }
        task.setCheckInAt(Instant.now());
        if (!STATUS_IN_PROGRESS.equals(task.getStatus()) && !STATUS_COMPLETED.equals(task.getStatus())) {
            task.setStatus(STATUS_IN_PROGRESS);
        }
        task = taskRepository.save(task);
        reopenRouteIfActive(task.getRouteId());
        return toTaskDto(task);
    }

    private void validateCheckInLocation(String deviceId, double lat, double lng) {
        DeviceInfo device = deviceRepository.findById(deviceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.DEVICE_NOT_FOUND));
        if (device.getLatitude() == null || device.getLongitude() == null) {
            return;
        }
        double distM = haversineMeters(device.getLatitude(), device.getLongitude(), lat, lng);
        if (distM > 500) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    String.format(ApiMessages.REPLENISHMENT_CHECK_IN_TOO_FAR, Math.round(distM)));
        }
    }

    private static double haversineMeters(double lat1, double lon1, double lat2, double lon2) {
        double r = 6371000;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return r * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    @Transactional
    public ReplenishmentRouteDto createRoute(Long operatorId, ReplenishmentRouteDto body) {

        ReplenishmentRoute route = new ReplenishmentRoute();

        route.setRouteName(body.routeName());

        route.setAssigneeUserId(body.assigneeUserId());

        route.setPlannedDate(body.plannedDate() != null ? body.plannedDate() : LocalDate.now());

        route.setStatus(PLANNED);

        route = routeRepository.save(route);



        if (body.tasks() != null) {

            for (ReplenishmentTaskDto t : body.tasks()) {

                ReplenishmentTask task = new ReplenishmentTask();

                task.setRouteId(route.getRouteId());

                task.setDeviceId(t.deviceId());

                task.setAssigneeUserId(t.assigneeUserId() != null ? t.assigneeUserId() : route.getAssigneeUserId());

                task.setStatus(STATUS_PENDING);

                task.setNotes(t.notes());

                taskRepository.save(task);

            }

        }

        return toRouteDto(route);

    }



    @Transactional

    public List<ReplenishmentTaskLineDto> submitTaskLines(Long operatorId, Long taskId,

                                                          SubmitReplenishmentLinesRequest request) {

        return runWithTaskLock(taskId, () -> doSubmitTaskLines(taskId, request));

    }

    private List<ReplenishmentTaskLineDto> doSubmitTaskLines(Long taskId,
                                                             SubmitReplenishmentLinesRequest request) {
        ReplenishmentTask task = requireTaskForUpdate(taskId);
        if (STATUS_COMPLETED.equals(task.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ApiMessages.REPLENISHMENT_TASK_ALREADY_COMPLETED);
        }
        taskLineRepository.deleteByTaskIdAndAppliedFalse(taskId);
        if (request.lines() != null) {
            for (ReplenishmentTaskLineDto dto : request.lines()) {
                persistSubmittedTaskLine(task, taskId, dto);
            }
        }
        if (!STATUS_IN_PROGRESS.equals(task.getStatus())) {
            task.setStatus(STATUS_IN_PROGRESS);
            taskRepository.save(task);
        }
        reopenRouteIfActive(task.getRouteId());
        return self.listTaskLines(taskId);
    }

    private void persistSubmittedTaskLine(ReplenishmentTask task, Long taskId, ReplenishmentTaskLineDto dto) {
        // 小程序/联调常省略 lineType，默认按上架（RESTOCK）处理
        String lineType = normalizeTaskLineType(dto.lineType());
        if (RESTOCK.equals(lineType)) {
            persistRestockLines(task, taskId, dto);
            return;
        }
        ReplenishmentTaskLine line = new ReplenishmentTaskLine();
        line.setTaskId(taskId);
        line.setLineType(lineType);
        line.setSkuId(dto.skuId());
        line.setBatchNo(dto.batchNo());
        line.setProductionDate(dto.productionDate());
        line.setExpiryDate(dto.expiryDate());
        line.setQuantity(dto.quantity());
        line.setSlotId(dto.slotId() != null && !dto.slotId().isBlank()
                ? dto.slotId().trim().toUpperCase() : null);
        line.setApplied(false);
        taskLineRepository.save(line);
    }

    private static String normalizeTaskLineType(String rawLineType) {
        return (rawLineType == null || rawLineType.isBlank())
                ? RESTOCK
                : rawLineType.trim().toUpperCase();
    }



    @Transactional(readOnly = true)

    public List<ReplenishmentTaskLineDto> listTaskLines(Long taskId) {

        return taskLineRepository.findByTaskIdOrderByLineIdAsc(taskId).stream()

                .map(this::toLineDto)

                .toList();

    }



    @Transactional

    public ReplenishmentTaskDto completeTask(Long operatorId, Long taskId) {

        return runWithTaskLock(taskId, () -> doCompleteTask(operatorId, taskId));

    }

    private ReplenishmentTaskDto doCompleteTask(Long operatorId, Long taskId) {
        ReplenishmentTask task = requireTaskForUpdate(taskId);
        // 未完成任务必须先签到，避免远程误点完成导致库存虚增
        if (!STATUS_COMPLETED.equals(task.getStatus()) && task.getCheckInAt() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    ApiMessages.REPLENISHMENT_COMPLETE_CHECK_IN_REQUIRED);
        }
        if (STATUS_COMPLETED.equals(task.getStatus())) {
            return finalizeAlreadyCompletedTask(task, operatorId, taskId);
        }
        List<ReplenishmentTaskLine> pending = taskLineRepository.findByTaskIdAndAppliedFalse(taskId);
        boolean expectReceive = validateOutboundBeforeComplete(task, pending);
        int appliedRestockQty = applyPendingReplenishmentLines(task, pending, operatorId, String.valueOf(taskId));
        deviceSlotService.clampDeviceOverCapacity(task.getDeviceId());
        task.setStatus(STATUS_COMPLETED);
        task.setCompletedAt(Instant.now());
        if (expectReceive) {
            appliedRestockQty = receiveOutboundHandover(task, operatorId, appliedRestockQty, pending.isEmpty());
        }
        task = taskRepository.save(task);
        return finishCompletedTaskSideEffects(task, taskId);
    }

    /** 幂等重入：已完成后补签收在途并关闭会话。 */
    private ReplenishmentTaskDto finalizeAlreadyCompletedTask(
            ReplenishmentTask task, Long operatorId, Long taskId) {
        // 任务已完成但出库仍在途（先完成任务、后发运的联调顺序）时，补签收；
        // 仅当任务本身无明细时按出库行回写，避免与已上架任务行重复加库存
        if (task.getOutboundId() != null
                && inTransitService.hasOpenForDevice(task.getOutboundId(), task.getDeviceId())) {
            int appliedQty = resolveAppliedQtyForCatchUpReceive(task, operatorId, taskId);
            inTransitService.receiveForDevice(task.getOutboundId(), task.getDeviceId());
            warehouseService.markDeviceHandoverReceived(
                    task.getOutboundId(), task.getDeviceId(), appliedQty);
        }
        return finishCompletedTaskSideEffects(task, taskId);
    }

    private int resolveAppliedQtyForCatchUpReceive(
            ReplenishmentTask task, Long operatorId, Long taskId) {
        List<ReplenishmentTaskLine> existingLines =
                taskLineRepository.findByTaskIdOrderByLineIdAsc(taskId);
        if (existingLines.isEmpty()) {
            return restockFromOutboundLines(task, operatorId, "OB-" + task.getOutboundId());
        }
        return existingLines.stream()
                .filter(l -> RESTOCK.equalsIgnoreCase(l.getLineType()))
                .filter(ReplenishmentTaskLine::isApplied)
                .mapToInt(ReplenishmentTaskLine::getQuantity)
                .sum();
    }

    /**
     * 校验仓配发运状态；返回是否需要在途签收。
     * 有仓配明细却未发运且仍有待上架行时拒绝完成。
     */
    private boolean validateOutboundBeforeComplete(
            ReplenishmentTask task, List<ReplenishmentTaskLine> pending) {
        if (task.getOutboundId() == null) {
            return false;
        }
        boolean inTransit = inTransitService.hasOpenForDevice(task.getOutboundId(), task.getDeviceId());
        boolean hasOutboundLines = warehouseService.hasOutboundLinesForDevice(
                task.getOutboundId(), task.getDeviceId());
        if (!inTransit && hasOutboundLines && !pending.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    ApiMessages.REPLENISHMENT_OUTBOUND_NOT_IN_TRANSIT);
        }
        return inTransit;
    }

    private int applyPendingReplenishmentLines(
            ReplenishmentTask task, List<ReplenishmentTaskLine> pending, Long operatorId, String refId) {
        int appliedRestockQty = 0;
        for (ReplenishmentTaskLine line : pending) {
            inventoryLotService.applyReplenishmentLine(task.getDeviceId(), line, operatorId, refId);
            if (RESTOCK.equalsIgnoreCase(line.getLineType())) {
                deviceSlotService.recordRestock(task.getDeviceId(), line.getSlotId());
                appliedRestockQty += line.getQuantity();
            }
            line.setApplied(true);
            taskLineRepository.save(line);
        }
        return appliedRestockQty;
    }

    /**
     * 仓配在途签收：按出库行上架；若已有现场 RESTOCK 行，则只补 SKU 差额。
     */
    private int receiveOutboundHandover(
            ReplenishmentTask task, Long operatorId, int appliedRestockQty, boolean pendingWasEmpty) {
        Map<String, Integer> appliedBySku = new HashMap<>();
        for (ReplenishmentTaskLine line : taskLineRepository.findByTaskIdOrderByLineIdAsc(task.getTaskId())) {
            if (!RESTOCK.equalsIgnoreCase(line.getLineType()) || !line.isApplied()) {
                continue;
            }
            appliedBySku.merge(line.getSkuId(), line.getQuantity(), Integer::sum);
        }
        int fromOutbound = restockFromOutboundLines(
                task, operatorId, "OB-" + task.getOutboundId(), appliedBySku);
        int totalRestock = pendingWasEmpty ? fromOutbound : appliedRestockQty + fromOutbound;
        inTransitService.receiveForDevice(task.getOutboundId(), task.getDeviceId());
        warehouseService.markDeviceHandoverReceived(
                task.getOutboundId(), task.getDeviceId(), totalRestock);
        return totalRestock;
    }

    private ReplenishmentTaskDto finishCompletedTaskSideEffects(ReplenishmentTask task, Long taskId) {
        completeRouteIfReady(task.getRouteId());
        // 完成补货后关闭仍占用柜机的补货开门会话，避免挡消费者
        if (sessionService != null) {
            sessionService.closeRestockSessionsForTask(taskId, "补货任务已完成，自动关闭开门会话");
        }
        markLinkedRequestCompleted(task);
        return toTaskDto(task);
    }

    /** 关联要货单：任务完成后置 COMPLETED（仅 ACCEPTED → COMPLETED）。 */
    private void markLinkedRequestCompleted(ReplenishmentTask task) {
        if (task == null || task.getRequestId() == null || merchantRequestRepository == null) {
            return;
        }
        merchantRequestRepository.findById(task.getRequestId()).ifPresent(req -> {
            if (!"ACCEPTED".equalsIgnoreCase(req.getStatus())) {
                return;
            }
            req.setStatus(STATUS_COMPLETED);
            merchantRequestRepository.save(req);
        });
    }

    /** 出库行 → 货道分配：优先沿用出库明细货道，并按当前余量截断。 */
    private List<DeviceSlotService.SlotRestockAllocation> resolveOutboundSlotAllocations(
            String deviceId, WarehouseOutboundLine ol) {
        return resolveOutboundSlotAllocations(deviceId, ol, ol.getQuantity());
    }

    private List<DeviceSlotService.SlotRestockAllocation> resolveOutboundSlotAllocations(
            String deviceId, WarehouseOutboundLine ol, int quantity) {
        if (quantity <= 0) {
            return List.of();
        }
        if (ol.getSlotId() != null && !ol.getSlotId().isBlank()) {
            String slot = ol.getSlotId().trim().toUpperCase();
            int room = deviceSlotService.headroomForSlot(deviceId, slot);
            int take = Math.min(quantity, room);
            if (take <= 0) {
                return List.of();
            }
            return List.of(new DeviceSlotService.SlotRestockAllocation(slot, take));
        }
        return deviceSlotService.allocateRestockQuantity(deviceId, ol.getSkuId(), quantity);
    }

    /** 无任务明细时按出库行（含货道/容量）回写柜机，返回实际上架件数。 */
    private int restockFromOutboundLines(ReplenishmentTask task, Long operatorId, String refId) {
        return restockFromOutboundLines(task, operatorId, refId, Map.of());
    }

    /**
     * 按出库行上架；{@code alreadyAppliedBySku} 表示同任务已通过现场行上架的数量，按 SKU 扣减后只补差额。
     */
    private int restockFromOutboundLines(ReplenishmentTask task, Long operatorId, String refId,
                                         Map<String, Integer> alreadyAppliedBySku) {
        Map<String, Integer> remainingCredit = new HashMap<>();
        if (alreadyAppliedBySku != null) {
            remainingCredit.putAll(alreadyAppliedBySku);
        }
        int received = 0;
        for (var ol : warehouseService.outboundLinesForDevice(task.getOutboundId(), task.getDeviceId())) {
            if (ol.getQuantity() <= 0) {
                continue;
            }
            int need = computeOutboundRestockNeed(ol, remainingCredit);
            if (need > 0) {
                received += applyOutboundLineRestock(task, ol, need, operatorId, refId);
            }
        }
        return received;
    }

    private static int computeOutboundRestockNeed(WarehouseOutboundLine ol, Map<String, Integer> remainingCredit) {
        int credit = remainingCredit.getOrDefault(ol.getSkuId(), 0);
        int need = ol.getQuantity() - credit;
        if (credit > 0) {
            remainingCredit.put(ol.getSkuId(), Math.max(0, credit - ol.getQuantity()));
        }
        return need;
    }

    private int applyOutboundLineRestock(ReplenishmentTask task, WarehouseOutboundLine ol, int need,
                                         Long operatorId, String refId) {
        List<DeviceSlotService.SlotRestockAllocation> allocations =
                resolveOutboundSlotAllocations(task.getDeviceId(), ol, need);
        if (allocations.isEmpty()) {
            return restockOutboundLineWithoutSlots(task, ol, need, operatorId, refId);
        }
        return restockOutboundLineWithAllocations(task, ol, allocations, operatorId, refId);
    }

    private int restockOutboundLineWithoutSlots(ReplenishmentTask task, WarehouseOutboundLine ol, int need,
                                                Long operatorId, String refId) {
        if (!deviceSlotService.hasSkuSlots(task.getDeviceId(), ol.getSkuId())) {
            inventoryLotService.addRestock(
                    task.getDeviceId(), ol.getSkuId(),
                    new InventoryLotService.RestockCommand(
                            ol.getBatchNo(), null, ol.getExpiryDate(), need, null,
                            new InventoryLotService.LotMovementRef("REPLENISH", refId, operatorId)));
            return need;
        }
        return 0;
    }

    private int restockOutboundLineWithAllocations(ReplenishmentTask task, WarehouseOutboundLine ol,
                                                   List<DeviceSlotService.SlotRestockAllocation> allocations,
                                                   Long operatorId, String refId) {
        int received = 0;
        for (DeviceSlotService.SlotRestockAllocation alloc : allocations) {
            inventoryLotService.addRestock(
                    task.getDeviceId(), ol.getSkuId(),
                    new InventoryLotService.RestockCommand(
                            ol.getBatchNo(), null, ol.getExpiryDate(), alloc.quantity(),
                            alloc.slotCode(),
                            new InventoryLotService.LotMovementRef("REPLENISH", refId, operatorId)));
            deviceSlotService.recordRestock(task.getDeviceId(), alloc.slotCode());
            received += alloc.quantity();
        }
        return received;
    }

    /** RESTOCK：无货道时按可补容量自动拆分/截断并分配货道，减少手填。 */
    private void persistRestockLines(ReplenishmentTask task, Long taskId, ReplenishmentTaskLineDto dto) {
        int qty = dto.quantity();
        if (qty <= 0) {
            return;
        }
        String slotId = dto.slotId() != null && !dto.slotId().isBlank()
                ? dto.slotId().trim().toUpperCase() : null;
        if (slotId != null) {
            deviceSlotService.validateRestockLine(task.getDeviceId(), slotId, dto.skuId(), qty);
            saveRestockLine(taskId, dto, slotId, qty);
            return;
        }
        List<DeviceSlotService.SlotRestockAllocation> allocations = deviceSlotService
                .allocateRestockQuantity(task.getDeviceId(), dto.skuId(), qty);
        if (allocations.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "商品 " + dto.skuId() + " 无可用货道容量，请调低数量或调整货道陈列");
        }
        int allocated = 0;
        for (DeviceSlotService.SlotRestockAllocation alloc : allocations) {
            deviceSlotService.validateRestockLine(
                    task.getDeviceId(), alloc.slotCode(), dto.skuId(), alloc.quantity());
            saveRestockLine(taskId, dto, alloc.slotCode(), alloc.quantity());
            allocated += alloc.quantity();
        }
        if (allocated < qty) {
            log.warn("submitTaskLines: truncated {} -> {} for sku={} task={}",
                    qty, allocated, dto.skuId(), taskId);
        }
    }

    private void saveRestockLine(Long taskId, ReplenishmentTaskLineDto dto, String slotId, int qty) {
        ReplenishmentTaskLine line = new ReplenishmentTaskLine();
        line.setTaskId(taskId);
        line.setLineType(RESTOCK);
        line.setSkuId(dto.skuId());
        line.setBatchNo(dto.batchNo());
        line.setProductionDate(dto.productionDate());
        line.setExpiryDate(dto.expiryDate());
        line.setQuantity(qty);
        line.setSlotId(slotId);
        line.setApplied(false);
        taskLineRepository.save(line);
    }

    private void completeRouteIfReady(Long routeId) {
        finalizeRouteIfReady(routeId);
    }

    /**
     * 安全取消空/卡死任务：未签到、无已上架明细；无出库或出库未交接可取消。
     * 有 SHIPPED 未签收时回仓并取消在途，不 DELETE 业务表。
     * 任务已 CANCELLED 时仍幂等收口其脏出库/在途（历史联调残留）。
     */
    @Transactional
    public ReplenishmentTaskDto cancelEmptyTask(Long operatorId, Long taskId) {
        ReplenishmentTask task = taskRepository.findById(taskId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.REPLENISHMENT_TASK_NOT_FOUND));
        boolean alreadyCancelled = STATUS_CANCELLED.equals(task.getStatus());
        if (STATUS_COMPLETED.equals(task.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ApiMessages.REPLENISHMENT_TASK_ALREADY_COMPLETED);
        }
        if (!alreadyCancelled) {
            assertTaskCancellableEmpty(task);
        }
        if (task.getOutboundId() != null) {
            warehouseService.cancelUnreceivedOutboundForDevice(task.getOutboundId(), task.getDeviceId(), operatorId);
        }
        if (!alreadyCancelled) {
            task.setStatus(STATUS_CANCELLED);
            task = taskRepository.save(task);
            if (sessionService != null) {
                sessionService.closeRestockSessionsForTask(taskId, "补货任务已取消，自动关闭开门会话");
            }
            finalizeRouteIfReady(task.getRouteId());
        }
        log.info("cancelEmptyTask taskId={} routeId={} deviceId={} operatorId={} alreadyCancelled={}",
                taskId, task.getRouteId(), task.getDeviceId(), operatorId, alreadyCancelled);
        return toTaskDto(task);
    }

    /**
     * 取消空路线：取消其下所有可空取消的开放任务；若仅剩终态则收口路线为 CANCELLED/COMPLETED。
     * 已 CANCELLED 的任务仍会尝试收口其脏出库/在途。
     */
    @Transactional
    public ReplenishmentRouteDto cancelEmptyRoute(Long operatorId, Long routeId) {
        return runWithRouteLock(routeId, () -> doCancelEmptyRoute(operatorId, routeId));
    }

    private ReplenishmentRouteDto doCancelEmptyRoute(Long operatorId, Long routeId) {
        ReplenishmentRoute route = routeRepository.findByIdForUpdate(routeId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.REPLENISHMENT_ROUTE_NOT_FOUND));
        List<ReplenishmentTask> tasks = taskRepository.findByRouteId(routeId);
        if (isTerminalRouteStatus(route.getStatus())) {
            cleanupOrphanOutboundsForTasks(operatorId, tasks);
            log.info("cancelEmptyRoute orphan-cleanup routeId={} operatorId={} status={}",
                    routeId, operatorId, route.getStatus());
            return toRouteDto(route);
        }
        cancelOpenTasksOnRoute(operatorId, tasks);
        finalizeRouteIfReady(routeId);
        route = routeRepository.findById(routeId).orElse(route);
        if (!isTerminalRouteStatus(route.getStatus())) {
            route.setStatus(STATUS_CANCELLED);
            route = routeRepository.save(route);
        }
        log.info("cancelEmptyRoute routeId={} operatorId={} status={}", routeId, operatorId, route.getStatus());
        return toRouteDto(route);
    }

    private static boolean isTerminalRouteStatus(String status) {
        return STATUS_CANCELLED.equals(status) || STATUS_COMPLETED.equals(status);
    }

    private void cleanupOrphanOutboundsForTasks(Long operatorId, List<ReplenishmentTask> tasks) {
        for (ReplenishmentTask task : tasks) {
            if (task.getOutboundId() != null && !STATUS_COMPLETED.equals(task.getStatus())) {
                warehouseService.cancelUnreceivedOutboundForDevice(
                        task.getOutboundId(), task.getDeviceId(), operatorId);
            }
        }
    }

    private void cancelOpenTasksOnRoute(Long operatorId, List<ReplenishmentTask> tasks) {
        for (ReplenishmentTask task : tasks) {
            if (STATUS_COMPLETED.equals(task.getStatus())) {
                continue;
            }
            if (STATUS_CANCELLED.equals(task.getStatus())) {
                cancelTaskOutboundIfPresent(operatorId, task);
            } else {
                try {
                    assertTaskCancellableEmpty(task);
                } catch (ResponseStatusException ex) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, ApiMessages.REPLENISHMENT_ROUTE_CANCEL_BLOCKED);
                }
                cancelTaskOutboundIfPresent(operatorId, task);
                task.setStatus(STATUS_CANCELLED);
                taskRepository.save(task);
                if (sessionService != null) {
                    sessionService.closeRestockSessionsForTask(task.getTaskId(), "补货任务已取消，自动关闭开门会话");
                }
            }
        }
    }

    private void cancelTaskOutboundIfPresent(Long operatorId, ReplenishmentTask task) {
        if (task.getOutboundId() != null) {
            warehouseService.cancelUnreceivedOutboundForDevice(
                    task.getOutboundId(), task.getDeviceId(), operatorId);
        }
    }

    private void assertTaskCancellableEmpty(ReplenishmentTask task) {
        if (task.getCheckInAt() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ApiMessages.REPLENISHMENT_CANCEL_NOT_EMPTY);
        }
        boolean hasApplied = taskLineRepository.findByTaskIdOrderByLineIdAsc(task.getTaskId()).stream()
                .anyMatch(ReplenishmentTaskLine::isApplied);
        if (hasApplied) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ApiMessages.REPLENISHMENT_CANCEL_NOT_EMPTY);
        }
    }

    /** 路线任务均终态时收口：全取消→CANCELLED；含完成→COMPLETED。 */
    private void finalizeRouteIfReady(Long routeId) {
        if (routeId == null) {
            return;
        }
        runWithRouteLock(routeId, () -> {
            doFinalizeRouteIfReady(routeId);
            return null;
        });
    }

    private void doFinalizeRouteIfReady(Long routeId) {
        List<ReplenishmentTask> routeTasks = taskRepository.findByRouteId(routeId);
        if (routeTasks.isEmpty()) {
            routeRepository.findByIdForUpdate(routeId).ifPresent(route -> {
                if (!STATUS_CANCELLED.equals(route.getStatus()) && !STATUS_COMPLETED.equals(route.getStatus())) {
                    route.setStatus(STATUS_CANCELLED);
                    routeRepository.save(route);
                }
            });
            return;
        }
        boolean allTerminal = routeTasks.stream()
                .allMatch(item -> STATUS_COMPLETED.equals(item.getStatus()) || STATUS_CANCELLED.equals(item.getStatus()));
        if (!allTerminal) {
            return;
        }
        boolean anyCompleted = routeTasks.stream().anyMatch(item -> STATUS_COMPLETED.equals(item.getStatus()));
        routeRepository.findByIdForUpdate(routeId).ifPresent(route -> {
            route.setStatus(anyCompleted ? STATUS_COMPLETED : STATUS_CANCELLED);
            routeRepository.save(route);
        });
    }

    /** 路线下出现未完成任务时，从 COMPLETED 回退为进行中（联调补任务/补签到场景） */
    private void reopenRouteIfActive(Long routeId) {
        if (routeId == null) {
            return;
        }
        runWithRouteLock(routeId, () -> {
            doReopenRouteIfActive(routeId);
            return null;
        });
    }

    private void doReopenRouteIfActive(Long routeId) {
        List<ReplenishmentTask> routeTasks = taskRepository.findByRouteId(routeId);
        boolean hasOpen = routeTasks.stream()
                .anyMatch(item -> !STATUS_COMPLETED.equals(item.getStatus()) && !STATUS_CANCELLED.equals(item.getStatus()));
        if (!hasOpen) {
            return;
        }
        routeRepository.findByIdForUpdate(routeId).ifPresent(route -> {
            if (STATUS_COMPLETED.equals(route.getStatus()) || STATUS_CANCELLED.equals(route.getStatus())) {
                route.setStatus(STATUS_IN_PROGRESS);
                routeRepository.save(route);
            }
        });
    }



    @Transactional(readOnly = true)

    public List<ReplenishmentTaskDto> myTasks(Long assigneeUserId) {
        return taskRepository.findByAssigneeUserIdAndStatusIn(assigneeUserId, List.of(STATUS_PENDING, STATUS_IN_PROGRESS)).stream()
                .map(this::toTaskDto)
                .toList();
    }



    @Transactional(readOnly = true)

    public List<DeviceSkuLotDto> listDeviceLots(String deviceId) {

        return lotRepository.findByDeviceId(deviceId).stream()

                .map(this::toLotDto)

                .toList();

    }



    @Transactional(readOnly = true)

    public List<PullOffTaskDto> listOpenPullOffTasks() {

        return pullOffTaskRepository.findByStatusOrderByCreatedAtDesc("OPEN").stream()

                .map(this::toPullOffDto)

                .toList();

    }

    @Transactional
    public PullOffTaskDto ensurePullOffFromLot(String lotId) {
        return toPullOffDto(inventoryLotService.ensureOpenPullOffTask(lotId));
    }




    @Transactional
    public ReplenishmentRouteDto createTaskFromPullOff(Long operatorId, Long pullOffTaskId,
                                                       CreateFromExpiryRequest request) {
        PullOffTask pull = pullOffTaskRepository.findById(pullOffTaskId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "临期告警不存在"));
        if (!"OPEN".equalsIgnoreCase(pull.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该临期告警已处理");
        }
        String lineType = normalizePullOffLineType(request);
        Long assignee = request != null ? request.assigneeUserId() : null;
        PullOffTaskPlan plan = preparePullOffTaskPlan(pull, lineType);

        ReplenishmentRoute route = new ReplenishmentRoute();
        route.setRouteName("临期-" + pull.getDeviceId() + "-" + pull.getSkuId());
        route.setAssigneeUserId(assignee != null ? assignee : operatorId);
        route.setPlannedDate(LocalDate.now());
        route.setStatus(PLANNED);
        route = routeRepository.save(route);

        ReplenishmentTask task = new ReplenishmentTask();
        task.setRouteId(route.getRouteId());
        task.setDeviceId(pull.getDeviceId());
        task.setAssigneeUserId(route.getAssigneeUserId());
        task.setStatus(STATUS_PENDING);
        task.setNotes("from-expiry:" + pull.getTaskId()
                + (pull.getReason() != null ? " " + pull.getReason() : ""));
        task = taskRepository.save(task);
        notifyTaskAssigned(task);
        persistPullOffTaskLines(task.getTaskId(), lineType, pull, plan);
        pull.setStatus("RESOLVED");
        pull.setResolvedAt(Instant.now());
        pullOffTaskRepository.save(pull);
        return toRouteDto(route);
    }

    private static String normalizePullOffLineType(CreateFromExpiryRequest request) {
        String lineType = request != null && request.lineType() != null
                ? request.lineType().trim().toUpperCase(java.util.Locale.ROOT)
                : "PULL_OFF";
        if (!"PULL_OFF".equals(lineType) && !RESTOCK.equals(lineType)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "lineType 仅支持 PULL_OFF 或 RESTOCK");
        }
        return lineType;
    }

    private record PullOffTaskPlan(int qty, List<DeviceSlotService.SlotRestockAllocation> restockAllocs) {}

    private PullOffTaskPlan preparePullOffTaskPlan(PullOffTask pull, String lineType) {
        int qty = Math.max(1, pull.getQuantity());
        List<DeviceSlotService.SlotRestockAllocation> restockAllocs = List.of();
        if (!RESTOCK.equals(lineType)) {
            return new PullOffTaskPlan(qty, restockAllocs);
        }
        if (!deviceSlotService.hasSkuSlots(pull.getDeviceId(), pull.getSkuId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "该商品未绑定货道，无法创建补货任务；请先在货道配置中绑定 SKU");
        }
        int headroom = deviceSlotService.totalHeadroomForSku(pull.getDeviceId(), pull.getSkuId());
        if (headroom <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "该商品货道已满，无法创建补货任务；请改用「下架任务」腾出库存后再补");
        }
        if (qty > headroom) {
            qty = headroom;
        }
        restockAllocs = deviceSlotService.allocateRestockQuantity(pull.getDeviceId(), pull.getSkuId(), qty);
        if (restockAllocs.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "该商品货道已满，无法创建补货任务；请改用「下架任务」腾出库存后再补");
        }
        return new PullOffTaskPlan(qty, restockAllocs);
    }

    private void persistPullOffTaskLines(Long taskId, String lineType, PullOffTask pull, PullOffTaskPlan plan) {
        if (RESTOCK.equals(lineType)) {
            for (DeviceSlotService.SlotRestockAllocation alloc : plan.restockAllocs()) {
                ReplenishmentTaskLine line = new ReplenishmentTaskLine();
                line.setTaskId(taskId);
                line.setLineType(lineType);
                line.setSkuId(pull.getSkuId());
                line.setBatchNo(pull.getBatchNo());
                line.setQuantity(alloc.quantity());
                line.setSlotId(alloc.slotCode());
                line.setApplied(false);
                taskLineRepository.save(line);
            }
            return;
        }
        ReplenishmentTaskLine line = new ReplenishmentTaskLine();
        line.setTaskId(taskId);
        line.setLineType(lineType);
        line.setSkuId(pull.getSkuId());
        line.setBatchNo(pull.getBatchNo());
        line.setQuantity(plan.qty());
        line.setApplied(false);
        taskLineRepository.save(line);
    }

    /** 补货任务指派站内信（商户端消息中心）。 */
    private void notifyTaskAssigned(ReplenishmentTask task) {
        if (task == null || task.getDeviceId() == null || task.getDeviceId().isBlank()) {
            return;
        }
        try {
            // 用柜机所属商户发信，避免运营超管 assignee 走 merchant:portal:access 导致整单回滚（BUG-014）
            String merchantId = deviceRepository.findById(task.getDeviceId())
                    .map(DeviceInfo::getMerchantId)
                    .filter(id -> id != null && !id.isBlank())
                    .orElse(null);
            if (merchantId == null) {
                return;
            }
            String deviceName = deviceRepository.findById(task.getDeviceId())
                    .map(d -> d.getDeviceName() != null ? d.getDeviceName() : task.getDeviceId())
                    .orElse(task.getDeviceId());
            notificationService.notifyMerchant(
                    merchantId,
                    "replenishment_assigned",
                    Map.of("taskId", String.valueOf(task.getTaskId()),
                            "deviceName", deviceName,
                            "time", java.time.LocalDate.now().toString()),
                    "REPLENISHMENT",
                    String.valueOf(task.getTaskId()));
        } catch (Exception e) {
            log.warn("replenishment notification failed task={}", task.getTaskId(), e);
        }
    }



    private DeviceInventoryDto toInventoryDto(DeviceSkuInventory inv) {
        return toInventoryDto(inv, new java.util.HashMap<>(), new java.util.HashMap<>());
    }

    private DeviceInventoryDto toInventoryDto(DeviceSkuInventory inv,
                                              Map<String, Map<String, Integer>> sellableByDevice,
                                              Map<String, Boolean> ledgerByDevice) {
        String deviceId = inv.getId().getDeviceId();
        String skuId = inv.getId().getSkuId();
        boolean ledger = ledgerByDevice.computeIfAbsent(deviceId, inventoryLotService::deviceUsesLotLedger);
        int qty = inv.getQuantity();
        if (ledger) {
            Map<String, Integer> bySku = sellableByDevice.computeIfAbsent(
                    deviceId, inventoryLotService::sellableQtyBySku);
            qty = bySku.getOrDefault(skuId, 0);
        }
        return new DeviceInventoryDto(
                deviceId, skuId,
                qty, inv.getCapacity(), inv.getLowThreshold(), inv.getUpdatedAt()
        );
    }



    private ReplenishmentRouteDto toRouteDto(ReplenishmentRoute route) {

        List<ReplenishmentTaskDto> tasks = taskRepository.findByRouteId(route.getRouteId()).stream()

                .map(this::toTaskDto)

                .toList();

        List<RouteWaypointDto> waypoints = List.of();

        if (route.getRouteGeoJson() != null && !route.getRouteGeoJson().isBlank()) {

            try {

                waypoints = objectMapper.readValue(route.getRouteGeoJson(),

                        objectMapper.getTypeFactory().constructCollectionType(List.class, RouteWaypointDto.class));

            } catch (Exception ignored) {

            }

        }

        return new ReplenishmentRouteDto(

                route.getRouteId(), route.getRouteName(), route.getAssigneeUserId(),

                route.getPlannedDate(), route.getStatus(), tasks, route.getCreatedAt(),

                resolveRouteDistanceM(route, tasks, waypoints), waypoints

        );

    }

    /** 路线里程：优先取规划值；缺失时按路线坐标或任务设备坐标估算（Haversine）。 */
    private Integer resolveRouteDistanceM(ReplenishmentRoute route,
                                          List<ReplenishmentTaskDto> tasks,
                                          List<RouteWaypointDto> waypoints) {
        if (route.getTotalDistanceM() != null && route.getTotalDistanceM() > 0) {
            return route.getTotalDistanceM();
        }
        double dist = waypoints != null && !waypoints.isEmpty()
                ? distanceFromWaypoints(waypoints)
                : distanceFromTaskDevices(tasks);
        return (int) Math.round(dist);
    }

    private static double distanceFromWaypoints(List<RouteWaypointDto> waypoints) {
        double dist = 0;
        for (int i = 1; i < waypoints.size(); i++) {
            RouteWaypointDto a = waypoints.get(i - 1);
            RouteWaypointDto b = waypoints.get(i);
            if ((a.latitude() != 0 || a.longitude() != 0) && (b.latitude() != 0 || b.longitude() != 0)) {
                dist += haversineMeters(a.latitude(), a.longitude(), b.latitude(), b.longitude());
            }
        }
        return dist;
    }

    private double distanceFromTaskDevices(List<ReplenishmentTaskDto> tasks) {
        java.util.List<DeviceInfo> ordered = new java.util.ArrayList<>();
        for (ReplenishmentTaskDto t : tasks) {
            if (t.deviceId() == null || t.deviceId().isBlank()) {
                continue;
            }
            deviceRepository.findById(t.deviceId()).ifPresent(d -> {
                if (d.getLatitude() != null && d.getLongitude() != null) {
                    ordered.add(d);
                }
            });
        }
        double dist = 0;
        for (int i = 1; i < ordered.size(); i++) {
            DeviceInfo a = ordered.get(i - 1);
            DeviceInfo b = ordered.get(i);
            dist += haversineMeters(a.getLatitude(), a.getLongitude(), b.getLatitude(), b.getLongitude());
        }
        return dist;
    }



    private ReplenishmentTaskDto toTaskDto(ReplenishmentTask t) {
        return new ReplenishmentTaskDto(
                t.getTaskId(), t.getRouteId(), t.getDeviceId(), t.getAssigneeUserId(),
                t.getStatus(), t.getNotes(), t.getCompletedAt(),
                t.getCheckInAt(), t.getCheckInLat(), t.getCheckInLng(),
                resolveCheckInDistanceM(t),
                t.getRequestId(), t.getOutboundId(), t.getCreatedAt()
        );
    }

    private ReplenishmentFulfillmentTaskDto toFulfillmentTaskDto(
            ReplenishmentTask task, ReplenishmentRoute route) {
        ReplenishmentTaskDto base = toTaskDto(task);
        String routeName = route != null ? route.getRouteName() : null;
        Long assignee;
        if (base.assigneeUserId() != null) {
            assignee = base.assigneeUserId();
        } else if (route != null) {
            assignee = route.getAssigneeUserId();
        } else {
            assignee = null;
        }
        return new ReplenishmentFulfillmentTaskDto(
                base.taskId(),
                base.routeId(),
                routeName,
                base.deviceId(),
                assignee,
                base.status(),
                base.notes(),
                base.completedAt(),
                base.checkInAt(),
                base.checkInLat(),
                base.checkInLng(),
                base.checkInDistanceM(),
                base.requestId(),
                base.outboundId(),
                base.createdAt()
        );
    }

    private Double resolveCheckInDistanceM(ReplenishmentTask t) {
        if (t.getCheckInLat() == null || t.getCheckInLng() == null || t.getDeviceId() == null) {
            return null;
        }
        DeviceInfo device = deviceRepository.findById(t.getDeviceId()).orElse(null);
        if (device == null || device.getLatitude() == null || device.getLongitude() == null) {
            return null;
        }
        return haversineMeters(device.getLatitude(), device.getLongitude(), t.getCheckInLat(), t.getCheckInLng());
    }



    private ReplenishmentTaskLineDto toLineDto(ReplenishmentTaskLine line) {

        return new ReplenishmentTaskLineDto(

                line.getLineId(), line.getLineType(), line.getSkuId(), line.getBatchNo(),

                line.getProductionDate(), line.getExpiryDate(), line.getQuantity(),

                line.getSlotId(), line.isApplied()

        );

    }



    private DeviceSkuLotDto toLotDto(DeviceSkuLot lot) {

        return new DeviceSkuLotDto(

                lot.getLotId(), lot.getDeviceId(), lot.getSkuId(), lot.getBatchNo(),

                lot.getProductionDate(), lot.getExpiryDate(), lot.getQuantity(),

                lot.getSlotId(), lot.getStatus()

        );

    }



    private PullOffTaskDto toPullOffDto(PullOffTask task) {
        int headroom = 0;
        try {
            headroom = deviceSlotService.totalHeadroomForSku(task.getDeviceId(), task.getSkuId());
        } catch (Exception ignored) {
            headroom = 0;
        }
        return new PullOffTaskDto(
                task.getTaskId(), task.getDeviceId(), task.getSkuId(), task.getLotId(),
                task.getBatchNo(), task.getQuantity(), task.getReason(), task.getStatus(),
                task.getCreatedAt(), Math.max(0, headroom)
        );
    }

    private ReplenishmentTask requireTaskForUpdate(Long taskId) {
        return taskRepository.findByIdForUpdate(taskId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        ApiMessages.REPLENISHMENT_TASK_NOT_FOUND));
    }

    static String replenishmentTaskLockKey(Long taskId) {
        return "replenishment:task:" + taskId;
    }

    static String replenishmentRouteLockKey(Long routeId) {
        return "replenishment:route:" + routeId;
    }

    private <T> T runWithRouteLock(Long routeId, java.util.function.Supplier<T> action) {
        if (!distributedLockService.tryLock(replenishmentRouteLockKey(routeId), 60, 5)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "补货路线处理中，请稍后重试");
        }
        try {
            return action.get();
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage(), e);
        } finally {
            distributedLockService.unlock(replenishmentRouteLockKey(routeId));
        }
    }

    private <T> T runWithTaskLock(Long taskId, java.util.function.Supplier<T> action) {
        if (!distributedLockService.tryLock(replenishmentTaskLockKey(taskId), 60, 5)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "补货任务处理中，请稍后重试");
        }
        try {
            return action.get();
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage(), e);
        } finally {
            distributedLockService.unlock(replenishmentTaskLockKey(taskId));
        }
    }

}
