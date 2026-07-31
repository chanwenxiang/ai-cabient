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

import org.springframework.http.HttpStatus;

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

    private static final Logger log = LoggerFactory.getLogger(ReplenishmentService.class);

    private final DeviceSkuInventoryMapper inventoryRepository;

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
                                MerchantReplenishmentRequestLineMapper merchantRequestLineRepository) {

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

    }



    @Transactional(readOnly = true)

    public List<DeviceInventoryDto> listInventory(String deviceId, boolean lowStockOnly) {

        List<DeviceSkuInventory> rows;

        if (lowStockOnly) {

            rows = inventoryRepository.findLowStock();

            if (deviceId != null && !deviceId.isBlank()) {

                String dev = deviceId.trim();

                rows = rows.stream()

                        .filter(i -> dev.equals(i.getId().getDeviceId()))

                        .toList();

            }

        } else {

            rows = deviceId != null && !deviceId.isBlank()

                    ? inventoryRepository.findByIdDeviceId(deviceId.trim())

                    : inventoryRepository.findAll();

        }

        return rows.stream().map(this::toInventoryDto).toList();

    }



    @Transactional

    public DeviceInventoryDto upsertInventory(Long operatorId, DeviceInventoryDto body) {

        DeviceSkuInventoryId id = new DeviceSkuInventoryId(body.deviceId(), body.skuId());

        DeviceSkuInventory inv = inventoryRepository.findById(id).orElseGet(() -> {

            DeviceSkuInventory n = new DeviceSkuInventory();

            n.setId(id);

            return n;

        });

        inv.setQuantity(body.quantity());

        inv.setCapacity(body.capacity());

        inv.setLowThreshold(body.lowThreshold());

        return toInventoryDto(inventoryRepository.save(inv));

    }



    @Transactional(readOnly = true)

    public List<ReplenishmentRouteDto> listRoutes() {

        return routeRepository.findAllByOrderByPlannedDateDesc().stream()

                .map(this::toRouteDto)

                .toList();

    }



    @Transactional

    public ReplenishmentRouteDto planAndCreateRoute(Long operatorId, PlanRouteRequest request) {

        assertHasReplenishmentGap(request);

        RoutePlanningService.PlannedRoute planned = routePlanningService.plan(request);



        ReplenishmentRoute route = new ReplenishmentRoute();

        route.setRouteName(request.routeName() != null ? request.routeName() : "补货路线");

        route.setAssigneeUserId(request.assigneeUserId());

        route.setPlannedDate(request.plannedDate() != null ? request.plannedDate() : LocalDate.now());

        route.setStatus("PLANNED");

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

            task.setStatus("PENDING");

            task.setNotes("seq=" + wp.sequence() + " dist=" + wp.distanceFromPrevM() + "m");

            taskRepository.save(task);

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
            if (deviceId == null || deviceId.isBlank()) {
                continue;
            }
            List<ReplenishmentSuggestDto> suggestions = warehouseService.suggestForDevice(deviceId.trim(), true);
            if (suggestions.stream().anyMatch(s -> s.suggestQty() > 0)) {
                anyGap = true;
                break;
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
        return seedDraftRestockLines(taskId, task.getDeviceId(), skuQty);
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
                line.setLineType("RESTOCK");
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
                line.setLineType("RESTOCK");
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
        List<ReplenishmentTask> tasks = taskRepository.findByOutboundId(outboundId);
        for (ReplenishmentTask task : tasks) {
            if ("COMPLETED".equals(task.getStatus())) {
                continue;
            }
            if (!taskLineRepository.findByTaskIdAndAppliedFalse(task.getTaskId()).isEmpty()) {
                continue;
            }
            List<WarehouseOutboundLine> outboundLines = warehouseService
                    .outboundLinesForDevice(outboundId, task.getDeviceId());
            if (outboundLines.isEmpty()) {
                continue;
            }
            for (WarehouseOutboundLine ol : outboundLines) {
                List<DeviceSlotService.SlotRestockAllocation> allocations =
                        resolveOutboundSlotAllocations(task.getDeviceId(), ol);
                int allocated = 0;
                for (DeviceSlotService.SlotRestockAllocation alloc : allocations) {
                    ReplenishmentTaskLine line = new ReplenishmentTaskLine();
                    line.setTaskId(task.getTaskId());
                    line.setLineType("RESTOCK");
                    line.setSkuId(ol.getSkuId());
                    line.setBatchNo(ol.getBatchNo());
                    line.setExpiryDate(ol.getExpiryDate());
                    line.setQuantity(alloc.quantity());
                    line.setSlotId(alloc.slotCode());
                    line.setApplied(false);
                    taskLineRepository.save(line);
                    allocated += alloc.quantity();
                }
                // 超出货道可补容量：截断，不生成无货道行（避免确认时手填）
                int remain = Math.max(0, ol.getQuantity() - allocated);
                if (remain > 0) {
                    log.warn("generateLinesFromOutbound: truncated {} units over capacity task={} sku={} slot={} allocated={}",
                            remain, task.getTaskId(), ol.getSkuId(), ol.getSlotId(), allocated);
                }
            }
            if (!"IN_PROGRESS".equals(task.getStatus())) {
                task.setStatus("IN_PROGRESS");
                taskRepository.save(task);
            }
        }
    }

    @Transactional
    public ReplenishmentTaskDto checkInTask(Long operatorId, Long taskId, ReplenishmentCheckInRequest request) {
        ReplenishmentTask task = taskRepository.findById(taskId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.REPLENISHMENT_TASK_NOT_FOUND));
        if (request != null && request.latitude() != null && request.longitude() != null) {
            validateCheckInLocation(task.getDeviceId(), request.latitude(), request.longitude());
            task.setCheckInLat(request.latitude());
            task.setCheckInLng(request.longitude());
        }
        task.setCheckInAt(Instant.now());
        if (!"IN_PROGRESS".equals(task.getStatus()) && !"COMPLETED".equals(task.getStatus())) {
            task.setStatus("IN_PROGRESS");
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

        route.setStatus("PLANNED");

        route = routeRepository.save(route);



        if (body.tasks() != null) {

            for (ReplenishmentTaskDto t : body.tasks()) {

                ReplenishmentTask task = new ReplenishmentTask();

                task.setRouteId(route.getRouteId());

                task.setDeviceId(t.deviceId());

                task.setAssigneeUserId(t.assigneeUserId() != null ? t.assigneeUserId() : route.getAssigneeUserId());

                task.setStatus("PENDING");

                task.setNotes(t.notes());

                taskRepository.save(task);

            }

        }

        return toRouteDto(route);

    }



    @Transactional

    public List<ReplenishmentTaskLineDto> submitTaskLines(Long operatorId, Long taskId,

                                                          SubmitReplenishmentLinesRequest request) {

        ReplenishmentTask task = taskRepository.findById(taskId)

                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.REPLENISHMENT_TASK_NOT_FOUND));

        if ("COMPLETED".equals(task.getStatus())) {

            throw new ResponseStatusException(HttpStatus.CONFLICT, ApiMessages.REPLENISHMENT_TASK_ALREADY_COMPLETED);

        }

        taskLineRepository.deleteByTaskIdAndAppliedFalse(taskId);

        if (request.lines() != null) {

            for (ReplenishmentTaskLineDto dto : request.lines()) {

                // 小程序/联调常省略 lineType，默认按上架（RESTOCK）处理
                String lineType = (dto.lineType() == null || dto.lineType().isBlank())
                        ? "RESTOCK"
                        : dto.lineType().trim().toUpperCase();
                if ("RESTOCK".equals(lineType)) {
                    persistRestockLines(task, taskId, dto);
                } else {
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

            }

        }

        if (!"IN_PROGRESS".equals(task.getStatus())) {

            task.setStatus("IN_PROGRESS");

            taskRepository.save(task);

        }
        reopenRouteIfActive(task.getRouteId());

        return listTaskLines(taskId);

    }



    @Transactional(readOnly = true)

    public List<ReplenishmentTaskLineDto> listTaskLines(Long taskId) {

        return taskLineRepository.findByTaskIdOrderByLineIdAsc(taskId).stream()

                .map(this::toLineDto)

                .toList();

    }



    @Transactional

    public ReplenishmentTaskDto completeTask(Long operatorId, Long taskId) {

        ReplenishmentTask task = taskRepository.findById(taskId)

                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.REPLENISHMENT_TASK_NOT_FOUND));

        // 未完成任务必须先签到，避免远程误点完成导致库存虚增
        if (!"COMPLETED".equals(task.getStatus()) && task.getCheckInAt() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    ApiMessages.REPLENISHMENT_COMPLETE_CHECK_IN_REQUIRED);
        }

        if ("COMPLETED".equals(task.getStatus())) {
            // 任务已完成但出库仍在途（先完成任务、后发运的联调顺序）时，补签收；
            // 仅当任务本身无明细时按出库行回写，避免与已上架任务行重复加库存
            if (task.getOutboundId() != null
                    && inTransitService.hasOpenForDevice(task.getOutboundId(), task.getDeviceId())) {
                int appliedQty;
                List<ReplenishmentTaskLine> existingLines =
                        taskLineRepository.findByTaskIdOrderByLineIdAsc(taskId);
                if (existingLines.isEmpty()) {
                    appliedQty = restockFromOutboundLines(
                            task, operatorId, "OB-" + task.getOutboundId());
                } else {
                    appliedQty = existingLines.stream()
                            .filter(l -> "RESTOCK".equalsIgnoreCase(l.getLineType()))
                            .filter(ReplenishmentTaskLine::isApplied)
                            .mapToInt(ReplenishmentTaskLine::getQuantity)
                            .sum();
                }
                inTransitService.receiveForDevice(task.getOutboundId(), task.getDeviceId());
                warehouseService.markDeviceHandoverReceived(
                        task.getOutboundId(), task.getDeviceId(), appliedQty);
            }
            completeRouteIfReady(task.getRouteId());
            if (sessionService != null) {
                sessionService.closeRestockSessionsForTask(taskId, "补货任务已完成，自动关闭开门会话");
            }
            markLinkedRequestCompleted(task);
            return toTaskDto(task);
        }

        List<ReplenishmentTaskLine> pending = taskLineRepository.findByTaskIdAndAppliedFalse(taskId);

        boolean expectReceive = false;
        if (task.getOutboundId() != null) {
            boolean inTransit = inTransitService.hasOpenForDevice(task.getOutboundId(), task.getDeviceId());
            boolean hasOutboundLines = warehouseService.hasOutboundLinesForDevice(
                    task.getOutboundId(), task.getDeviceId());
            // 有仓配明细却未发运：有待上架任务行时阻止完成
            if (!inTransit && hasOutboundLines && !pending.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        ApiMessages.REPLENISHMENT_OUTBOUND_NOT_IN_TRANSIT);
            }
            // 在途签收与任务行是否为空无关（空任务行仍应签收仓配在途）
            expectReceive = inTransit;
        }

        String refId = String.valueOf(taskId);
        int appliedRestockQty = 0;

        for (ReplenishmentTaskLine line : pending) {

            inventoryLotService.applyReplenishmentLine(task.getDeviceId(), line, operatorId, refId);

            if ("RESTOCK".equalsIgnoreCase(line.getLineType())) {
                deviceSlotService.recordRestock(task.getDeviceId(), line.getSlotId());
                appliedRestockQty += line.getQuantity();
            }

            line.setApplied(true);

            taskLineRepository.save(line);

        }

        task.setStatus("COMPLETED");

        task.setCompletedAt(Instant.now());

        if (expectReceive) {
            // 仓配在途签收：按出库行上架；若已有现场 RESTOCK 行，则只补「SKU 差额」，避免重复加可乐又漏掉水。
            Map<String, Integer> appliedBySku = new HashMap<>();
            for (ReplenishmentTaskLine line : taskLineRepository.findByTaskIdOrderByLineIdAsc(taskId)) {
                if (!"RESTOCK".equalsIgnoreCase(line.getLineType()) || !line.isApplied()) {
                    continue;
                }
                appliedBySku.merge(line.getSkuId(), line.getQuantity(), Integer::sum);
            }
            int fromOutbound = restockFromOutboundLines(
                    task, operatorId, "OB-" + task.getOutboundId(), appliedBySku);
            if (pending.isEmpty()) {
                appliedRestockQty = fromOutbound;
            } else {
                appliedRestockQty += fromOutbound;
            }
            inTransitService.receiveForDevice(task.getOutboundId(), task.getDeviceId());
            // 按实际上架数量签收：不足则 PARTIAL
            warehouseService.markDeviceHandoverReceived(
                    task.getOutboundId(), task.getDeviceId(), appliedRestockQty);
        }

        task = taskRepository.save(task);
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
            req.setStatus("COMPLETED");
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
            int credit = remainingCredit.getOrDefault(ol.getSkuId(), 0);
            int need = ol.getQuantity() - credit;
            if (credit > 0) {
                remainingCredit.put(ol.getSkuId(), Math.max(0, credit - ol.getQuantity()));
            }
            if (need <= 0) {
                continue;
            }
            List<DeviceSlotService.SlotRestockAllocation> allocations =
                    resolveOutboundSlotAllocations(task.getDeviceId(), ol, need);
            if (allocations.isEmpty()) {
                // 无货道绑定时仍按出库数量回写（兼容旧柜）
                if (!deviceSlotService.hasSkuSlots(task.getDeviceId(), ol.getSkuId())) {
                    inventoryLotService.addRestock(
                            task.getDeviceId(), ol.getSkuId(), ol.getBatchNo(), null,
                            ol.getExpiryDate(), need, null, operatorId, refId);
                    received += need;
                }
                continue;
            }
            for (DeviceSlotService.SlotRestockAllocation alloc : allocations) {
                inventoryLotService.addRestock(
                        task.getDeviceId(), ol.getSkuId(), ol.getBatchNo(), null,
                        ol.getExpiryDate(), alloc.quantity(), alloc.slotCode(), operatorId, refId);
                deviceSlotService.recordRestock(task.getDeviceId(), alloc.slotCode());
                received += alloc.quantity();
            }
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
        line.setLineType("RESTOCK");
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
        boolean alreadyCancelled = "CANCELLED".equals(task.getStatus());
        if ("COMPLETED".equals(task.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ApiMessages.REPLENISHMENT_TASK_ALREADY_COMPLETED);
        }
        if (!alreadyCancelled) {
            assertTaskCancellableEmpty(task);
        }
        if (task.getOutboundId() != null) {
            warehouseService.cancelUnreceivedOutboundForDevice(task.getOutboundId(), task.getDeviceId(), operatorId);
        }
        if (!alreadyCancelled) {
            task.setStatus("CANCELLED");
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
        ReplenishmentRoute route = routeRepository.findById(routeId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.REPLENISHMENT_ROUTE_NOT_FOUND));
        List<ReplenishmentTask> tasks = taskRepository.findByRouteId(routeId);
        // 终态路线仍允许幂等收口脏出库（如历史 SHIPPED + 任务已取消）
        if ("CANCELLED".equals(route.getStatus()) || "COMPLETED".equals(route.getStatus())) {
            for (ReplenishmentTask task : tasks) {
                if (task.getOutboundId() != null && !"COMPLETED".equals(task.getStatus())) {
                    warehouseService.cancelUnreceivedOutboundForDevice(
                            task.getOutboundId(), task.getDeviceId(), operatorId);
                }
            }
            log.info("cancelEmptyRoute orphan-cleanup routeId={} operatorId={} status={}",
                    routeId, operatorId, route.getStatus());
            return toRouteDto(route);
        }
        for (ReplenishmentTask task : tasks) {
            if ("COMPLETED".equals(task.getStatus())) {
                continue;
            }
            if ("CANCELLED".equals(task.getStatus())) {
                if (task.getOutboundId() != null) {
                    warehouseService.cancelUnreceivedOutboundForDevice(
                            task.getOutboundId(), task.getDeviceId(), operatorId);
                }
                continue;
            }
            try {
                assertTaskCancellableEmpty(task);
            } catch (ResponseStatusException ex) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, ApiMessages.REPLENISHMENT_ROUTE_CANCEL_BLOCKED);
            }
            if (task.getOutboundId() != null) {
                warehouseService.cancelUnreceivedOutboundForDevice(task.getOutboundId(), task.getDeviceId(), operatorId);
            }
            task.setStatus("CANCELLED");
            taskRepository.save(task);
            if (sessionService != null) {
                sessionService.closeRestockSessionsForTask(task.getTaskId(), "补货任务已取消，自动关闭开门会话");
            }
        }
        finalizeRouteIfReady(routeId);
        route = routeRepository.findById(routeId).orElse(route);
        // 仍开放则强制标 CANCELLED（任务已全部取消）
        if (!"CANCELLED".equals(route.getStatus()) && !"COMPLETED".equals(route.getStatus())) {
            route.setStatus("CANCELLED");
            route = routeRepository.save(route);
        }
        log.info("cancelEmptyRoute routeId={} operatorId={} status={}", routeId, operatorId, route.getStatus());
        return toRouteDto(route);
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
        List<ReplenishmentTask> routeTasks = taskRepository.findByRouteId(routeId);
        if (routeTasks.isEmpty()) {
            routeRepository.findById(routeId).ifPresent(route -> {
                if (!"CANCELLED".equals(route.getStatus()) && !"COMPLETED".equals(route.getStatus())) {
                    route.setStatus("CANCELLED");
                    routeRepository.save(route);
                }
            });
            return;
        }
        boolean allTerminal = routeTasks.stream()
                .allMatch(item -> "COMPLETED".equals(item.getStatus()) || "CANCELLED".equals(item.getStatus()));
        if (!allTerminal) {
            return;
        }
        boolean anyCompleted = routeTasks.stream().anyMatch(item -> "COMPLETED".equals(item.getStatus()));
        routeRepository.findById(routeId).ifPresent(route -> {
            route.setStatus(anyCompleted ? "COMPLETED" : "CANCELLED");
            routeRepository.save(route);
        });
    }

    /** 路线下出现未完成任务时，从 COMPLETED 回退为进行中（联调补任务/补签到场景） */
    private void reopenRouteIfActive(Long routeId) {
        if (routeId == null) {
            return;
        }
        List<ReplenishmentTask> routeTasks = taskRepository.findByRouteId(routeId);
        boolean hasOpen = routeTasks.stream()
                .anyMatch(item -> !"COMPLETED".equals(item.getStatus()) && !"CANCELLED".equals(item.getStatus()));
        if (!hasOpen) {
            return;
        }
        routeRepository.findById(routeId).ifPresent(route -> {
            if ("COMPLETED".equals(route.getStatus()) || "CANCELLED".equals(route.getStatus())) {
                route.setStatus("IN_PROGRESS");
                routeRepository.save(route);
            }
        });
    }



    @Transactional(readOnly = true)

    public List<ReplenishmentTaskDto> myTasks(Long assigneeUserId) {
        return taskRepository.findByAssigneeUserIdAndStatusIn(assigneeUserId, List.of("PENDING", "IN_PROGRESS")).stream()
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
        String lineType = request != null && request.lineType() != null
                ? request.lineType().trim().toUpperCase(java.util.Locale.ROOT)
                : "PULL_OFF";
        if (!"PULL_OFF".equals(lineType) && !"RESTOCK".equals(lineType)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "lineType 仅支持 PULL_OFF 或 RESTOCK");
        }
        Long assignee = request != null ? request.assigneeUserId() : null;

        int qty = Math.max(1, pull.getQuantity());
        List<DeviceSlotService.SlotRestockAllocation> restockAllocs = List.of();
        if ("RESTOCK".equals(lineType)) {
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
        }

        ReplenishmentRoute route = new ReplenishmentRoute();
        route.setRouteName("临期-" + pull.getDeviceId() + "-" + pull.getSkuId());
        route.setAssigneeUserId(assignee != null ? assignee : operatorId);
        route.setPlannedDate(LocalDate.now());
        route.setStatus("PLANNED");
        route = routeRepository.save(route);

        ReplenishmentTask task = new ReplenishmentTask();
        task.setRouteId(route.getRouteId());
        task.setDeviceId(pull.getDeviceId());
        task.setAssigneeUserId(route.getAssigneeUserId());
        task.setStatus("PENDING");
        task.setNotes("from-expiry:" + pull.getTaskId()
                + (pull.getReason() != null ? " " + pull.getReason() : ""));
        task = taskRepository.save(task);

        if ("RESTOCK".equals(lineType)) {
            // 与 seedDraftRestockLines 一致：每个货道分配单独一行，避免把总量写进首槽导致超填
            for (DeviceSlotService.SlotRestockAllocation alloc : restockAllocs) {
                ReplenishmentTaskLine line = new ReplenishmentTaskLine();
                line.setTaskId(task.getTaskId());
                line.setLineType(lineType);
                line.setSkuId(pull.getSkuId());
                line.setBatchNo(pull.getBatchNo());
                line.setQuantity(alloc.quantity());
                line.setSlotId(alloc.slotCode());
                line.setApplied(false);
                taskLineRepository.save(line);
            }
        } else {
            ReplenishmentTaskLine line = new ReplenishmentTaskLine();
            line.setTaskId(task.getTaskId());
            line.setLineType(lineType);
            line.setSkuId(pull.getSkuId());
            line.setBatchNo(pull.getBatchNo());
            line.setQuantity(qty);
            line.setApplied(false);
            taskLineRepository.save(line);
        }

        pull.setStatus("RESOLVED");
        pull.setResolvedAt(Instant.now());
        pullOffTaskRepository.save(pull);

        return toRouteDto(route);
    }



    private DeviceInventoryDto toInventoryDto(DeviceSkuInventory inv) {

        return new DeviceInventoryDto(

                inv.getId().getDeviceId(), inv.getId().getSkuId(),

                inv.getQuantity(), inv.getCapacity(), inv.getLowThreshold(), inv.getUpdatedAt()

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

                route.getTotalDistanceM(), waypoints

        );

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

}
