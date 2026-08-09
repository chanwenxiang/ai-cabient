package com.aicabinet.trade.service;

import com.aicabinet.common.dto.*;
import com.aicabinet.trade.domain.*;
import com.aicabinet.trade.mapper.*;
import com.aicabinet.trade.support.ApiMessages;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class WarehouseService {

    public static final String DEFAULT_WAREHOUSE_ID = "WH-DEMO-001";

    private final WarehouseMapper warehouseRepository;
    private final WarehouseInventoryMapper inventoryRepository;
    private final WarehouseInboundMapper inboundRepository;
    private final WarehouseInboundLineMapper inboundLineRepository;
    private final WarehouseOutboundMapper outboundRepository;
    private final WarehouseOutboundLineMapper outboundLineRepository;
    private final WarehouseMovementMapper movementRepository;
    private final DeviceSkuInventoryMapper deviceInventoryRepository;
    private final ReplenishmentTaskMapper taskRepository;
    private final ReplenishmentRouteMapper routeRepository;
    private final SkuCatalogMapper skuCatalogRepository;
    private final DeviceSlotService deviceSlotService;
    private final SalesVelocityService salesVelocityService;
    private final InTransitService inTransitService;

    public WarehouseService(WarehouseMapper warehouseRepository,
                            WarehouseInventoryMapper inventoryRepository,
                            WarehouseInboundMapper inboundRepository,
                            WarehouseInboundLineMapper inboundLineRepository,
                            WarehouseOutboundMapper outboundRepository,
                            WarehouseOutboundLineMapper outboundLineRepository,
                            WarehouseMovementMapper movementRepository,
                            DeviceSkuInventoryMapper deviceInventoryRepository,
                            ReplenishmentTaskMapper taskRepository,
                            ReplenishmentRouteMapper routeRepository,
                            SkuCatalogMapper skuCatalogRepository,
                            DeviceSlotService deviceSlotService,
                            SalesVelocityService salesVelocityService,
                            InTransitService inTransitService) {
        this.warehouseRepository = warehouseRepository;
        this.inventoryRepository = inventoryRepository;
        this.inboundRepository = inboundRepository;
        this.inboundLineRepository = inboundLineRepository;
        this.outboundRepository = outboundRepository;
        this.outboundLineRepository = outboundLineRepository;
        this.movementRepository = movementRepository;
        this.deviceInventoryRepository = deviceInventoryRepository;
        this.taskRepository = taskRepository;
        this.routeRepository = routeRepository;
        this.skuCatalogRepository = skuCatalogRepository;
        this.deviceSlotService = deviceSlotService;
        this.salesVelocityService = salesVelocityService;
        this.inTransitService = inTransitService;
    }

    @Transactional(readOnly = true)
    public List<WarehouseDto> listWarehouses() {
        return warehouseRepository.findAll().stream().map(this::toWarehouseDto).toList();
    }

    @Transactional(readOnly = true)
    public List<WarehouseInventoryDto> listInventory(String warehouseId) {
        String wh = resolveWarehouseId(warehouseId);
        return inventoryRepository.findByWarehouseIdOrderByExpiryDateAsc(wh).stream()
                .filter(i -> i.getQuantity() > 0)
                .map(this::toInventoryDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<WarehouseMovementDto> listMovements(String warehouseId) {
        String wh = resolveWarehouseId(warehouseId);
        return movementRepository.findTop100ByWarehouseIdOrderByCreatedAtDesc(wh).stream()
                .map(this::toMovementDto)
                .toList();
    }

    @Transactional
    public WarehouseInboundRequest inbound(Long operatorId, WarehouseInboundRequest request) {
        String wh = resolveWarehouseId(request.warehouseId());
        warehouseRepository.findById(wh).orElseThrow(() -> notFound("warehouse"));
        if (request.lines() == null || request.lines().isEmpty()) {
            throw badRequest("lines required");
        }
        WarehouseInbound inbound = new WarehouseInbound();
        inbound.setWarehouseId(wh);
        inbound.setRefNo(request.refNo());
        inbound.setNotes(request.notes());
        inbound.setOperatorId(operatorId);
        inbound = inboundRepository.save(inbound);

        for (WarehouseInboundLineDto dto : request.lines()) {
            validateInboundLine(dto);
            WarehouseInboundLine line = new WarehouseInboundLine();
            line.setInboundId(inbound.getInboundId());
            line.setSkuId(dto.skuId());
            line.setBatchNo(dto.batchNo());
            line.setProductionDate(dto.productionDate());
            line.setExpiryDate(dto.expiryDate());
            line.setQuantity(dto.quantity());
            inboundLineRepository.save(line);
            addWarehouseStock(wh, dto.skuId(), dto.batchNo(), dto.productionDate(), dto.expiryDate(), dto.quantity());
            recordWarehouseMovement(wh, dto.skuId(), dto.batchNo(), "MANUAL_INBOUND",
                    dto.quantity(), "WAREHOUSE_INBOUND", String.valueOf(inbound.getInboundId()), operatorId);
        }
        return request;
    }

    @Transactional
    public void receivePurchaseStock(String warehouseId, String skuId, String batchNo,
                                     LocalDate productionDate, LocalDate expiryDate,
                                     int qty, int unitCostCents, Long operatorId,
                                     String refType, String refId) {
        if (qty <= 0) {
            throw badRequest("quantity must be positive");
        }
        String wh = resolveWarehouseId(warehouseId);
        warehouseRepository.findById(wh).orElseThrow(() -> notFound("warehouse"));
        WarehouseInbound inbound = new WarehouseInbound();
        inbound.setWarehouseId(wh);
        inbound.setRefNo(refType + "-" + refId);
        inbound.setNotes("purchase receive");
        inbound.setOperatorId(operatorId);
        if ("PURCHASE_ORDER".equals(refType)) {
            inbound.setPurchaseOrderId(parseLongOrNull(refId));
        }
        inbound = inboundRepository.save(inbound);

        WarehouseInboundLine line = new WarehouseInboundLine();
        line.setInboundId(inbound.getInboundId());
        line.setSkuId(skuId);
        line.setBatchNo(batchNo);
        line.setProductionDate(productionDate);
        line.setExpiryDate(expiryDate);
        line.setQuantity(qty);
        line.setUnitCostCents(unitCostCents);
        inboundLineRepository.save(line);

        addWarehouseStock(wh, skuId, batchNo, productionDate, expiryDate, qty);
        recordWarehouseMovement(wh, skuId, batchNo, "PURCHASE_RECEIVE", qty, refType, refId, operatorId);
    }

    @Transactional
    public void returnPurchaseStock(String warehouseId, String skuId, String batchNo, int qty,
                                    Long operatorId, String refType, String refId) {
        if (qty <= 0) {
            throw badRequest("quantity must be positive");
        }
        String wh = resolveWarehouseId(warehouseId);
        warehouseRepository.findById(wh).orElseThrow(() -> notFound("warehouse"));
        deductWarehouseStock(wh, skuId, batchNo, qty);
        recordWarehouseMovement(wh, skuId, batchNo, "PURCHASE_RETURN", -qty, refType, refId, operatorId);
    }

    /** 盘点差异调整：把账面库存直接对齐到实盘数量，并记录库存流水。 */
    @Transactional
    public void adjustStocktake(String warehouseId, String skuId, String batchNo,
                                LocalDate productionDate, LocalDate expiryDate,
                                int bookQty, int countedQty, Long operatorId, Long stocktakeId) {
        int delta = countedQty - bookQty;
        if (delta == 0) {
            return;
        }
        String wh = resolveWarehouseId(warehouseId);
        warehouseRepository.findById(wh).orElseThrow(() -> notFound("warehouse"));
        if (delta > 0) {
            addWarehouseStock(wh, skuId, batchNo, productionDate, expiryDate, delta);
        } else {
            deductWarehouseStock(wh, skuId, batchNo, -delta);
        }
        recordWarehouseMovement(wh, skuId, batchNo, "STOCKTAKE", delta,
                "STOCKTAKE", String.valueOf(stocktakeId), operatorId);
    }

    /** 货位操作同步仓库总库存：入库/出库调整仓库账面并记录流水；移库 delta=0 仅留痕。 */
    @Transactional
    public void binStockChange(String warehouseId, String skuId, String batchNo,
                               LocalDate productionDate, LocalDate expiryDate,
                               int deltaQty, Long operatorId, String refType, String refId) {
        if (deltaQty == 0) {
            return;
        }
        String wh = resolveWarehouseId(warehouseId);
        warehouseRepository.findById(wh).orElseThrow(() -> notFound("warehouse"));
        if (deltaQty > 0) {
            addWarehouseStock(wh, skuId, batchNo, productionDate, expiryDate, deltaQty);
        } else {
            deductWarehouseStock(wh, skuId, batchNo, -deltaQty);
        }
        recordWarehouseMovement(wh, skuId, batchNo, "BIN_STOCK", deltaQty, refType, refId, operatorId);
    }

    @Transactional(readOnly = true)
    public List<ReplenishmentSuggestDto> suggestForDevice(String deviceId) {
        return suggestForDevice(deviceId, false);
    }

    /**
     * @param fillToPar true=规划出库：按货道补到 PAR；false=运营建议：仅 minLevel/ROP 触发
     */
    @Transactional(readOnly = true)
    public List<ReplenishmentSuggestDto> suggestForDevice(String deviceId, boolean fillToPar) {
        String dev = deviceId.trim();
        Map<String, Integer> inTransitBySku = inTransitService.qtyBySkuForDevice(dev);
        Map<String, Integer> qtyBySku = new LinkedHashMap<>();
        Map<String, Integer> bookBySku = new LinkedHashMap<>();
        Map<String, Integer> parBySku = new LinkedHashMap<>();
        Map<String, String> reasonBySku = new LinkedHashMap<>();
        Map<String, SalesVelocityService.SkuVelocity> velocityBySku = salesVelocityService.velocityBySku(deviceId);
        List<SlotReplenishmentSuggestDto> slots = fillToPar
                ? deviceSlotService.suggestSlotsForOutboundFill(deviceId)
                : deviceSlotService.suggestSlotsForDevice(deviceId);
        for (SlotReplenishmentSuggestDto slot : slots) {
            if (slot.suggestQty() <= 0) {
                continue;
            }
            qtyBySku.merge(slot.skuId(), slot.suggestQty(), Integer::sum);
            bookBySku.merge(slot.skuId(), slot.bookQty(), Integer::sum);
            parBySku.merge(slot.skuId(), slot.parLevel(), Integer::sum);
            reasonBySku.putIfAbsent(slot.skuId(), slot.suggestReason());
        }
        if (!qtyBySku.isEmpty()) {
            List<ReplenishmentSuggestDto> fromSlots = qtyBySku.entrySet().stream()
                    .map(e -> {
                        String skuId = e.getKey();
                        SalesVelocityService.SkuVelocity velocity = velocityBySku.getOrDefault(
                                skuId, new SalesVelocityService.SkuVelocity(0, 0, 0, 0));
                        int book = bookBySku.getOrDefault(skuId, 0);
                        int par = parBySku.getOrDefault(skuId, 0);
                        int inTransit = inTransitBySku.getOrDefault(skuId, 0);
                        int rawSuggest = e.getValue();
                        return new ReplenishmentSuggestDto(
                                dev, skuId, book, par, 0,
                                Math.max(0, rawSuggest - inTransit), inTransit,
                                velocity.soldQty7d(), velocity.soldQty14d(), velocity.ropPoint(),
                                reasonBySku.getOrDefault(skuId, "PAR"));
                    })
                    .filter(s -> s.suggestQty() > 0)
                    .toList();
            if (!fromSlots.isEmpty()) {
                return fromSlots;
            }
        }
        if (fillToPar) {
            // 货道已满 PAR / 在途抵消后，再回退常规低库存/ROP 建议
            return suggestForDevice(deviceId, false);
        }
        List<ReplenishmentSuggestDto> lowStock = deviceInventoryRepository.findByIdDeviceId(dev).stream()
                .filter(i -> i.getQuantity() <= i.getLowThreshold())
                .map(i -> {
                    SalesVelocityService.SkuVelocity velocity = velocityBySku.getOrDefault(
                            i.getId().getSkuId(), new SalesVelocityService.SkuVelocity(0, 0, 0, 0));
                    String skuId = i.getId().getSkuId();
                    int inTransit = inTransitBySku.getOrDefault(skuId, 0);
                    int rawSuggest = Math.max(0, i.getCapacity() - i.getQuantity());
                    // 有货道陈列时按可补容量截断，避免出库远超货道
                    rawSuggest = capSuggestBySlotHeadroom(dev, skuId, rawSuggest);
                    return new ReplenishmentSuggestDto(
                            i.getId().getDeviceId(),
                            skuId,
                            i.getQuantity(),
                            i.getCapacity(),
                            i.getLowThreshold(),
                            Math.max(0, rawSuggest - inTransit), inTransit,
                            velocity.soldQty7d(), velocity.soldQty14d(), velocity.ropPoint(),
                            "LOW_STOCK");
                })
                .filter(s -> s.suggestQty() > 0)
                .toList();
        if (!lowStock.isEmpty()) {
            return lowStock;
        }
        return velocityBySku.entrySet().stream()
                .filter(e -> e.getValue().ropPoint() > 0)
                .map(e -> {
                    String skuId = e.getKey();
                    SalesVelocityService.SkuVelocity velocity = e.getValue();
                    int book = deviceInventoryRepository.findByIdDeviceId(dev).stream()
                            .filter(inv -> skuId.equals(inv.getId().getSkuId()))
                            .mapToInt(DeviceSkuInventory::getQuantity)
                            .sum();
                    if (book > velocity.ropPoint()) {
                        return null;
                    }
                    int target = Math.max(velocity.ropPoint() * 2, book + 1);
                    int inTransit = inTransitBySku.getOrDefault(skuId, 0);
                    int rawSuggest = Math.max(0, target - book);
                    rawSuggest = capSuggestBySlotHeadroom(dev, skuId, rawSuggest);
                    if (rawSuggest <= 0) {
                        return null;
                    }
                    return new ReplenishmentSuggestDto(
                            dev, skuId, book, target, velocity.ropPoint(),
                            Math.max(0, rawSuggest - inTransit), inTransit,
                            velocity.soldQty7d(), velocity.soldQty14d(), velocity.ropPoint(),
                            "ROP");
                })
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    /** 已绑定货道时，建议量不超过货道可补总容量。 */
    private int capSuggestBySlotHeadroom(String deviceId, String skuId, int rawSuggest) {
        if (rawSuggest <= 0 || !deviceSlotService.hasSkuSlots(deviceId, skuId)) {
            return rawSuggest;
        }
        return Math.min(rawSuggest, deviceSlotService.totalHeadroomForSku(deviceId, skuId));
    }

    @Transactional
    public WarehouseOutboundDto createOutboundFromLines(Long routeId, String deviceId, Long assigneeUserId,
                                                        java.util.Map<String, Integer> skuQty, String warehouseId) {
        Long outboundId = tryCreateOutboundFromLines(routeId, deviceId, assigneeUserId, skuQty, warehouseId);
        if (outboundId == null) {
            throw badRequest("outbound lines required");
        }
        return getOutbound(outboundId);
    }

    /**
     * 按 SKU 要货量尝试生成出库单；仓库无可用库存时返回 null（不抛错、不污染外层事务）。
     * 部分库存时尽力分配并保留出库单（requireFull=false），避免 409 导致外层接单事务 rollback-only。
     */
    @Transactional
    public Long tryCreateOutboundFromLines(Long routeId, String deviceId, Long assigneeUserId,
                                           java.util.Map<String, Integer> skuQty, String warehouseId) {
        if (skuQty == null || skuQty.isEmpty()) {
            return null;
        }
        String wh = resolveWarehouseId(warehouseId);
        WarehouseOutbound outbound = new WarehouseOutbound();
        outbound.setWarehouseId(wh);
        outbound.setRouteId(routeId);
        outbound.setAssigneeUserId(assigneeUserId);
        outbound.setStatus("DRAFT");
        outbound.setNotes("merchant replenishment request");
        outbound = outboundRepository.save(outbound);
        int allocatedLines = 0;
        for (var entry : skuQty.entrySet()) {
            if (entry.getValue() == null || entry.getValue() <= 0) {
                continue;
            }
            allocatedLines += allocateFefoToOutbound(
                    outbound.getOutboundId(), wh, deviceId, entry.getKey(), entry.getValue(), false);
        }
        if (allocatedLines <= 0) {
            outboundRepository.deleteById(outbound.getOutboundId());
            return null;
        }
        return outbound.getOutboundId();
    }

    @Transactional
    public WarehouseOutboundDto createOutboundForRoute(Long routeId, String warehouseId, Long assigneeUserId) {
        String wh = resolveWarehouseId(warehouseId);
        Optional<WarehouseOutbound> existing = outboundRepository.findByRouteId(routeId);
        if (existing.isPresent()) {
            WarehouseOutbound o = existing.get();
            boolean emptyDraft = "DRAFT".equals(o.getStatus())
                    && outboundLineRepository.findByOutboundIdOrderByLineIdAsc(o.getOutboundId()).isEmpty();
            if (!emptyDraft) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "outbound already exists for route");
            }
            // 历史空出库头：清理后重建明细
            for (ReplenishmentTask task : taskRepository.findByRouteId(routeId)) {
                if (o.getOutboundId().equals(task.getOutboundId())) {
                    task.setOutboundId(null);
                    taskRepository.save(task);
                }
            }
            outboundRepository.deleteById(o.getOutboundId());
        }
        List<ReplenishmentTask> tasks = taskRepository.findByRouteId(routeId);
        if (tasks.isEmpty()) {
            throw badRequest("route has no tasks");
        }

        WarehouseOutbound outbound = new WarehouseOutbound();
        outbound.setWarehouseId(wh);
        outbound.setRouteId(routeId);
        outbound.setAssigneeUserId(assigneeUserId);
        outbound.setStatus("DRAFT");
        outbound.setNotes("auto from route " + routeId);
        outbound = outboundRepository.save(outbound);

        int allocatedLines = 0;
        boolean hadSuggestQty = false;
        for (ReplenishmentTask task : tasks) {
            // 规划出库按补到 PAR，避免仅 minLevel 触发导致出库单无行
            List<ReplenishmentSuggestDto> suggestions = suggestForDevice(task.getDeviceId(), true);
            for (ReplenishmentSuggestDto s : suggestions) {
                if (s.suggestQty() <= 0) continue;
                hadSuggestQty = true;
                // 单 SKU 库存不足时尽量分配可用量，避免整单回滚成空出库头
                allocatedLines += allocateFefoToOutbound(
                        outbound.getOutboundId(), wh, task.getDeviceId(), s.skuId(), s.suggestQty(), false);
            }
            task.setOutboundId(outbound.getOutboundId());
            taskRepository.save(task);
        }
        if (allocatedLines <= 0) {
            for (ReplenishmentTask task : tasks) {
                if (outbound.getOutboundId().equals(task.getOutboundId())) {
                    task.setOutboundId(null);
                    taskRepository.save(task);
                }
            }
            outboundRepository.deleteById(outbound.getOutboundId());
            throw badRequest(hadSuggestQty
                    ? ApiMessages.REPLENISHMENT_WAREHOUSE_STOCK_INSUFFICIENT
                    : ApiMessages.REPLENISHMENT_NO_GAP);
        }
        return getOutbound(outbound.getOutboundId());
    }

    @Transactional(readOnly = true)
    public List<WarehouseOutboundDto> listOutbounds() {
        return outboundRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(o -> getOutbound(o.getOutboundId()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<WarehouseOutboundLine> outboundLinesForDevice(Long outboundId, String deviceId) {
        return outboundLineRepository.findByOutboundIdAndDeviceIdOrderByLineIdAsc(outboundId, deviceId);
    }

    @Transactional(readOnly = true)
    public WarehouseOutboundDto getOutbound(Long outboundId) {
        WarehouseOutbound outbound = outboundRepository.findById(outboundId)
                .orElseThrow(() -> notFound("outbound"));
        List<WarehouseOutboundLineDto> lines = outboundLineRepository.findByOutboundIdOrderByLineIdAsc(outboundId)
                .stream().map(this::toOutboundLineDto).toList();
        return new WarehouseOutboundDto(
                outbound.getOutboundId(), outbound.getWarehouseId(), outbound.getRouteId(),
                outbound.getStatus(), outbound.getAssigneeUserId(), outbound.getNotes(),
                outbound.getCreatedAt(), outbound.getShippedAt(), lines
        );
    }

    @Transactional
    public WarehouseOutboundDto markPicked(Long outboundId) {
        WarehouseOutbound outbound = outboundRepository.findById(outboundId)
                .orElseThrow(() -> notFound("outbound"));
        if ("SHIPPED".equals(outbound.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "already shipped");
        }
        List<WarehouseOutboundLine> lines = outboundLineRepository.findByOutboundIdOrderByLineIdAsc(outboundId);
        if (lines.isEmpty()) {
            throw badRequest("出库单无明细，无法拣货（可能库存不足未生成行项）");
        }
        lines.forEach(line -> {
                    line.setPicked(true);
                    line.setHandoverStatus("READY");
                    outboundLineRepository.save(line);
                });
        outbound.setStatus("PICKED");
        outbound.setHandoverStatus("READY");
        outboundRepository.save(outbound);
        return getOutbound(outboundId);
    }

    @Transactional
    public WarehouseOutboundDto shipOutbound(Long operatorId, Long outboundId) {
        WarehouseOutbound outbound = outboundRepository.findById(outboundId)
                .orElseThrow(() -> notFound("outbound"));
        if ("SHIPPED".equals(outbound.getStatus())) {
            return getOutbound(outboundId);
        }
        List<WarehouseOutboundLine> lines = outboundLineRepository.findByOutboundIdOrderByLineIdAsc(outboundId);
        if (lines.isEmpty()) {
            throw badRequest("出库单无明细，无法发运（可能库存不足未生成行项）");
        }
        if (lines.stream().anyMatch(line -> !line.isPicked())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "outbound must be picked before ship");
        }
        // 发运前再按当前货道余量截断，避免规划后柜机库存变化导致超发
        lines = clampLinesToSlotHeadroom(lines);
        if (lines.isEmpty()) {
            throw badRequest("货道已满，无可发运数量");
        }
        for (WarehouseOutboundLine line : lines) {
            deductWarehouseStock(outbound.getWarehouseId(), line.getSkuId(), line.getBatchNo(), line.getQuantity());
            recordWarehouseMovement(outbound.getWarehouseId(), line.getSkuId(), line.getBatchNo(),
                    "OUTBOUND_SHIP", -line.getQuantity(), "WAREHOUSE_OUTBOUND",
                    String.valueOf(outboundId), operatorId);
            line.setHandoverStatus("IN_TRANSIT");
            outboundLineRepository.save(line);
        }
        outbound.setStatus("SHIPPED");
        outbound.setShippedAt(Instant.now());
        outbound.setHandoverStatus("IN_TRANSIT");
        outbound.setHandoverOperatorId(operatorId);
        outbound.setHandedOverAt(Instant.now());
        outboundRepository.save(outbound);
        inTransitService.recordFromOutbound(outboundId, lines);
        return getOutbound(outboundId);
    }

    @Transactional(readOnly = true)
    public boolean hasOutboundLinesForDevice(Long outboundId, String deviceId) {
        if (outboundId == null || deviceId == null || deviceId.isBlank()) {
            return false;
        }
        return !outboundLineRepository
                .findByOutboundIdAndDeviceIdOrderByLineIdAsc(outboundId, deviceId.trim())
                .isEmpty();
    }

    /**
     * 整单作废未签收出库：DRAFT/PICKED 直接作废；SHIPPED 未交接则回仓并取消在途。
     * 任一设备行已 RECEIVED/PARTIAL 则拒绝（避免半签收脏账）。
     */
    @Transactional
    public WarehouseOutboundDto cancelUnreceivedOutbound(Long outboundId, Long operatorId) {
        WarehouseOutbound outbound = outboundRepository.findById(outboundId)
                .orElseThrow(() -> notFound("outbound"));
        if ("CANCELLED".equals(outbound.getStatus())) {
            return getOutbound(outboundId);
        }
        List<WarehouseOutboundLine> allLines =
                outboundLineRepository.findByOutboundIdOrderByLineIdAsc(outboundId);
        boolean handedOver = allLines.stream().anyMatch(line -> {
            String hs = line.getHandoverStatus();
            return "RECEIVED".equals(hs) || "PARTIAL".equals(hs);
        });
        if (handedOver) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ApiMessages.WAREHOUSE_OUTBOUND_CANCEL_BLOCKED);
        }
        java.util.LinkedHashSet<String> devices = new java.util.LinkedHashSet<>();
        for (WarehouseOutboundLine line : allLines) {
            if (line.getDeviceId() != null && !line.getDeviceId().isBlank()) {
                devices.add(line.getDeviceId().trim());
            }
        }
        if (devices.isEmpty()) {
            outbound.setStatus("CANCELLED");
            outbound.setHandoverStatus("CANCELLED");
            outboundRepository.save(outbound);
            return getOutbound(outboundId);
        }
        for (String device : devices) {
            cancelUnreceivedOutboundForDevice(outboundId, device, operatorId);
        }
        return getOutbound(outboundId);
    }

    /**
     * 取消未签收出库占用：DRAFT/PICKED 直接作废；SHIPPED 未交接则回仓并取消在途。
     * 已 RECEIVED/PARTIAL 交接的设备行拒绝取消。
     */
    @Transactional
    public void cancelUnreceivedOutboundForDevice(Long outboundId, String deviceId, Long operatorId) {
        if (outboundId == null || deviceId == null || deviceId.isBlank()) {
            return;
        }
        WarehouseOutbound outbound = outboundRepository.findById(outboundId)
                .orElseThrow(() -> notFound("outbound"));
        if ("CANCELLED".equals(outbound.getStatus())) {
            return;
        }
        String device = deviceId.trim();
        List<WarehouseOutboundLine> deviceLines =
                outboundLineRepository.findByOutboundIdAndDeviceIdOrderByLineIdAsc(outboundId, device);
        boolean handedOver = deviceLines.stream().anyMatch(line -> {
            String hs = line.getHandoverStatus();
            return "RECEIVED".equals(hs) || "PARTIAL".equals(hs);
        });
        if (handedOver) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ApiMessages.REPLENISHMENT_CANCEL_NOT_EMPTY);
        }
        String status = outbound.getStatus();
        if ("SHIPPED".equals(status)) {
            for (WarehouseOutboundLine line : deviceLines) {
                if (line.getQuantity() > 0) {
                    addWarehouseStock(outbound.getWarehouseId(), line.getSkuId(), line.getBatchNo(),
                            null, line.getExpiryDate(), line.getQuantity());
                    recordWarehouseMovement(outbound.getWarehouseId(), line.getSkuId(), line.getBatchNo(),
                            "OUTBOUND_CANCEL", line.getQuantity(), "WAREHOUSE_OUTBOUND",
                            String.valueOf(outboundId), operatorId);
                }
                line.setHandoverStatus("CANCELLED");
                outboundLineRepository.save(line);
            }
            inTransitService.cancelOpenForDevice(outboundId, device);
        } else {
            // DRAFT / PICKED：未扣仓，仅标记明细与单据
            for (WarehouseOutboundLine line : deviceLines) {
                line.setHandoverStatus("CANCELLED");
                outboundLineRepository.save(line);
            }
        }
        List<WarehouseOutboundLine> allLines = outboundLineRepository.findByOutboundIdOrderByLineIdAsc(outboundId);
        boolean allDeviceCancelled = allLines.stream().allMatch(line ->
                "CANCELLED".equals(line.getHandoverStatus())
                        || line.getDeviceId() == null
                        || line.getDeviceId().isBlank());
        boolean noOpenHandover = allLines.stream().noneMatch(line -> {
            String hs = line.getHandoverStatus();
            return hs == null || "READY".equals(hs) || "IN_TRANSIT".equals(hs);
        });
        if (allDeviceCancelled || noOpenHandover || deviceLines.size() == allLines.size()) {
            outbound.setStatus("CANCELLED");
            outbound.setHandoverStatus("CANCELLED");
            outboundRepository.save(outbound);
        }
    }

    /**
     * 批量安全收口历史脏出库（不硬删）：
     * <ul>
     *   <li>空 DRAFT/PICKED（无明细）</li>
     *   <li>终态路线上的未发运草稿/已拣货（含有明细）</li>
     *   <li>终态路线上的 SHIPPED 且未签收，且无 COMPLETED 关联任务（回仓 + 取消在途）</li>
     * </ul>
     * COMPLETED 路线仍 SHIPPED 时：仅当关联任务全非 COMPLETED（多为已取消未签收）才收口，
     * 避免「任务已完成但交接态未回写」场景误回仓造成假满货。
     */
    @Transactional
    public WarehouseStaleCleanupResultDto cleanupStaleOutbounds(Long operatorId) {
        Map<Long, String> routeStatusById = routeRepository.findAllByOrderByRouteIdAsc().stream()
                .filter(r -> r.getRouteId() != null)
                .collect(Collectors.toMap(ReplenishmentRoute::getRouteId, ReplenishmentRoute::getStatus, (a, b) -> a));
        int cancelledEmptyDrafts = 0;
        int cancelledTerminalDrafts = 0;
        int cancelledOrphanShipped = 0;
        int skipped = 0;
        List<Long> cancelledIds = new ArrayList<>();
        for (WarehouseOutbound outbound : outboundRepository.findAllByOrderByCreatedAtDesc()) {
            if ("CANCELLED".equals(outbound.getStatus())) {
                continue;
            }
            Long outboundId = outbound.getOutboundId();
            List<WarehouseOutboundLine> lines =
                    outboundLineRepository.findByOutboundIdOrderByLineIdAsc(outboundId);
            boolean handedOver = lines.stream().anyMatch(line -> {
                String hs = line.getHandoverStatus();
                return "RECEIVED".equals(hs) || "PARTIAL".equals(hs);
            });
            if (handedOver) {
                skipped++;
                continue;
            }
            String status = outbound.getStatus();
            boolean empty = lines.isEmpty();
            String routeStatus = outbound.getRouteId() == null ? null : routeStatusById.get(outbound.getRouteId());
            boolean terminalRoute = "CANCELLED".equals(routeStatus) || "COMPLETED".equals(routeStatus);

            String bucket = null;
            if (("DRAFT".equals(status) || "PICKED".equals(status)) && empty) {
                bucket = "empty";
            } else if (("DRAFT".equals(status) || "PICKED".equals(status)) && terminalRoute) {
                bucket = "terminal-draft";
            } else if ("SHIPPED".equals(status) && terminalRoute && !hasCompletedTaskLinked(outboundId)) {
                // 终态路线 + 未签收 + 无已完成任务：与 cancel-empty 孤儿收口同安全边界
                bucket = "orphan-shipped";
            }
            if (bucket == null) {
                skipped++;
                continue;
            }
            try {
                cancelUnreceivedOutbound(outboundId, operatorId);
                cancelledIds.add(outboundId);
                switch (bucket) {
                    case "empty" -> cancelledEmptyDrafts++;
                    case "terminal-draft" -> cancelledTerminalDrafts++;
                    case "orphan-shipped" -> cancelledOrphanShipped++;
                    default -> { }
                }
            } catch (ResponseStatusException ex) {
                skipped++;
            }
        }
        return new WarehouseStaleCleanupResultDto(
                cancelledEmptyDrafts, cancelledTerminalDrafts, cancelledOrphanShipped, skipped, cancelledIds);
    }

    /** 出库是否仍有已完成补货任务（已上架场景禁止自动回仓）。 */
    private boolean hasCompletedTaskLinked(Long outboundId) {
        if (outboundId == null) {
            return false;
        }
        return taskRepository.findByOutboundId(outboundId).stream()
                .anyMatch(task -> "COMPLETED".equals(task.getStatus()));
    }

    @Transactional
    public void markDeviceHandoverReceived(Long outboundId, String deviceId) {
        markDeviceHandoverReceived(outboundId, deviceId, Integer.MAX_VALUE);
    }

    /**
     * 按实际上架数量签收：满量 RECEIVED，部分/零上架 PARTIAL（与仓配交接字典一致）。
     *
     * @param appliedQty 本设备本次实际上架数量；传 {@link Integer#MAX_VALUE} 表示按出库行全量签收
     */
    @Transactional
    public void markDeviceHandoverReceived(Long outboundId, String deviceId, int appliedQty) {
        if (outboundId == null || deviceId == null || deviceId.isBlank() || appliedQty < 0) {
            return;
        }
        List<WarehouseOutboundLine> deviceLines =
                outboundLineRepository.findByOutboundIdAndDeviceIdOrderByLineIdAsc(outboundId, deviceId.trim());
        if (deviceLines.isEmpty()) {
            return;
        }
        int shipped = deviceLines.stream().mapToInt(WarehouseOutboundLine::getQuantity).sum();
        boolean full = appliedQty >= shipped && shipped > 0;
        int remaining = Math.min(appliedQty, shipped);
        for (WarehouseOutboundLine line : deviceLines) {
            if (full) {
                line.setHandoverStatus("RECEIVED");
            } else if (remaining <= 0) {
                line.setHandoverStatus("PARTIAL");
            } else if (remaining >= line.getQuantity()) {
                line.setHandoverStatus("RECEIVED");
                remaining -= line.getQuantity();
            } else {
                line.setHandoverStatus("PARTIAL");
                remaining = 0;
            }
            outboundLineRepository.save(line);
        }
        WarehouseOutbound outbound = outboundRepository.findById(outboundId).orElse(null);
        if (outbound == null) {
            return;
        }
        List<WarehouseOutboundLine> allLines = outboundLineRepository.findByOutboundIdOrderByLineIdAsc(outboundId);
        boolean allReceived = allLines.stream().allMatch(line -> "RECEIVED".equals(line.getHandoverStatus()));
        if (allReceived) {
            outbound.setHandoverStatus("RECEIVED");
        } else {
            outbound.setHandoverStatus("PARTIAL");
        }
        outboundRepository.save(outbound);
    }

    /**
     * @param requireFull true 时库存不足抛冲突；false 时尽力分配并返回已生成行数
     * @return 本次写入的出库行数
     */
    private int allocateFefoToOutbound(Long outboundId, String warehouseId, String deviceId,
                                       String skuId, int needQty, boolean requireFull) {
        int cappedNeed = needQty;
        boolean boundSlots = deviceId != null && !deviceId.isBlank()
                && deviceSlotService.hasSkuSlots(deviceId, skuId);
        if (boundSlots) {
            int headroom = deviceSlotService.totalHeadroomForSku(deviceId, skuId);
            if (headroom <= 0) {
                return 0;
            }
            cappedNeed = Math.min(needQty, headroom);
        }
        if (cappedNeed <= 0) {
            return 0;
        }

        List<DeviceSlotService.SlotRestockAllocation> slotAllocs = boundSlots
                ? deviceSlotService.allocateRestockQuantity(deviceId, skuId, cappedNeed)
                : List.of();
        if (boundSlots && slotAllocs.isEmpty()) {
            return 0;
        }
        if (slotAllocs.isEmpty()) {
            return allocateFefoLots(outboundId, warehouseId, deviceId, skuId, cappedNeed, null, requireFull).lines();
        }

        int lines = 0;
        int fulfilled = 0;
        for (DeviceSlotService.SlotRestockAllocation alloc : slotAllocs) {
            FefoAllocResult result = allocateFefoLots(
                    outboundId, warehouseId, deviceId, skuId, alloc.quantity(), alloc.slotCode(), false);
            lines += result.lines();
            fulfilled += result.qty();
            if (result.qty() < alloc.quantity()) {
                break;
            }
        }
        if (requireFull && fulfilled < cappedNeed) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "warehouse stock insufficient for outbound sku=" + skuId
                            + " need=" + needQty + " remaining=" + (needQty - fulfilled));
        }
        return lines;
    }

    private record FefoAllocResult(int lines, int qty) {}

    /**
     * FEFO 写入出库行（可选货道）。
     */
    private FefoAllocResult allocateFefoLots(Long outboundId, String warehouseId, String deviceId,
                                             String skuId, int needQty, String slotId, boolean requireFull) {
        int remaining = needQty;
        int lines = 0;
        List<WarehouseInventory> lots = inventoryRepository
                .findByWarehouseIdAndSkuIdOrderByExpiryDateAsc(warehouseId, skuId);
        for (WarehouseInventory lot : lots) {
            if (remaining <= 0) break;
            if (lot.getQuantity() <= 0 || lot.getExpiryDate().isBefore(LocalDate.now())) continue;
            int allocated = outboundLineRepository.sumAllocatedQty(warehouseId, skuId, lot.getBatchNo());
            int available = Math.max(0, lot.getQuantity() - allocated);
            if (available <= 0) continue;
            int take = Math.min(available, remaining);
            WarehouseOutboundLine line = new WarehouseOutboundLine();
            line.setOutboundId(outboundId);
            line.setDeviceId(deviceId);
            line.setSkuId(skuId);
            line.setBatchNo(lot.getBatchNo());
            line.setExpiryDate(lot.getExpiryDate());
            line.setQuantity(take);
            line.setPicked(false);
            if (slotId != null && !slotId.isBlank()) {
                line.setSlotId(slotId.trim().toUpperCase());
            }
            outboundLineRepository.save(line);
            remaining -= take;
            lines++;
        }
        if (remaining > 0 && requireFull) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "warehouse stock insufficient for outbound sku=" + skuId + " need=" + needQty + " remaining=" + remaining);
        }
        return new FefoAllocResult(lines, needQty - remaining);
    }

    /** 发运前按货道余量截断出库行；数量归零的行删除。 */
    private List<WarehouseOutboundLine> clampLinesToSlotHeadroom(List<WarehouseOutboundLine> lines) {
        Map<String, Integer> takenBySlot = new LinkedHashMap<>();
        Map<String, Integer> takenBySku = new LinkedHashMap<>();
        List<WarehouseOutboundLine> kept = new java.util.ArrayList<>();
        for (WarehouseOutboundLine line : lines) {
            String deviceId = line.getDeviceId();
            String skuId = line.getSkuId();
            if (deviceId == null || deviceId.isBlank() || skuId == null
                    || !deviceSlotService.hasSkuSlots(deviceId, skuId)) {
                kept.add(line);
                continue;
            }
            int qty = line.getQuantity();
            if (qty <= 0) {
                outboundLineRepository.deleteById(line.getLineId());
                continue;
            }
            String slotId = line.getSlotId();
            int allow;
            if (slotId != null && !slotId.isBlank()) {
                String key = deviceId + "|" + slotId.trim().toUpperCase();
                int headroom = deviceSlotService.headroomForSlot(deviceId, slotId);
                int already = takenBySlot.getOrDefault(key, 0);
                allow = Math.max(0, headroom - already);
                if (allow > 0) {
                    takenBySlot.put(key, already + Math.min(qty, allow));
                }
            } else {
                String key = deviceId + "|" + skuId;
                int headroom = deviceSlotService.totalHeadroomForSku(deviceId, skuId);
                int already = takenBySku.getOrDefault(key, 0);
                allow = Math.max(0, headroom - already);
                if (allow > 0) {
                    takenBySku.put(key, already + Math.min(qty, allow));
                }
            }
            if (allow <= 0) {
                outboundLineRepository.deleteById(line.getLineId());
                continue;
            }
            if (allow < qty) {
                line.setQuantity(allow);
                outboundLineRepository.save(line);
            }
            kept.add(line);
        }
        return kept;
    }

    private void addWarehouseStock(String warehouseId, String skuId, String batchNo,
                                   LocalDate productionDate, LocalDate expiryDate, int qty) {
        WarehouseInventory inv = inventoryRepository.findByWarehouseIdAndSkuIdAndBatchNo(warehouseId, skuId, batchNo)
                .orElseGet(() -> {
                    WarehouseInventory n = new WarehouseInventory();
                    n.setWarehouseId(warehouseId);
                    n.setSkuId(skuId);
                    n.setBatchNo(batchNo);
                    n.setProductionDate(productionDate);
                    n.setExpiryDate(expiryDate);
                    return n;
                });
        inv.setQuantity(inv.getQuantity() + qty);
        if (productionDate != null) inv.setProductionDate(productionDate);
        if (expiryDate != null) inv.setExpiryDate(expiryDate);
        inventoryRepository.save(inv);
    }

    private void deductWarehouseStock(String warehouseId, String skuId, String batchNo, int qty) {
        WarehouseInventory inv = inventoryRepository.findByWarehouseIdAndSkuIdAndBatchNo(warehouseId, skuId, batchNo)
                .orElseThrow(() -> badRequest("warehouse stock insufficient: " + skuId + "/" + batchNo));
        if (inv.getQuantity() < qty) {
            throw badRequest("warehouse stock insufficient: " + skuId + "/" + batchNo);
        }
        inv.setQuantity(inv.getQuantity() - qty);
        inventoryRepository.save(inv);
    }

    private void recordWarehouseMovement(String warehouseId, String skuId, String batchNo,
                                         String movementType, int deltaQty,
                                         String refType, String refId, Long operatorId) {
        WarehouseMovement movement = new WarehouseMovement();
        movement.setWarehouseId(warehouseId);
        movement.setSkuId(skuId);
        movement.setBatchNo(batchNo);
        movement.setMovementType(movementType);
        movement.setDeltaQty(deltaQty);
        movement.setRefType(refType);
        movement.setRefId(refId);
        movement.setOperatorId(operatorId);
        movementRepository.save(movement);
    }

    private void validateInboundLine(WarehouseInboundLineDto dto) {
        if (dto.skuId() == null || dto.skuId().isBlank()) throw badRequest("skuId required");
        if (dto.batchNo() == null || dto.batchNo().isBlank()) throw badRequest("batchNo required");
        if (dto.expiryDate() == null) throw badRequest("expiryDate required");
        if (dto.quantity() <= 0) throw badRequest("quantity must be positive");
        if (!skuCatalogRepository.existsById(dto.skuId())) {
            throw badRequest("sku not found: " + dto.skuId());
        }
    }

    private String resolveWarehouseId(String warehouseId) {
        if (warehouseId != null && !warehouseId.isBlank()) return warehouseId.trim();
        return DEFAULT_WAREHOUSE_ID;
    }

    private WarehouseDto toWarehouseDto(Warehouse w) {
        return new WarehouseDto(w.getWarehouseId(), w.getWarehouseName(), w.getAddress(), w.getStatus(), w.getCreatedAt());
    }

    private WarehouseInventoryDto toInventoryDto(WarehouseInventory i) {
        return new WarehouseInventoryDto(
                i.getInventoryId(), i.getWarehouseId(), i.getSkuId(), i.getBatchNo(),
                i.getProductionDate(), i.getExpiryDate(), i.getQuantity()
        );
    }

    private WarehouseOutboundLineDto toOutboundLineDto(WarehouseOutboundLine line) {
        return new WarehouseOutboundLineDto(
                line.getLineId(), line.getDeviceId(), line.getSkuId(), line.getBatchNo(),
                line.getExpiryDate(), line.getQuantity(), line.isPicked(),
                line.getHandoverStatus() == null ? "PENDING" : line.getHandoverStatus(),
                line.getSlotId()
        );
    }

    @Transactional
    public WarehouseDto upsertWarehouse(String warehouseId, String warehouseName, String address, String status) {
        if (warehouseId == null || warehouseId.isBlank()) {
            throw badRequest("warehouseId required");
        }
        if (warehouseName == null || warehouseName.isBlank()) {
            throw badRequest("warehouseName required");
        }
        String id = warehouseId.trim();
        Warehouse warehouse = warehouseRepository.findById(id).orElseGet(Warehouse::new);
        boolean creating = warehouse.getWarehouseId() == null || warehouse.getWarehouseId().isBlank();
        warehouse.setWarehouseId(id);
        warehouse.setWarehouseName(warehouseName.trim());
        warehouse.setAddress(address == null || address.isBlank() ? null : address.trim());
        String st = status == null || status.isBlank() ? "ACTIVE" : status.trim().toUpperCase();
        if (!"ACTIVE".equals(st) && !"INACTIVE".equals(st)) {
            throw badRequest("status must be ACTIVE or INACTIVE");
        }
        warehouse.setStatus(st);
        if (creating || warehouse.getCreatedAt() == null) {
            warehouse.setCreatedAt(Instant.now());
        }
        return toWarehouseDto(warehouseRepository.save(warehouse));
    }

    private WarehouseMovementDto toMovementDto(WarehouseMovement movement) {
        return new WarehouseMovementDto(
                movement.getMovementId(),
                movement.getWarehouseId(),
                movement.getSkuId(),
                movement.getBatchNo(),
                movement.getMovementType(),
                movement.getDeltaQty(),
                movement.getRefType(),
                movement.getRefId(),
                movement.getOperatorId(),
                movement.getCreatedAt()
        );
    }

    private static ResponseStatusException notFound(String what) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, what + " not found");
    }

    private static ResponseStatusException badRequest(String msg) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
    }

    private static Long parseLongOrNull(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
