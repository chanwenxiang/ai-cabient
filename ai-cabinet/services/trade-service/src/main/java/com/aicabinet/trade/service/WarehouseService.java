package com.aicabinet.trade.service;

import com.aicabinet.common.dto.*;
import com.aicabinet.trade.domain.*;
import com.aicabinet.trade.repository.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class WarehouseService {

    public static final String DEFAULT_WAREHOUSE_ID = "WH-DEMO-001";

    private final WarehouseRepository warehouseRepository;
    private final WarehouseInventoryRepository inventoryRepository;
    private final WarehouseInboundRepository inboundRepository;
    private final WarehouseInboundLineRepository inboundLineRepository;
    private final WarehouseOutboundRepository outboundRepository;
    private final WarehouseOutboundLineRepository outboundLineRepository;
    private final DeviceSkuInventoryRepository deviceInventoryRepository;
    private final ReplenishmentTaskRepository taskRepository;
    private final SkuCatalogRepository skuCatalogRepository;
    private final DeviceSlotService deviceSlotService;
    private final SalesVelocityService salesVelocityService;
    private final InTransitService inTransitService;

    public WarehouseService(WarehouseRepository warehouseRepository,
                            WarehouseInventoryRepository inventoryRepository,
                            WarehouseInboundRepository inboundRepository,
                            WarehouseInboundLineRepository inboundLineRepository,
                            WarehouseOutboundRepository outboundRepository,
                            WarehouseOutboundLineRepository outboundLineRepository,
                            DeviceSkuInventoryRepository deviceInventoryRepository,
                            ReplenishmentTaskRepository taskRepository,
                            SkuCatalogRepository skuCatalogRepository,
                            DeviceSlotService deviceSlotService,
                            SalesVelocityService salesVelocityService,
                            InTransitService inTransitService) {
        this.warehouseRepository = warehouseRepository;
        this.inventoryRepository = inventoryRepository;
        this.inboundRepository = inboundRepository;
        this.inboundLineRepository = inboundLineRepository;
        this.outboundRepository = outboundRepository;
        this.outboundLineRepository = outboundLineRepository;
        this.deviceInventoryRepository = deviceInventoryRepository;
        this.taskRepository = taskRepository;
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
        }
        return request;
    }

    @Transactional(readOnly = true)
    public List<ReplenishmentSuggestDto> suggestForDevice(String deviceId) {
        String dev = deviceId.trim();
        Map<String, Integer> inTransitBySku = inTransitService.qtyBySkuForDevice(dev);
        Map<String, Integer> qtyBySku = new LinkedHashMap<>();
        Map<String, Integer> bookBySku = new LinkedHashMap<>();
        Map<String, Integer> parBySku = new LinkedHashMap<>();
        Map<String, String> reasonBySku = new LinkedHashMap<>();
        Map<String, SalesVelocityService.SkuVelocity> velocityBySku = salesVelocityService.velocityBySku(deviceId);
        for (SlotReplenishmentSuggestDto slot : deviceSlotService.suggestSlotsForDevice(deviceId)) {
            if (slot.suggestQty() <= 0) {
                continue;
            }
            qtyBySku.merge(slot.skuId(), slot.suggestQty(), Integer::sum);
            bookBySku.merge(slot.skuId(), slot.bookQty(), Integer::sum);
            parBySku.merge(slot.skuId(), slot.parLevel(), Integer::sum);
            reasonBySku.putIfAbsent(slot.skuId(), slot.suggestReason());
        }
        if (!qtyBySku.isEmpty()) {
            return qtyBySku.entrySet().stream()
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
                    .toList();
        }
        List<ReplenishmentSuggestDto> lowStock = deviceInventoryRepository.findByIdDeviceId(dev).stream()
                .filter(i -> i.getQuantity() <= i.getLowThreshold())
                .map(i -> {
                    SalesVelocityService.SkuVelocity velocity = velocityBySku.getOrDefault(
                            i.getId().getSkuId(), new SalesVelocityService.SkuVelocity(0, 0, 0, 0));
                    String skuId = i.getId().getSkuId();
                    int inTransit = inTransitBySku.getOrDefault(skuId, 0);
                    int rawSuggest = Math.max(0, i.getCapacity() - i.getQuantity());
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
                    return new ReplenishmentSuggestDto(
                            dev, skuId, book, target, velocity.ropPoint(),
                            Math.max(0, rawSuggest - inTransit), inTransit,
                            velocity.soldQty7d(), velocity.soldQty14d(), velocity.ropPoint(),
                            "ROP");
                })
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    @Transactional
    public WarehouseOutboundDto createOutboundForRoute(Long routeId, String warehouseId, Long assigneeUserId) {
        String wh = resolveWarehouseId(warehouseId);
        outboundRepository.findByRouteId(routeId).ifPresent(o -> {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "outbound already exists for route");
        });
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

        for (ReplenishmentTask task : tasks) {
            List<ReplenishmentSuggestDto> suggestions = suggestForDevice(task.getDeviceId());
            for (ReplenishmentSuggestDto s : suggestions) {
                if (s.suggestQty() <= 0) continue;
                allocateFefoToOutbound(outbound.getOutboundId(), wh, task.getDeviceId(), s.skuId(), s.suggestQty());
            }
            task.setOutboundId(outbound.getOutboundId());
            taskRepository.save(task);
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
        outboundLineRepository.findByOutboundIdOrderByLineIdAsc(outboundId)
                .forEach(line -> {
                    line.setPicked(true);
                    outboundLineRepository.save(line);
                });
        outbound.setStatus("PICKED");
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
            throw badRequest("outbound has no lines");
        }
        for (WarehouseOutboundLine line : lines) {
            deductWarehouseStock(outbound.getWarehouseId(), line.getSkuId(), line.getBatchNo(), line.getQuantity());
            line.setPicked(true);
            outboundLineRepository.save(line);
        }
        outbound.setStatus("SHIPPED");
        outbound.setShippedAt(Instant.now());
        outboundRepository.save(outbound);
        inTransitService.recordFromOutbound(outboundId, lines);
        return getOutbound(outboundId);
    }

    private void allocateFefoToOutbound(Long outboundId, String warehouseId, String deviceId,
                                        String skuId, int needQty) {
        int remaining = needQty;
        List<WarehouseInventory> lots = inventoryRepository
                .findByWarehouseIdAndSkuIdOrderByExpiryDateAsc(warehouseId, skuId);
        for (WarehouseInventory lot : lots) {
            if (remaining <= 0) break;
            if (lot.getQuantity() <= 0 || lot.getExpiryDate().isBefore(LocalDate.now())) continue;
            int take = Math.min(lot.getQuantity(), remaining);
            WarehouseOutboundLine line = new WarehouseOutboundLine();
            line.setOutboundId(outboundId);
            line.setDeviceId(deviceId);
            line.setSkuId(skuId);
            line.setBatchNo(lot.getBatchNo());
            line.setExpiryDate(lot.getExpiryDate());
            line.setQuantity(take);
            line.setPicked(false);
            outboundLineRepository.save(line);
            remaining -= take;
        }
        if (remaining > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "warehouse stock insufficient for outbound sku=" + skuId + " need=" + needQty + " remaining=" + remaining);
        }
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
                line.getExpiryDate(), line.getQuantity(), line.isPicked()
        );
    }

    private static ResponseStatusException notFound(String what) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, what + " not found");
    }

    private static ResponseStatusException badRequest(String msg) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
    }
}
