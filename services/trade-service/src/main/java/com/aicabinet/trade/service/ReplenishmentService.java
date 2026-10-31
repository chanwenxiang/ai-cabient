package com.aicabinet.trade.service;



import com.aicabinet.common.dto.*;
import com.aicabinet.trade.support.ApiMessages;
import com.aicabinet.trade.support.DeviceLocationSupport;
import com.aicabinet.trade.support.SkuDisplayNames;
import com.aicabinet.trade.support.SkuOnShelf;
import com.aicabinet.trade.support.SkuStockLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aicabinet.trade.domain.DeviceInfo;
import com.aicabinet.trade.domain.DeviceSkuInventory;

import com.aicabinet.trade.domain.DeviceSkuInventoryId;

import com.aicabinet.trade.domain.DeviceSkuLot;

import com.aicabinet.trade.domain.PullOffTask;

import com.aicabinet.trade.domain.ReplenishmentRoute;

import com.aicabinet.trade.domain.ReplenishmentTask;

import com.aicabinet.trade.domain.ReplenishmentTaskLine;

import com.aicabinet.trade.domain.WarehouseOutboundLine;

import com.aicabinet.trade.mapper.DeviceInfoMapper;
import com.aicabinet.trade.mapper.SkuCatalogMapper;
import com.aicabinet.trade.mapper.DeviceSkuInventoryMapper;

import com.aicabinet.trade.mapper.DeviceSkuLotMapper;

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
import java.util.Set;

import java.util.stream.Collectors;



@Service

public class ReplenishmentService {
    private static final String STATUS_IN_PROGRESS = "IN_PROGRESS";
    private static final String STATUS_COMPLETED = "COMPLETED";
    private static final String STATUS_CANCELLED = "CANCELLED";
    private static final String RESTOCK = "RESTOCK";
    /** 下架行类型（唯一另一合法值；与 InventoryLotService.applyReplenishmentLine 的 apply 白名单同口径）。 */
    private static final String PULL_OFF = "PULL_OFF";
    private static final String STATUS_PENDING = "PENDING";
    private static final String PLANNED = "PLANNED";
    /** 出库单状态（与 WarehouseService 内部常量同口径，那边是 private，故此处本地复写）。 */
    private static final String OUTBOUND_DRAFT = "DRAFT";
    private static final String OUTBOUND_RECEIVED = "RECEIVED";


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
    private final RestockSnapshotService restockSnapshotService;

    private final InTransitService inTransitService;

    private final SessionService sessionService;

    private final NotificationService notificationService;
    private final DistributedLockService distributedLockService;
    private final SystemConfigService systemConfigService;
    private final SkuCatalogMapper skuCatalogMapper;



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
                                NotificationService notificationService,
                                DistributedLockService distributedLockService,
                                SystemConfigService systemConfigService,
            SkuCatalogMapper skuCatalogMapper,
                                RestockSnapshotService restockSnapshotService,
                                @Lazy ReplenishmentService self) {

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
        this.notificationService = notificationService;
        this.distributedLockService = distributedLockService;
        this.systemConfigService = systemConfigService;
        this.skuCatalogMapper = skuCatalogMapper;
        this.restockSnapshotService = restockSnapshotService;

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
        Map<String, java.util.Set<String>> lotSkusByDevice = new java.util.HashMap<>();
        List<DeviceInventoryDto> dtos = rows.stream()
                .map(inv -> toInventoryDto(inv, sellableByDevice, lotSkusByDevice))
                .toList();
        if (lowStockOnly) {
            // 低库存/缺货面只收在架 SKU（2026-10-30 拍板）：下架行可能仍压着账面，
            // 清零前靠假库存隐身、清零后会被读成缺货——都不得进「需补货」视图。
            // 判据与 WarehouseService.onlyOnShelf / DeviceAssetService 缺货面同一条。
            return onShelfOnly(dtos.stream()
                    .filter(d -> SkuStockLevel.of(d.quantity(), d.lowThreshold()).warning())
                    .toList());
        }
        return dtos;

    }

    /** 低库存面过滤：只保留 {@code sku_catalog.status=ACTIVE} 的行。 */
    private List<DeviceInventoryDto> onShelfOnly(List<DeviceInventoryDto> rows) {
        if (rows.isEmpty()) {
            return rows;
        }
        Set<String> skuIds = rows.stream().map(DeviceInventoryDto::skuId)
                .filter(s -> s != null && !s.isBlank())
                .collect(Collectors.toSet());
        if (skuIds.isEmpty()) {
            return rows;
        }
        Set<String> onShelf = skuCatalogMapper.selectList(Wrappers.<com.aicabinet.trade.domain.SkuCatalog>lambdaQuery()
                        .in(com.aicabinet.trade.domain.SkuCatalog::getSkuId, skuIds))
                .stream()
                .filter(SkuOnShelf::isOnShelf)
                .map(com.aicabinet.trade.domain.SkuCatalog::getSkuId)
                .collect(Collectors.toSet());
        return rows.stream().filter(d -> onShelf.contains(d.skuId())).toList();
    }



    @Transactional

    public DeviceInventoryDto upsertInventory(Long operatorId, DeviceInventoryDto body) {

        DeviceSkuInventoryId id = new DeviceSkuInventoryId(body.deviceId(), body.skuId());

        var existing = inventoryRepository.findById(id);
        DeviceSkuInventory inv = existing.orElseGet(() -> {

            DeviceSkuInventory n = new DeviceSkuInventory();

            n.setId(id);

            return n;

        });
        if (existing.isPresent()) {
            com.aicabinet.trade.support.OptimisticLocking.requireMatchingInventoryVersion(
                    body.inventoryVersion(), inv.getVersion());
        }

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
    public PageResult<PullOffTaskDto> listOpenPullOffTasksPage(String deviceId, int page, int size) {
        int p = Math.max(page, 0);
        int s = Math.min(Math.max(size, 1), 100);
        var result = pullOffTaskRepository.searchPage("OPEN", deviceId, p, s);
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
                .map(task -> toFulfillmentTaskDto(task, routeById.get(task.getRouteId()),
                        unassignedRestockByTask(result.getRecords())))
                .toList();
        return new PageResult<>(items, p, s, result.getTotal());
    }

    /**
     * 批量聚合各任务「未分配货道的 RESTOCK 行数」（applied=false 且 slot_id 空）。
     * CB-038（2026-10-14）重建「待分配」能力：替代已删的 admin 端逐任务拉明细（N 次请求），
     * 改为列表一次聚合；一台任务 0 = 货道全就绪。
     *
     * <p>铁律 78：开门柜（判据=将邑绑定）按商品备货，补货行恒无货道（{@code slot_id} 为空是常态，
     * 见 {@link #applyPendingReplenishmentLines} 的归一逻辑），对它们「待分配」恒等「未上架行数」⇒ 徽章常亮、
     * 「仅待分配」筛选命中全部开门柜。故开门柜任务一律不计数。
     */
    private Map<Long, Integer> unassignedRestockByTask(List<ReplenishmentTask> tasks) {
        if (tasks.isEmpty()) {
            return Map.of();
        }
        List<Long> taskIds = tasks.stream()
                .map(ReplenishmentTask::getTaskId)
                .filter(java.util.Objects::nonNull)
                .toList();
        if (taskIds.isEmpty()) {
            return Map.of();
        }
        List<Map<String, Object>> rows = taskLineRepository.selectMaps(Wrappers.<ReplenishmentTaskLine>query()
                .select("task_id", "COUNT(*) AS cnt")
                .in("task_id", taskIds)
                .eq("applied", false)
                .eq("line_type", "RESTOCK")
                .and(w -> w.isNull("slot_id").or().eq("slot_id", ""))
                .groupBy("task_id"));
        Map<Long, Integer> byTask = new HashMap<>();
        Map<Long, String> deviceByTask = new HashMap<>();
        for (ReplenishmentTask t : tasks) {
            if (t.getTaskId() != null && t.getDeviceId() != null && !t.getDeviceId().isBlank()) {
                deviceByTask.put(t.getTaskId(), t.getDeviceId().trim());
            }
        }
        Map<String, Boolean> doorByDevice = new HashMap<>();
        for (Map<String, Object> row : rows) {
            Object tid = row.get("task_id");
            Object cnt = row.get("cnt");
            if (tid instanceof Number idNum && cnt instanceof Number cntNum) {
                String device = deviceByTask.get(idNum.longValue());
                if (device != null && doorByDevice.computeIfAbsent(device, deviceSlotService::isDoorCabinet)) {
                    continue;
                }
                byTask.put(idNum.longValue(), cntNum.intValue());
            }
        }
        return byTask;
    }

    @Transactional(readOnly = true)
    public ReplenishmentOpsSummaryDto opsSummary() {
        long pending = taskRepository.countByStatusIn(List.of(STATUS_PENDING, STATUS_IN_PROGRESS));
        long fulfilled = taskRepository.countByStatusIn(List.of(STATUS_COMPLETED));
        Long planned = routeRepository.selectCount(Wrappers.<ReplenishmentRoute>lambdaQuery()
                .in(ReplenishmentRoute::getStatus, PLANNED, STATUS_IN_PROGRESS));
        long plannedRoutes = planned == null ? 0 : planned;
        // 要货申请已下线（V346）：字段保留以稳定 API 形状，恒为 0
        long pendingRequests = 0L;
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
            // Route geo JSON is optional metadata; planning result still persists without it.
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

        // 同事务生成出库单：失败则整单回滚，避免吞异常导致 UnexpectedRollback→500 或半成功路线。
        // 业务口径（2026-10-28）：补货=自提自送，无发运环节，建出库单后立即种现场补货明细。
        com.aicabinet.common.dto.WarehouseOutboundDto outbound =
                warehouseService.createOutboundForRoute(route.getRouteId(), null, route.getAssigneeUserId());
        if (outbound != null && outbound.outboundId() != null) {
            self.generateLinesFromOutbound(outbound.outboundId());
        }

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
        // 开门柜按商品备货（判据=将邑绑定）：不走货道分配，整行落无货道行
        boolean doorCabinet = deviceSlotService.isDoorCabinet(deviceId);
        for (Map.Entry<String, Integer> entry : skuQty.entrySet()) {
            String skuId = entry.getKey();
            int need = entry.getValue() == null ? 0 : Math.max(0, entry.getValue());
            if (skuId == null || skuId.isBlank() || need <= 0) {
                continue;
            }
            List<DeviceSlotService.SlotRestockAllocation> allocations = doorCabinet
                    ? List.of()
                    : deviceSlotService.allocateRestockQuantity(deviceId, skuId, need);
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
                if (!doorCabinet) {
                    log.warn("seedDraftRestockLines: {} units without slot headroom task={} sku={}",
                            remain, taskId, skuId);
                }
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
        // 开门柜按商品备货：不分配货道，整行落 slotId=null（否则走分配路径会把行绑回残留货道，
        // 或 allocated=0 时整行被当成「超容量」丢掉）
        if (deviceSlotService.isDoorCabinet(task.getDeviceId())) {
            ReplenishmentTaskLine line = new ReplenishmentTaskLine();
            line.setTaskId(task.getTaskId());
            line.setLineType(RESTOCK);
            line.setSkuId(ol.getSkuId());
            line.setBatchNo(ol.getBatchNo());
            line.setExpiryDate(ol.getExpiryDate());
            line.setQuantity(ol.getQuantity());
            line.setApplied(false);
            taskLineRepository.save(line);
            return;
        }
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
        // 终态不可复活（fail-closed）：状态集合与文案跟开门 ensureRestockDoorAllowed **共用一套**。
        // 旧行为只挡「有无可履约明细」，对 CANCELLED 会把状态改回 IN_PROGRESS（见方法末尾 setStatus 分支），
        // 而「取消空任务」正是运营用来解冻柜机的手段；冻结判据是 status=IN_PROGRESS 且 checkInAt≠null
        // ⇒ 一次签到就能撤销运营刚解除的冻结，柜机重新对消费者停售。要复活必须走显式改派/重建任务，
        // 不能靠现场签到把终态改回去。
        if (STATUS_COMPLETED.equals(task.getStatus()) || STATUS_CANCELLED.equals(task.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ApiMessages.REPLENISHMENT_TASK_FINISHED);
        }
        assertTaskHasFulfillableWork(task);
        DeviceInfo device = deviceRepository.findById(task.getDeviceId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.DEVICE_NOT_FOUND));
        boolean deviceHasCoords = DeviceLocationSupport.hasCoords(device);
        boolean requestHasCoords = request != null
                && request.latitude() != null
                && request.longitude() != null;
        boolean requireLocation = systemConfigService.getBoolean(
                SystemConfigService.REPLENISHMENT_CHECK_IN_REQUIRE_LOCATION, true);
        if (requireLocation) {
            // 无坐标拒签（fail-closed）：柜机自己没有点位坐标时**不得**放行。
            // 旧逻辑把「设备无坐标」当成「无需校验」，只在 deviceHasCoords=true 时才要求签到带定位，
            // 于是「建柜时漏填坐标」的柜机永久绕过了地理围栏 —— 那是静默失效，不是宽松策略。
            if (!deviceHasCoords) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        ApiMessages.REPLENISHMENT_CHECK_IN_DEVICE_LOCATION_MISSING);
            }
            if (!requestHasCoords) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        ApiMessages.REPLENISHMENT_CHECK_IN_LOCATION_REQUIRED);
            }
        }
        if (requestHasCoords) {
            int maxDistanceM = systemConfigService.getInt(
                    SystemConfigService.REPLENISHMENT_CHECK_IN_MAX_DISTANCE_M, 500);
            validateCheckInLocation(device, request.latitude(), request.longitude(), maxDistanceM);
            task.setCheckInLat(request.latitude());
            task.setCheckInLng(request.longitude());
        }
        task.setCheckInAt(Instant.now());
        // 走到这里状态只可能是 PENDING / IN_PROGRESS —— 终态已在方法开头被拒，
        // 所以这个分支不再具备「把已取消的任务复活」的能力。
        if (!STATUS_IN_PROGRESS.equals(task.getStatus())) {
            task.setStatus(STATUS_IN_PROGRESS);
        }
        task = taskRepository.save(task);
        reopenRouteIfActive(task.getRouteId());
        return toTaskDto(task);
    }

    /**
     * 签到前须有可履约明细：任务行已存在，或关联出库对该柜已有明细（发运后可生成任务行）。
     * 否则空任务签到会冻柜，且历史上无法 cancel-empty（BUG-010）。
     */
    private void assertTaskHasFulfillableWork(ReplenishmentTask task) {
        if (!taskLineRepository.findByTaskIdOrderByLineIdAsc(task.getTaskId()).isEmpty()) {
            return;
        }
        if (task.getOutboundId() != null
                && warehouseService.hasOutboundLinesForDevice(task.getOutboundId(), task.getDeviceId())) {
            return;
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ApiMessages.REPLENISHMENT_CHECK_IN_NO_LINES);
    }

    private void validateCheckInLocation(DeviceInfo device, double lat, double lng, int maxDistanceM) {
        if (device.getLatitude() == null || device.getLongitude() == null) {
            return;
        }
        if (maxDistanceM <= 0) {
            return;
        }
        double distM = haversineMeters(device.getLatitude(), device.getLongitude(), lat, lng);
        if (distM > maxDistanceM) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    String.format(ApiMessages.REPLENISHMENT_CHECK_IN_TOO_FAR,
                            Math.round(distM), maxDistanceM));
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
        // 开门柜提交数量按台账封顶（2026-10-28 用户实测 EG943322621391：台账可补 2 件，
        // 小程序数量却能加到 9）。货道柜有 validateRestockLine/自动分配截断两层钳制，
        // 开门柜此前提交与生成两层都不设限，这是缺口不是设计。
        if (deviceSlotService.isDoorCabinet(task.getDeviceId())) {
            assertDoorCabinetHeadroom(task, request);
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

    /**
     * D28-A5（CB-075）：开门柜可补量的**唯一出处**——提交封顶与商户端录入共用同一个数，
     * 别再各算一层（此前小程序只算「上限 − 在架」，少扣他单在途 ⇒「能加进去、点提交才 400」）。
     * 可补量 = 台账 capacity − 在架 quantity − **他人**在途。
     * 在途必须排除本任务自己的出库单（见 InTransitService.qtyBySkuForDeviceExcludingOutbound、
     * CB-075 结论 ②）：本单已领未上柜就是这一趟要装进柜机的货，扣掉等于自己挡自己上架。
     * capacity≤0 或未建台账行的 SKU 不进结果＝不设限（上限依据缺失时 fail-open，录入容量后自动生效）。
     */
    private Map<String, ReplenishmentHeadroomDto> doorCabinetHeadroomBySku(ReplenishmentTask task) {
        Map<String, DeviceSkuInventory> invBySku = inventoryRepository.findByIdDeviceId(task.getDeviceId())
                .stream()
                .collect(Collectors.toMap(DeviceSkuInventory::getSkuId, i -> i, (a, b) -> a, LinkedHashMap::new));
        Map<String, Integer> otherInTransitBySku = inTransitService
                .qtyBySkuForDeviceExcludingOutbound(task.getDeviceId(), task.getOutboundId());
        Map<String, ReplenishmentHeadroomDto> headroomBySku = new LinkedHashMap<>();
        for (DeviceSkuInventory inv : invBySku.values()) {
            if (inv.getCapacity() <= 0) {
                continue;
            }
            int otherInTransit = otherInTransitBySku.getOrDefault(inv.getSkuId(), 0);
            int room = Math.max(0, inv.getCapacity() - inv.getQuantity() - otherInTransit);
            headroomBySku.put(inv.getSkuId(), new ReplenishmentHeadroomDto(
                    inv.getSkuId(), inv.getCapacity(), inv.getQuantity(), otherInTransit, room));
        }
        return headroomBySku;
    }

    /**
     * D28-A5：只读下发给商户端的录入上限。非开门柜返回空列表——货道柜的上限来自货道
     * （{@code device_slot}），把台账水位当成另一套上限会让两页数字当面打架。
     * 🔴 判据与提交校验同一个（{@code deviceSlotService.isDoorCabinet}），别在这里改柜型判定。
     */
    @Transactional(readOnly = true)
    public List<ReplenishmentHeadroomDto> taskHeadroom(ReplenishmentTask task) {
        if (task == null || !deviceSlotService.isDoorCabinet(task.getDeviceId())) {
            return List.of();
        }
        return List.copyOf(doorCabinetHeadroomBySku(task).values());
    }

    /**
     * 开门柜提交数量台账封顶（2026-10-28）：口径见 {@link #doorCabinetHeadroomBySku}，
     * 同 SKU 多行（多批次）按合计校验。并发口子：submitTaskLines 只锁任务不锁设备，两任务并发提交
     * 同一 SKU 理论可合计越界；商户端单操作员场景下窗口极小，完成链入库仍以实际清点为准。
     */
    private void assertDoorCabinetHeadroom(ReplenishmentTask task, SubmitReplenishmentLinesRequest request) {
        if (request.lines() == null || request.lines().isEmpty()) {
            return;
        }
        Map<String, Integer> sumBySku = new LinkedHashMap<>();
        for (ReplenishmentTaskLineDto dto : request.lines()) {
            if (dto == null || dto.skuId() == null || dto.skuId().isBlank()) {
                continue;
            }
            if (!RESTOCK.equals(normalizeTaskLineType(dto.lineType()))) {
                continue;
            }
            sumBySku.merge(dto.skuId().trim(), Math.max(0, dto.quantity()), Integer::sum);
        }
        if (sumBySku.isEmpty()) {
            return;
        }
        Map<String, ReplenishmentHeadroomDto> headroomBySku = doorCabinetHeadroomBySku(task);
        for (Map.Entry<String, Integer> e : sumBySku.entrySet()) {
            ReplenishmentHeadroomDto headroom = headroomBySku.get(e.getKey());
            if (headroom == null) {
                continue;
            }
            if (e.getValue() > headroom.room()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "商品 " + e.getKey() + " 超出台账可补容量：上限 " + headroom.capacity()
                                + "，在架 " + headroom.onShelfQty()
                                + "，他单已领未上柜 " + headroom.otherInTransitQty()
                                + "，最多再补 " + headroom.room());
            }
        }
    }

    private void persistSubmittedTaskLine(ReplenishmentTask task, Long taskId, ReplenishmentTaskLineDto dto) {
        // 小程序/联调常省略 lineType，默认按上架（RESTOCK）处理
        String lineType = normalizeTaskLineType(dto.lineType());
        // CB-042（2026-10-15）：①行类型白名单提前到提交时——此前未知类型会落库成功、
        // 到任务完成时才在 applyReplenishmentLine 炸 unknown lineType（提交了注定完不成的任务）；
        // ②0 件行不进任务清单（下架行与 RESTOCK 行同口径，见 persistRestockLines 的 P1-17 门禁）——
        // 行业拣货单按「补货标准-现库存」差值生成，0 差值不产生行；下架以实取数为准。
        if (!RESTOCK.equals(lineType) && !PULL_OFF.equals(lineType)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "lineType 仅支持 RESTOCK 或 PULL_OFF（sku=" + dto.skuId() + "）");
        }
        if (RESTOCK.equals(lineType)) {
            persistRestockLines(task, taskId, dto);
            return;
        }
        if (dto.quantity() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "下架数量必须大于 0（sku=" + dto.skuId() + "）");
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

    /** P0-2（2026-10-15）：本次补货会话是否留下真实重力实测（真实测 ⇒ 完成时不覆盖实盘）。 */
    private boolean hasRealRestockGravitySnapshot(ReplenishmentTask task) {
        return restockSnapshotService != null
                && restockSnapshotService.hasRealGravitySnapshot(task.getTaskId());
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
        boolean expectReceive = validateOutboundBeforeComplete(operatorId, task, pending);
        // P0-2（2026-10-15）：有真实重力实测 ⇒ 保留关门快照实盘，不用回写后账面覆盖
        boolean preservePhysical = hasRealRestockGravitySnapshot(task);
        int appliedRestockQty = applyPendingReplenishmentLines(task, pending, operatorId,
                String.valueOf(taskId), preservePhysical);
        // CB-051 收尾：RESTOCK 行应用后按柜×商品写「上次配送后库存」快照
        snapshotLastRestockQty(task.getDeviceId(), pending);
        deviceSlotService.clampDeviceOverCapacity(task.getDeviceId());
        task.setStatus(STATUS_COMPLETED);
        task.setCompletedAt(Instant.now());
        if (expectReceive) {
            receiveOutboundHandover(task, operatorId, appliedRestockQty, preservePhysical);
        }
        task = taskRepository.save(task);
        return finishCompletedTaskSideEffects(task, taskId);
    }

    /** 幂等重入：已完成后补签收在途并关闭会话。 */
    private ReplenishmentTaskDto finalizeAlreadyCompletedTask(
            ReplenishmentTask task, Long operatorId, Long taskId) {
        // 任务已完成但出库仍在途（先完成任务、后发运的联调顺序）时，补签收
        if (task.getOutboundId() != null
                && inTransitService.hasOpenForDevice(task.getOutboundId(), task.getDeviceId())) {
            receiveCatchUpHandover(task, operatorId, taskId, hasRealRestockGravitySnapshot(task));
        }
        return finishCompletedTaskSideEffects(task, taskId);
    }

    /**
     * 幂等重入的成对签收。🔴 只有任务**完全没有明细**时才按出库行回写柜机（整单签收）；
     * 有明细就只认已应用的实收，差额记 {@code remainder_qty}——否则会与已上架的任务行重复加库存。
     */
    private void receiveCatchUpHandover(
            ReplenishmentTask task, Long operatorId, Long taskId, boolean preservePhysical) {
        List<ReplenishmentTaskLine> existingLines = taskLineRepository.findByTaskIdOrderByLineIdAsc(taskId);
        if (existingLines.isEmpty()) {
            restockFromOutboundLines(task, operatorId, "OB-" + task.getOutboundId(), preservePhysical);
            receiveHandoverPairFull(task.getOutboundId(), task.getDeviceId());
            return;
        }
        receiveHandoverPair(task.getOutboundId(), task.getDeviceId(), receivedQtyBySkuOf(existingLines));
    }

    /**
     * CB-051：补货完成后写「上次配送后库存」快照（device_sku_inventory.last_restock_qty）。
     * 只覆盖本次 RESTOCK 行涉及的柜×商品；快照值=应用后现行 quantity（与批次账本口径一致）。
     */
    private void snapshotLastRestockQty(String deviceId, List<ReplenishmentTaskLine> appliedLines) {
        List<String> skuIds = appliedLines.stream()
                .filter(l -> RESTOCK.equalsIgnoreCase(l.getLineType()))
                .map(ReplenishmentTaskLine::getSkuId)
                .filter(skuId -> skuId != null && !skuId.isBlank())
                .distinct()
                .toList();
        if (skuIds.isEmpty()) {
            return;
        }
        for (DeviceSkuInventory inv : inventoryRepository.findByIdDeviceId(deviceId)) {
            if (skuIds.contains(inv.getSkuId())) {
                inv.setLastRestockQty(inv.getQuantity());
                // 复合主键实体禁 updateById（Invalid bound statement）——wrapper 更新
                inventoryRepository.update(inv, com.baomidou.mybatisplus.core.toolkit.Wrappers
                        .<DeviceSkuInventory>lambdaUpdate()
                        .eq(DeviceSkuInventory::getDeviceId, deviceId)
                        .eq(DeviceSkuInventory::getSkuId, inv.getSkuId()));
            }
        }
    }

    /**
     * 校验仓配发运状态；返回是否需要在途签收。
     * 无在途但出库单有明细且仍有待上架行时，按自提自送口径补一次发运（见 {@link #shipForSelfPickup}）。
     */
    private boolean validateOutboundBeforeComplete(
            Long operatorId, ReplenishmentTask task, List<ReplenishmentTaskLine> pending) {
        if (task.getOutboundId() == null) {
            return false;
        }
        if (inTransitService.hasOpenForDevice(task.getOutboundId(), task.getDeviceId())) {
            return true;
        }
        boolean hasOutboundLines = warehouseService.hasOutboundLinesForDevice(
                task.getOutboundId(), task.getDeviceId());
        if (!hasOutboundLines || pending.isEmpty()) {
            return false;
        }
        return shipForSelfPickup(operatorId, task);
    }

    /**
     * 自提自送口径（2026-10-28 用户拍板）下的发运补齐。
     *
     * <p>口径：补货员从自己分仓领货、自己拉去上柜，没有采购/物流那套「拣货→发运→在途→签收」
     * 的两段等待。但「领货出门」必须扣一次仓库存，而该扣减只存在于
     * {@link WarehouseService#shipOutbound}。2026-10-28 把建单链路改成「建出库单后立即种现场
     * 明细」时没补这一步，后果有两条，且都只在真机上才暴露：
     * ① 出库单永远停在 DRAFT ⇒ {@link #validateOutboundBeforeComplete} 判为「未发运」⇒ 商户端
     * 「确认全部上架」被 409 拦住，补货任务根本无法完成；
     * ② 仓库存永不扣减 ⇒ 账实不符，出库单最终悬挂成孤儿单。
     *
     * <p>修法：把这一步挪到「确认上架」这一刻——仓扣减对齐真实上柜时点，任务中途取消也不会
     * 提前扣库。发运产生的在途行由随后的 {@link #receiveOutboundHandover} 成对签收，不残留。
     */
    private boolean shipForSelfPickup(Long operatorId, ReplenishmentTask task) {
        Long outboundId = task.getOutboundId();
        String status = warehouseService.getOutbound(outboundId).status();
        if (STATUS_CANCELLED.equals(status) || OUTBOUND_RECEIVED.equals(status)) {
            // 已作废（仓库存已回仓）或已签收（其它路径已收口）⇒ 不再补发运，按任务行独立上架
            return false;
        }
        if (OUTBOUND_DRAFT.equals(status)) {
            warehouseService.markPicked(outboundId);
        }
        warehouseService.shipOutbound(operatorId, outboundId);
        if (!inTransitService.hasOpenForDevice(outboundId, task.getDeviceId())) {
            // 发运后仍无在途 ⇒ 出库单状态异常，宁可拦住也不能让仓库存白扣
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    ApiMessages.REPLENISHMENT_OUTBOUND_NOT_IN_TRANSIT);
        }
        return true;
    }

    private int applyPendingReplenishmentLines(
            ReplenishmentTask task, List<ReplenishmentTaskLine> pending, Long operatorId, String refId,
            boolean preservePhysical) {
        int appliedRestockQty = 0;
        // 开门柜历史行归一：旧任务行可能带着已删/残留货道号，上架时一并清掉
        boolean doorCabinet = deviceSlotService.isDoorCabinet(task.getDeviceId());
        for (ReplenishmentTaskLine line : pending) {
            if (doorCabinet && line.getSlotId() != null) {
                line.setSlotId(null);
                // 随后的 save 走 updateById，默认跳过 null ⇒ 残留货道号清不掉，须显式 SQL
                taskLineRepository.clearSlotId(line.getLineId());
            }
            inventoryLotService.applyReplenishmentLine(task.getDeviceId(), line, operatorId, refId);
            if (RESTOCK.equalsIgnoreCase(line.getLineType())) {
                deviceSlotService.recordRestock(task.getDeviceId(), line.getSlotId(), preservePhysical);
                appliedRestockQty += line.getQuantity();
            }
            line.setApplied(true);
            taskLineRepository.save(line);
        }
        return appliedRestockQty;
    }

    /**
     * P1-3：在途签收 + 交接收口的**唯一成对入口**——warehouse_in_transit 与
     * outbound handover 两账必须成对收口（漏一半即脏账，一致性巡检 OUTBOUND_HANDOVER 兜底）。
     * 新增签收场景一律调用本方法（或 {@link #receiveHandoverPairFull}），禁止散写两个服务方法。
     *
     * <p>CB-074：实收按 SKU 传入，未上满的差额写 {@code warehouse_outbound_line.remainder_qty}；
     * 在途照样结掉——货确实出了仓，差额是「领而未上」，不是「仍在途」。
     */
    private void receiveHandoverPair(Long outboundId, String deviceId, Map<String, Integer> receivedQtyBySku) {
        inTransitService.receiveForDevice(outboundId, deviceId);
        warehouseService.markDeviceHandoverReceived(outboundId, deviceId, receivedQtyBySku);
    }

    /** 整单全量签收：仅在「无现场明细、按出库行入柜」时使用（差额=0）。 */
    private void receiveHandoverPairFull(Long outboundId, String deviceId) {
        inTransitService.receiveForDevice(outboundId, deviceId);
        warehouseService.markDeviceHandoverReceivedFull(outboundId, deviceId);
    }

    /**
     * 出库结案：**柜机只按商户实收数量入账**，差额记在出库行 {@code remainder_qty}（领而未上）。
     *
     * <p>2026-10-28 真机事故：任务行只有「雪碧 ×2」（商户实际只上架 2 个雪碧），
     * 旧实现却把出库单上冰红茶 3、加多宝 3 的差额一并写进柜机台账，凭空多出 6 件，
     * 首页「库存偏低」从 2 变 0。根因是把「出库单上的领货量」当成了「现场实收量」。
     *
     * <p>2026-10-29 同族的另一半：旧实现此时又给整单打上「全量签收」，「领了没上」在三端查不出来，
     * 月结「上柜」列其实是领货量。现在差额落成行上的未结数量（CB-074：留在原单据行，
     * 既不自动退回分仓——退回是第二张单据；也不补记进柜机——铁律 78）。
     *
     * <p>🔴 分支判据是「任务有没有 RESTOCK 行」，不是「实收合计是否大于 0」：
     * 铁律 80 要求现场没上的货把行数量减到 0，若按合计判空就会掉进整单入柜分支，
     * 把商户如实登记的 0 反转成柜机虚增（如实记数反被罚，必然逼着现场乱填）。
     *
     * <p>仅在任务**一条 RESTOCK 行都没有**时（建单即完成等无现场明细的场景）才按出库行整单入柜。
     */
    private int receiveOutboundHandover(
            ReplenishmentTask task, Long operatorId, int appliedRestockQty, boolean preservePhysical) {
        List<ReplenishmentTaskLine> taskLines =
                taskLineRepository.findByTaskIdOrderByLineIdAsc(task.getTaskId());
        boolean hasRestockLines = taskLines.stream()
                .anyMatch(line -> RESTOCK.equalsIgnoreCase(line.getLineType()));
        if (!hasRestockLines) {
            int fromOutbound = restockFromOutboundLines(
                    task, operatorId, "OB-" + task.getOutboundId(), preservePhysical);
            receiveHandoverPairFull(task.getOutboundId(), task.getDeviceId());
            return fromOutbound;
        }
        receiveHandoverPair(task.getOutboundId(), task.getDeviceId(), receivedQtyBySkuOf(taskLines));
        return appliedRestockQty;
    }

    /** 已应用的 RESTOCK 行按 SKU 汇总——即商户逐项确认的实收数量（数量为 0 的行不产出实收）。 */
    private static Map<String, Integer> receivedQtyBySkuOf(List<ReplenishmentTaskLine> lines) {
        Map<String, Integer> bySku = new LinkedHashMap<>();
        for (ReplenishmentTaskLine line : lines) {
            if (!RESTOCK.equalsIgnoreCase(line.getLineType()) || !line.isApplied()) {
                continue;
            }
            if (line.getQuantity() <= 0) {
                continue;
            }
            bySku.merge(line.getSkuId(), line.getQuantity(), Integer::sum);
        }
        return bySku;
    }

    private ReplenishmentTaskDto finishCompletedTaskSideEffects(ReplenishmentTask task, Long taskId) {
        completeRouteIfReady(task.getRouteId());
        // 完成补货后关闭仍占用柜机的补货开门会话，避免挡消费者
        if (sessionService != null) {
            sessionService.closeRestockSessionsForTask(taskId, "补货任务已完成，自动关闭开门会话");
        }
        return toTaskDto(task);
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
        // 开门柜按商品备货（判据=将邑绑定）：出库行携带的旧货道号一律忽略
        if (deviceSlotService.isDoorCabinet(deviceId)) {
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
    private int restockFromOutboundLines(ReplenishmentTask task, Long operatorId, String refId,
                                         boolean preservePhysical) {
        return restockFromOutboundLines(task, operatorId, refId, Map.of(), preservePhysical);
    }

    /**
     * 按出库行上架；{@code alreadyAppliedBySku} 表示同任务已通过现场行上架的数量，按 SKU 扣减后只补差额。
     */
    private int restockFromOutboundLines(ReplenishmentTask task, Long operatorId, String refId,
                                         Map<String, Integer> alreadyAppliedBySku, boolean preservePhysical) {
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
                received += applyOutboundLineRestock(task, ol, need, operatorId, refId, preservePhysical);
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
                                         Long operatorId, String refId, boolean preservePhysical) {
        List<DeviceSlotService.SlotRestockAllocation> allocations =
                resolveOutboundSlotAllocations(task.getDeviceId(), ol, need);
        if (allocations.isEmpty()) {
            return restockOutboundLineWithoutSlots(task, ol, need, operatorId, refId);
        }
        return restockOutboundLineWithAllocations(task, ol, allocations, operatorId, refId, preservePhysical);
    }

    private int restockOutboundLineWithoutSlots(ReplenishmentTask task, WarehouseOutboundLine ol, int need,
                                                Long operatorId, String refId) {
        // 开门柜必须走这里加库存：柜上有残留货道时 hasSkuSlots 仍为真，
        // 若只判 hasSkuSlots 会 return 0——补货完成却不加任何库存（静默丢账）
        if (deviceSlotService.isDoorCabinet(task.getDeviceId())
                || !deviceSlotService.hasSkuSlots(task.getDeviceId(), ol.getSkuId())) {
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
                                                   Long operatorId, String refId, boolean preservePhysical) {
        int received = 0;
        for (DeviceSlotService.SlotRestockAllocation alloc : allocations) {
            inventoryLotService.addRestock(
                    task.getDeviceId(), ol.getSkuId(),
                    new InventoryLotService.RestockCommand(
                            ol.getBatchNo(), null, ol.getExpiryDate(), alloc.quantity(),
                            alloc.slotCode(),
                            new InventoryLotService.LotMovementRef("REPLENISH", refId, operatorId)));
            deviceSlotService.recordRestock(task.getDeviceId(), alloc.slotCode(), preservePhysical);
            received += alloc.quantity();
        }
        return received;
    }

    /** RESTOCK：有货道按可补容量自动拆分/截断并分配货道；无货道整机落 slotId=null。 */
    private void persistRestockLines(ReplenishmentTask task, Long taskId, ReplenishmentTaskLineDto dto) {
        int qty = dto.quantity();
        // 审计 P1-17（2026-10-15）：qty≤0 明确拒绝而非静默丢弃——静默丢行会让商户以为
        // 提交成功但补货清单缺行（对不上的账）。前端已有数量钳制，此为后端兜底防线。
        if (qty <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "补货数量必须大于 0（sku=" + dto.skuId() + "）");
        }
        // 开门柜没有格子（2026-10-27 用户拍板）：该 SKU 在柜上无任何启用货道时
        // 不分配、不 400，整行落 slotId=null——与完成链 restockOutboundLineWithoutSlots
        // 同口径；旧草稿行携带的已删货道号也在此一并归零。
        // 2026-10-28 用户拍板「判据固定为将邑绑定」：开门柜**不论柜上有没有货道残留**，
        // 补货行都不绑货道（残留行只会把行绑回脏货道，再被 mp 容量门禁二次拦截）。
        if (deviceSlotService.isDoorCabinet(task.getDeviceId())
                || !deviceSlotService.hasSkuSlots(task.getDeviceId(), dto.skuId())) {
            saveRestockLine(taskId, dto, null, qty);
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
     * 安全取消空/卡死任务：无已上架明细即可取消（含已签到但未上架，避免空任务冻柜）。
     * 无出库或出库未交接可取消；有 SHIPPED 未签收时回仓并取消在途，不 DELETE 业务表。
     * 任务已 CANCELLED 时仍幂等收口其脏出库/在途（历史联调残留）。
     */
    @Transactional
    public ReplenishmentTaskDto cancelEmptyTask(Long operatorId, Long taskId) {
        // AU-111：与 completeTask/checkIn 同款收口——任务锁 + 锁内行锁重查，
        // 杜绝「并发签到/提交与取消交错读到旧空明细」竞态。
        return runWithTaskLock(taskId, () -> doCancelEmptyTask(operatorId, taskId));
    }

    private ReplenishmentTaskDto doCancelEmptyTask(Long operatorId, Long taskId) {
        ReplenishmentTask task = requireTaskForUpdate(taskId);
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
        // 已签到但尚未上架：允许取消以解除消费者冻柜（BUG-010）；已上架则禁止。
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

    /**
     * CB-018 ①（2026-10-09）：补货建议一键生成补货任务。原链路建议只能看
     * （GET /replenishment/suggest），运营要人工照抄到补货单——现一键转成
     * 路线（PLANNED）+ 任务（PENDING）+ RESTOCK 行（货道分配与幂等复用
     * {@link #seedDraftRestockLines}）。口径与建议页一致（fillToPar=false：
     * 仅 minLevel/ROP 触发，在途已抵扣）；批次（batchNo/生产/效期）留空，
     * 由仓配出库链路回填（generateLinesFromOutbound 已支持按出库行回写）。
     * 不做防重：同设备可重复生成，任务 notes 带 from-suggest: 前缀便于甄别合并。
     */
    @Transactional
    public ReplenishmentRouteDto createTaskFromSuggestion(Long operatorId, String deviceId, Long assigneeUserId) {
        String dev = deviceId == null ? "" : deviceId.trim();
        if (dev.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择设备");
        }
        Map<String, Integer> skuQty = new LinkedHashMap<>();
        for (ReplenishmentSuggestDto s : warehouseService.suggestForDevice(dev)) {
            if (s.suggestQty() > 0 && s.skuId() != null && !s.skuId().isBlank()) {
                skuQty.merge(s.skuId(), s.suggestQty(), Integer::sum);
            }
        }
        if (skuQty.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ApiMessages.REPLENISHMENT_NO_GAP);
        }
        ReplenishmentRoute route = new ReplenishmentRoute();
        route.setRouteName("建议-" + dev + "-" + LocalDate.now());
        // 自动建任务链路 operatorId=0（系统）⇒ 0 不落指派（留空=待派单），避免脏外键语义
        route.setAssigneeUserId(assigneeUserId != null && assigneeUserId > 0 ? assigneeUserId
                : (operatorId != null && operatorId > 0 ? operatorId : null));
        route.setPlannedDate(LocalDate.now());
        route.setStatus(PLANNED);
        route = routeRepository.save(route);

        ReplenishmentTask task = new ReplenishmentTask();
        task.setRouteId(route.getRouteId());
        task.setDeviceId(dev);
        task.setAssigneeUserId(route.getAssigneeUserId());
        task.setStatus(STATUS_PENDING);
        task.setNotes("from-suggest:" + dev);
        task = taskRepository.save(task);
        notifyTaskAssigned(task);
        // P0-1 修复（2026-10-15）：设备有主仓 ⇒ 同事务生成出库单（与 plan 路径同源，
        // 发运时 generateLinesFromOutbound 回填批次行，完成时仓扣减/在途签收闭环）；
        // 无主仓（未启用仓配的商户）⇒ 保持直加模式（草稿行，完成时直接加柜机账面）。
        // 注意：有出库单时不可再种草稿行——generateLinesForTask 会因未应用行存在而跳过回填。
        // 业务口径（2026-10-28 用户拍板）：补货员是「自提自送」——从自己分仓领货、自己拉上车去补柜，
        // 没有采购/物流那套「发运」环节。故有主仓时建出库单后须**立即** generateLinesFromOutbound
        // 种现场补货明细（与 selfInitiateTask 一致），否则任务明细恒为空、核对流程走不通。
        if (hasHomeWarehouse(dev)) {
            com.aicabinet.common.dto.WarehouseOutboundDto outbound =
                    warehouseService.createOutboundForRoute(route.getRouteId(), null, route.getAssigneeUserId());
            if (outbound != null && outbound.outboundId() != null) {
                self.generateLinesFromOutbound(outbound.outboundId());
            }
        } else {
            self.seedDraftRestockLines(task.getTaskId(), dev, skuQty);
        }
        return toRouteDto(route);
    }

    private boolean hasHomeWarehouse(String deviceId) {
        return deviceRepository.findById(deviceId)
                .map(d -> d.getHomeWarehouseId() != null && !d.getHomeWarehouseId().isBlank())
                .orElse(false);
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
        // 开门柜按商品备货（判据=将邑绑定）：不要求该 SKU 绑货道，也不受货道余量限幅
        if (deviceSlotService.isDoorCabinet(pull.getDeviceId())) {
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
            if (plan.restockAllocs().isEmpty()) {
                // 开门柜：无货道分配 ⇒ 整行落无货道行（不能因空分配而少建行）
                ReplenishmentTaskLine line = new ReplenishmentTaskLine();
                line.setTaskId(taskId);
                line.setLineType(lineType);
                line.setSkuId(pull.getSkuId());
                line.setBatchNo(pull.getBatchNo());
                line.setQuantity(plan.qty());
                line.setApplied(false);
                taskLineRepository.save(line);
                return;
            }
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
                                              Map<String, java.util.Set<String>> lotSkusByDevice) {
        String deviceId = inv.getId().getDeviceId();
        String skuId = inv.getId().getSkuId();
        // 与 DeviceSlotService.toInventoryDto / MerchantInventoryPortalService 同一条口径：
        // 只有「柜 + SKU」有批次行才走账本可售，否则回退台账 quantity。
        java.util.Set<String> lotSkus = lotSkusByDevice.computeIfAbsent(
                deviceId, inventoryLotService::lotLedgerSkuIds);
        Map<String, Integer> bySku = lotSkus.contains(skuId)
                ? sellableByDevice.computeIfAbsent(deviceId, inventoryLotService::sellableQtyBySku)
                : null;
        int qty = SkuStockLevel.sellableQty(inv, lotSkus, bySku);
        return new DeviceInventoryDto(
                deviceId, skuId,
                qty, inv.getCapacity(), inv.getLowThreshold(), inv.getUpdatedAt(),
                inv.getVersion(), inv.getLastRestockQty()
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
                // Corrupt route geo JSON: expose route without reconstructed waypoints.
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
                if (DeviceLocationSupport.hasCoords(d)) {
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



    /** 按任务 ID 取任务 DTO（自主发起补货等外部流程复用）。 */
    @Transactional(readOnly = true)
    public ReplenishmentTaskDto getTaskDto(Long taskId) {
        ReplenishmentTask task = taskRepository.findById(taskId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "补货任务不存在"));
        return toTaskDto(task);
    }

    private ReplenishmentTaskDto toTaskDto(ReplenishmentTask t) {
        String deviceName = null;
        Boolean deviceHasCoords = null;
        if (t.getDeviceId() != null) {
            DeviceInfo device = deviceRepository.findById(t.getDeviceId()).orElse(null);
            if (device != null) {
                deviceName = device.getDeviceName();
                // 与签到闸同一个判据（DeviceLocationSupport）—— 客户端据此前置禁用签到，
                // 避免「到柜前才吃 400」；两处若各写各的 null 判断就会漂移。
                deviceHasCoords = DeviceLocationSupport.hasCoords(device);
            }
        }
        ReplenishmentRoute route = t.getRouteId() == null
                ? null
                : routeRepository.findById(t.getRouteId()).orElse(null);
        return new ReplenishmentTaskDto(
                t.getTaskId(), t.getRouteId(), t.getDeviceId(), t.getAssigneeUserId(),
                t.getStatus(), t.getNotes(), t.getCompletedAt(),
                t.getCheckInAt(), t.getCheckInLat(), t.getCheckInLng(),
                resolveCheckInDistanceM(t),
                t.getOutboundId(), t.getCreatedAt(),
                deviceName,
                route != null ? route.getRouteName() : null,
                route != null ? route.getPlannedDate() : null,
                null,
                null,
                deviceHasCoords
        );
    }

    private ReplenishmentFulfillmentTaskDto toFulfillmentTaskDto(
            ReplenishmentTask task, ReplenishmentRoute route, Map<Long, Integer> unassignedByTask) {
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
                base.outboundId(),
                base.createdAt(),
                base.deviceName(),
                unassignedByTask.getOrDefault(task.getTaskId(), 0)
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

                line.getLineId(), line.getLineType(), line.getSkuId(), resolveSkuName(line.getSkuId()), line.getBatchNo(),

                line.getProductionDate(), line.getExpiryDate(), line.getQuantity(),

                line.getSlotId(), line.isApplied()

        );

    }

    /**
     * 明细展示商品名：名称+规格（如东鹏特饮250ml），与要货明细 formatReplenRequestProduct 口径一致；
     * 目录缺失时回落编码。
     */
    private String resolveSkuName(String skuId) {
        if (skuId == null || skuId.isBlank()) return null;
        var catalog = skuCatalogMapper.selectById(skuId);
        if (catalog == null) return skuId;
        String display = SkuDisplayNames.of(catalog);
        return display != null ? display : skuId;
    }



    private DeviceSkuLotDto toLotDto(DeviceSkuLot lot) {

        return new DeviceSkuLotDto(

                lot.getLotId(), lot.getDeviceId(), lot.getSkuId(), lot.getBatchNo(),

                lot.getProductionDate(), lot.getExpiryDate(), lot.getQuantity(),

                lot.getSlotId(), lot.getStatus()

        );

    }



    private PullOffTaskDto toPullOffDto(PullOffTask task) {
        // 开门柜无货道行 ⇒ totalHeadroomForSku 恒 0，照它禁用 RESTOCK 会给出假的「货道已满」
        // （后端 preparePullOffTaskPlan 对开门柜明确不设货道余量闸）。判据单源，别在这另立。
        boolean doorCabinet = deviceSlotService.isDoorCabinet(task.getDeviceId());
        int headroom = 0;
        if (!doorCabinet) {
            try {
                headroom = deviceSlotService.totalHeadroomForSku(task.getDeviceId(), task.getSkuId());
            } catch (Exception ignored) {
                headroom = 0;
            }
        }
        return new PullOffTaskDto(
                task.getTaskId(), task.getDeviceId(), task.getSkuId(), task.getLotId(),
                task.getBatchNo(), task.getQuantity(), task.getReason(), task.getStatus(),
                task.getCreatedAt(), Math.max(0, headroom), doorCabinet
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
