package com.aicabinet.trade.service;



import com.aicabinet.common.dto.*;

import com.aicabinet.trade.domain.DeviceInfo;
import com.aicabinet.trade.domain.DeviceSkuInventory;

import com.aicabinet.trade.domain.DeviceSkuInventoryId;

import com.aicabinet.trade.domain.DeviceSkuLot;

import com.aicabinet.trade.domain.PullOffTask;

import com.aicabinet.trade.domain.ReplenishmentRoute;

import com.aicabinet.trade.domain.ReplenishmentTask;

import com.aicabinet.trade.domain.ReplenishmentTaskLine;

import com.aicabinet.trade.domain.WarehouseOutboundLine;

import com.aicabinet.trade.repository.DeviceInfoRepository;
import com.aicabinet.trade.repository.DeviceSkuInventoryRepository;

import com.aicabinet.trade.repository.DeviceSkuLotRepository;

import com.aicabinet.trade.repository.PullOffTaskRepository;

import com.aicabinet.trade.repository.ReplenishmentRouteRepository;

import com.aicabinet.trade.repository.ReplenishmentTaskLineRepository;

import com.aicabinet.trade.repository.ReplenishmentTaskRepository;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.http.HttpStatus;

import org.springframework.stereotype.Service;

import org.springframework.transaction.annotation.Transactional;

import org.springframework.web.server.ResponseStatusException;



import java.time.Instant;

import java.time.LocalDate;

import java.util.List;



@Service

public class ReplenishmentService {



    private final DeviceSkuInventoryRepository inventoryRepository;

    private final ReplenishmentRouteRepository routeRepository;

    private final ReplenishmentTaskRepository taskRepository;

    private final ReplenishmentTaskLineRepository taskLineRepository;

    private final RoutePlanningService routePlanningService;

    private final InventoryLotService inventoryLotService;

    private final DeviceSkuLotRepository lotRepository;

    private final PullOffTaskRepository pullOffTaskRepository;

    private final ObjectMapper objectMapper;

    private final WarehouseService warehouseService;

    private final DeviceInfoRepository deviceRepository;

    private final DeviceSlotService deviceSlotService;

    private final InTransitService inTransitService;



    public ReplenishmentService(DeviceSkuInventoryRepository inventoryRepository,

                                ReplenishmentRouteRepository routeRepository,

                                ReplenishmentTaskRepository taskRepository,

                                ReplenishmentTaskLineRepository taskLineRepository,

                                RoutePlanningService routePlanningService,

                                InventoryLotService inventoryLotService,

                                DeviceSkuLotRepository lotRepository,

                                PullOffTaskRepository pullOffTaskRepository,

                                ObjectMapper objectMapper,

                                WarehouseService warehouseService,
                                DeviceInfoRepository deviceRepository,
                                DeviceSlotService deviceSlotService,
                                InTransitService inTransitService) {

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

        try {
            warehouseService.createOutboundForRoute(route.getRouteId(), null, route.getAssigneeUserId());
        } catch (Exception ignored) {
            // 仓库库存不足时仍允许创建路线
        }

        return toRouteDto(route);

    }



    @Transactional(readOnly = true)
    public List<ReplenishmentSuggestDto> suggestForDevice(String deviceId) {
        return warehouseService.suggestForDevice(deviceId);
    }

    @Transactional(readOnly = true)
    public List<SlotReplenishmentSuggestDto> suggestSlotsForDevice(String deviceId) {
        return deviceSlotService.suggestSlotsForDevice(deviceId);
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
                List<DeviceSlotService.SlotRestockAllocation> allocations = deviceSlotService
                        .allocateRestockQuantity(task.getDeviceId(), ol.getSkuId(), ol.getQuantity());
                if (allocations.isEmpty()) {
                    continue;
                }
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
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "task not found"));
        if (request != null && request.latitude() != null && request.longitude() != null) {
            validateCheckInLocation(task.getDeviceId(), request.latitude(), request.longitude());
            task.setCheckInLat(request.latitude());
            task.setCheckInLng(request.longitude());
        }
        task.setCheckInAt(Instant.now());
        if (!"IN_PROGRESS".equals(task.getStatus()) && !"COMPLETED".equals(task.getStatus())) {
            task.setStatus("IN_PROGRESS");
        }
        return toTaskDto(taskRepository.save(task));
    }

    private void validateCheckInLocation(String deviceId, double lat, double lng) {
        DeviceInfo device = deviceRepository.findById(deviceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "device not found"));
        if (device.getLatitude() == null || device.getLongitude() == null) {
            return;
        }
        double distM = haversineMeters(device.getLatitude(), device.getLongitude(), lat, lng);
        if (distM > 500) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "check-in too far from device (" + Math.round(distM) + "m)");
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

                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "task not found"));

        if ("COMPLETED".equals(task.getStatus())) {

            throw new ResponseStatusException(HttpStatus.CONFLICT, "task already completed");

        }

        taskLineRepository.deleteByTaskIdAndAppliedFalse(taskId);

        if (request.lines() != null) {

            for (ReplenishmentTaskLineDto dto : request.lines()) {

                String lineType = dto.lineType().toUpperCase();
                if ("RESTOCK".equals(lineType)) {
                    deviceSlotService.validateRestockLine(
                            task.getDeviceId(), dto.slotId(), dto.skuId(), dto.quantity());
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

        }

        if (!"IN_PROGRESS".equals(task.getStatus())) {

            task.setStatus("IN_PROGRESS");

            taskRepository.save(task);

        }

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

                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "task not found"));

        if ("COMPLETED".equals(task.getStatus())) {
            completeRouteIfReady(task.getRouteId());
            return toTaskDto(task);
        }

        List<ReplenishmentTaskLine> pending = taskLineRepository.findByTaskIdAndAppliedFalse(taskId);

        if (!pending.isEmpty()
                && task.getOutboundId() != null
                && !inTransitService.hasOpenForDevice(task.getOutboundId(), task.getDeviceId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "outbound is not in transit or has already been received for this device");
        }

        String refId = String.valueOf(taskId);

        for (ReplenishmentTaskLine line : pending) {

            inventoryLotService.applyReplenishmentLine(task.getDeviceId(), line, operatorId, refId);

            if ("RESTOCK".equalsIgnoreCase(line.getLineType())) {
                deviceSlotService.recordRestock(task.getDeviceId(), line.getSlotId());
            }

            line.setApplied(true);

            taskLineRepository.save(line);

        }

        task.setStatus("COMPLETED");

        task.setCompletedAt(Instant.now());

        inTransitService.receiveForDevice(task.getOutboundId(), task.getDeviceId());
        warehouseService.markDeviceHandoverReceived(task.getOutboundId(), task.getDeviceId());

        task = taskRepository.save(task);
        completeRouteIfReady(task.getRouteId());
        return toTaskDto(task);

    }

    private void completeRouteIfReady(Long routeId) {
        if (routeId == null) {
            return;
        }
        List<ReplenishmentTask> routeTasks = taskRepository.findByRouteId(routeId);
        if (!routeTasks.isEmpty() && routeTasks.stream().allMatch(item -> "COMPLETED".equals(item.getStatus()))) {
            routeRepository.findById(routeId).ifPresent(route -> {
                route.setStatus("COMPLETED");
                routeRepository.save(route);
            });
        }
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
                t.getRequestId(), t.getCreatedAt()
        );

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

        return new PullOffTaskDto(

                task.getTaskId(), task.getDeviceId(), task.getSkuId(), task.getLotId(),

                task.getBatchNo(), task.getQuantity(), task.getReason(), task.getStatus(),

                task.getCreatedAt()

        );

    }

}
