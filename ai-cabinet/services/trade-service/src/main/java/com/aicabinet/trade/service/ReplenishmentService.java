package com.aicabinet.trade.service;

import com.aicabinet.common.dto.DeviceInventoryDto;
import com.aicabinet.common.dto.PlanRouteRequest;
import com.aicabinet.common.dto.ReplenishmentRouteDto;
import com.aicabinet.common.dto.ReplenishmentTaskDto;
import com.aicabinet.common.dto.RouteWaypointDto;
import com.aicabinet.trade.domain.DeviceSkuInventory;
import com.aicabinet.trade.domain.DeviceSkuInventoryId;
import com.aicabinet.trade.domain.ReplenishmentRoute;
import com.aicabinet.trade.domain.ReplenishmentTask;
import com.aicabinet.trade.repository.DeviceSkuInventoryRepository;
import com.aicabinet.trade.repository.ReplenishmentRouteRepository;
import com.aicabinet.trade.repository.ReplenishmentTaskRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

@Service
public class ReplenishmentService {

    private final DeviceSkuInventoryRepository inventoryRepository;
    private final ReplenishmentRouteRepository routeRepository;
    private final ReplenishmentTaskRepository taskRepository;
    private final RoutePlanningService routePlanningService;
    private final ObjectMapper objectMapper;

    public ReplenishmentService(DeviceSkuInventoryRepository inventoryRepository,
                                ReplenishmentRouteRepository routeRepository,
                                ReplenishmentTaskRepository taskRepository,
                                RoutePlanningService routePlanningService,
                                ObjectMapper objectMapper) {
        this.inventoryRepository = inventoryRepository;
        this.routeRepository = routeRepository;
        this.taskRepository = taskRepository;
        this.routePlanningService = routePlanningService;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public List<DeviceInventoryDto> listInventory(String deviceId) {
        List<DeviceSkuInventory> rows = deviceId != null
                ? inventoryRepository.findByIdDeviceId(deviceId)
                : inventoryRepository.findAll();
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
        return toRouteDto(route);
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
    public ReplenishmentTaskDto completeTask(Long operatorId, Long taskId) {
        ReplenishmentTask task = taskRepository.findById(taskId)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "task not found"));
        task.setStatus("COMPLETED");
        task.setCompletedAt(Instant.now());
        return toTaskDto(taskRepository.save(task));
    }

    @Transactional(readOnly = true)
    public List<ReplenishmentTaskDto> myTasks(Long assigneeUserId) {
        return taskRepository.findByAssigneeUserIdAndStatus(assigneeUserId, "PENDING").stream()
                .map(this::toTaskDto)
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
                t.getStatus(), t.getNotes(), t.getCompletedAt()
        );
    }
}
