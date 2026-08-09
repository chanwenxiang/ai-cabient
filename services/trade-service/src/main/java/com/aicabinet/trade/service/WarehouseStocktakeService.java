package com.aicabinet.trade.service;

import com.aicabinet.common.dto.AdjustStocktakeRequest;
import com.aicabinet.common.dto.CreateStocktakeRequest;
import com.aicabinet.common.dto.StocktakeDto;
import com.aicabinet.common.dto.StocktakeLineDto;
import com.aicabinet.common.dto.UpdateStocktakeLineRequest;
import com.aicabinet.trade.domain.WarehouseInventory;
import com.aicabinet.trade.domain.WarehouseStocktake;
import com.aicabinet.trade.domain.WarehouseStocktakeLine;
import com.aicabinet.trade.mapper.SkuCatalogMapper;
import com.aicabinet.trade.mapper.WarehouseInventoryMapper;
import com.aicabinet.trade.mapper.WarehouseMapper;
import com.aicabinet.trade.mapper.WarehouseStocktakeLineMapper;
import com.aicabinet.trade.mapper.WarehouseStocktakeMapper;
import com.aicabinet.trade.client.VisionServiceClient;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 整仓盘点流程：新建盘点单（明盘/盲盘）→ 录入实盘 → 完成 → 复盘调整 → 差异落库。
 */
@Service
public class WarehouseStocktakeService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final float MIN_VISION_CONFIDENCE = 0.50f;

    private final PermissionService permissionService;
    private final WarehouseStocktakeMapper stocktakeRepository;
    private final WarehouseStocktakeLineMapper lineRepository;
    private final WarehouseMapper warehouseRepository;
    private final WarehouseInventoryMapper inventoryRepository;
    private final SkuCatalogMapper skuCatalogRepository;
    private final WarehouseService warehouseService;
    private final VisionServiceClient visionServiceClient;

    public WarehouseStocktakeService(PermissionService permissionService,
                                     WarehouseStocktakeMapper stocktakeRepository,
                                     WarehouseStocktakeLineMapper lineRepository,
                                     WarehouseMapper warehouseRepository,
                                     WarehouseInventoryMapper inventoryRepository,
                                     SkuCatalogMapper skuCatalogRepository,
                                     WarehouseService warehouseService,
                                     VisionServiceClient visionServiceClient) {
        this.permissionService = permissionService;
        this.stocktakeRepository = stocktakeRepository;
        this.lineRepository = lineRepository;
        this.warehouseRepository = warehouseRepository;
        this.inventoryRepository = inventoryRepository;
        this.skuCatalogRepository = skuCatalogRepository;
        this.warehouseService = warehouseService;
        this.visionServiceClient = visionServiceClient;
    }

    @Transactional
    public StocktakeDto create(Long operatorId, CreateStocktakeRequest request) {
        permissionService.requirePermission(operatorId, "ops:warehouse:edit");
        String wh = required(request.warehouseId(), "warehouseId").trim();
        warehouseRepository.findById(wh).orElseThrow(() -> notFound("warehouse"));

        boolean blind = request.mode() != null && "BLIND".equalsIgnoreCase(request.mode().trim());
        WarehouseStocktake st = new WarehouseStocktake();
        st.setStocktakeNo(generateNo());
        st.setWarehouseId(wh);
        st.setMode(blind ? "BLIND" : "OPEN");
        st.setStatus("DRAFT");
        st.setOperatorId(operatorId);
        st.setNotes(trimToNull(request.notes()));
        st.setCreatedAt(Instant.now());
        st = stocktakeRepository.save(st);

        int bookTotal = 0;
        int countedTotal = 0;
        for (WarehouseInventory inv : inventoryRepository.findByWarehouseIdOrderByExpiryDateAsc(wh)) {
            if (inv.getQuantity() <= 0) {
                continue;
            }
            WarehouseStocktakeLine line = new WarehouseStocktakeLine();
            line.setStocktakeId(st.getStocktakeId());
            line.setSkuId(inv.getSkuId());
            line.setBatchNo(inv.getBatchNo());
            line.setProductionDate(inv.getProductionDate());
            line.setExpiryDate(inv.getExpiryDate());
            line.setBookQty(inv.getQuantity());
            line.setCountedQty(blind ? null : inv.getQuantity());
            line.setDiffQty(0);
            line.setStatus("PENDING");
            lineRepository.save(line);
            bookTotal += inv.getQuantity();
            if (line.getCountedQty() != null) {
                countedTotal += line.getCountedQty();
            }
        }
        st.setBookQty(bookTotal);
        st.setCountedQty(countedTotal);
        stocktakeRepository.save(st);
        return toDto(st);
    }

    @Transactional(readOnly = true)
    public List<StocktakeDto> list(Long operatorId, String status) {
        permissionService.requirePermission(operatorId, "ops:warehouse:list");
        return stocktakeRepository.findAllByOrderByCreatedAtDesc().stream()
                .filter(s -> status == null || status.isBlank()
                        || status.trim().equalsIgnoreCase(s.getStatus()))
                .map(this::toDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public StocktakeDto get(Long operatorId, Long stocktakeId) {
        permissionService.requirePermission(operatorId, "ops:warehouse:list");
        return toDto(requireStocktake(stocktakeId));
    }

    @Transactional
    public StocktakeLineDto updateLine(Long operatorId, Long stocktakeId, Long lineId,
                                       UpdateStocktakeLineRequest request) {
        permissionService.requirePermission(operatorId, "ops:warehouse:edit");
        WarehouseStocktake st = requireStocktake(stocktakeId);
        if (!"DRAFT".equals(st.getStatus()) && !"IN_PROGRESS".equals(st.getStatus())) {
            throw conflict("stocktake not editable");
        }
        WarehouseStocktakeLine line = lineRepository.findById(lineId)
                .orElseThrow(() -> notFound("stocktake line"));
        if (!line.getStocktakeId().equals(stocktakeId)) {
            throw badRequest("line does not belong to stocktake");
        }
        int counted = request.countedQty() != null ? request.countedQty() : 0;
        if (counted < 0) {
            throw badRequest("countedQty must be >= 0");
        }
        line.setCountedQty(counted);
        line.setDiffQty(counted - line.getBookQty());
        line.setStatus(line.getDiffQty() == 0 ? "MATCHED" : "DIFF");
        line.setNotes(trimToNull(request.notes()));
        lineRepository.save(line);

        if ("DRAFT".equals(st.getStatus())) {
            st.setStatus("IN_PROGRESS");
            st.setStartedAt(Instant.now());
        }
        refreshTotals(st);
        stocktakeRepository.save(st);
        return toLineDto(line);
    }

    @Transactional
    public StocktakeDto complete(Long operatorId, Long stocktakeId) {
        permissionService.requirePermission(operatorId, "ops:warehouse:edit");
        WarehouseStocktake st = requireStocktake(stocktakeId);
        if (!"DRAFT".equals(st.getStatus()) && !"IN_PROGRESS".equals(st.getStatus())) {
            throw conflict("stocktake cannot be completed");
        }
        for (WarehouseStocktakeLine line : lineRepository.findByStocktakeIdOrderByLineIdAsc(stocktakeId)) {
            if (line.getCountedQty() == null) {
                throw badRequest("all lines must be counted before completion: "
                        + line.getSkuId() + "/" + line.getBatchNo());
            }
            line.setDiffQty(line.getCountedQty() - line.getBookQty());
            line.setStatus(line.getDiffQty() == 0 ? "MATCHED" : "DIFF");
            lineRepository.save(line);
        }
        st.setStatus("COMPLETED");
        st.setCompletedAt(Instant.now());
        refreshTotals(st);
        stocktakeRepository.save(st);
        return toDto(st);
    }

    @Transactional
    public StocktakeDto adjust(Long operatorId, Long stocktakeId, AdjustStocktakeRequest request) {
        permissionService.requirePermission(operatorId, "ops:warehouse:edit");
        WarehouseStocktake st = requireStocktake(stocktakeId);
        if (!"COMPLETED".equals(st.getStatus())) {
            throw conflict("stocktake must be completed before adjusting");
        }
        Set<Long> selected = request != null && request.lineIds() != null && !request.lineIds().isEmpty()
                ? new HashSet<>(request.lineIds())
                : null;
        List<WarehouseStocktakeLine> lines = lineRepository.findByStocktakeIdOrderByLineIdAsc(stocktakeId);
        for (WarehouseStocktakeLine line : lines) {
            if (line.getCountedQty() == null || line.getDiffQty() == 0
                    || "ADJUSTED".equals(line.getStatus())) {
                continue;
            }
            if (selected != null && !selected.contains(line.getLineId())) {
                continue;
            }
            warehouseService.adjustStocktake(
                    st.getWarehouseId(), line.getSkuId(), line.getBatchNo(),
                    line.getProductionDate(), line.getExpiryDate(),
                    line.getBookQty(), line.getCountedQty(), operatorId, st.getStocktakeId());
            line.setStatus("ADJUSTED");
            line.setAdjustedAt(Instant.now());
            lineRepository.save(line);
        }
        refreshTotals(st);
        boolean anyDiffLeft = lines.stream().anyMatch(l -> l.getCountedQty() != null
                && l.getDiffQty() != 0 && !"ADJUSTED".equals(l.getStatus()));
        if (!anyDiffLeft) {
            st.setStatus("ADJUSTED");
        }
        stocktakeRepository.save(st);
        return toDto(st);
    }

    @Transactional
    public StocktakeDto cancel(Long operatorId, Long stocktakeId) {
        permissionService.requirePermission(operatorId, "ops:warehouse:edit");
        WarehouseStocktake st = requireStocktake(stocktakeId);
        if (!"DRAFT".equals(st.getStatus())) {
            throw conflict("only draft stocktake can be cancelled");
        }
        st.setStatus("CANCELLED");
        stocktakeRepository.save(st);
        return toDto(st);
    }

    /**
     * 拍照盘点：上传现场照片，由视觉服务识别商品并自动填入实盘数量。
     * 只覆盖置信度达标的识别结果，未识别/低置信度的行保持原状由人工录入。
     */
    @Transactional
    public StocktakeDto applyVisionCounts(Long operatorId, Long stocktakeId,
                                          byte[] image, String filename) {
        permissionService.requirePermission(operatorId, "ops:warehouse:edit");
        WarehouseStocktake st = requireStocktake(stocktakeId);
        if (!"DRAFT".equals(st.getStatus()) && !"IN_PROGRESS".equals(st.getStatus())) {
            throw conflict("stocktake not editable");
        }
        VisionServiceClient.RecognitionResult result;
        try {
            result = visionServiceClient.recognizeUpload("STOCKTAKE-" + stocktakeId, image, filename);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "识别服务暂不可用，请手动录入实盘数");
        }
        if (result.items() == null || result.items().isEmpty()) {
            return toDto(st);
        }

        Map<String, WarehouseStocktakeLine> bySku = new HashMap<>();
        for (WarehouseStocktakeLine line : lineRepository.findByStocktakeIdOrderByLineIdAsc(stocktakeId)) {
            bySku.put(line.getSkuId().toUpperCase(Locale.ROOT), line);
        }
        for (VisionServiceClient.RecognizedItem item : result.items()) {
            if (item.skuId() == null || item.confidence() < MIN_VISION_CONFIDENCE) {
                continue;
            }
            WarehouseStocktakeLine line = bySku.get(item.skuId().trim().toUpperCase(Locale.ROOT));
            if (line == null) {
                continue;
            }
            int counted = Math.max(0, item.quantity());
            line.setCountedQty(counted);
            line.setDiffQty(counted - line.getBookQty());
            line.setStatus(line.getDiffQty() == 0 ? "MATCHED" : "DIFF");
            lineRepository.save(line);
        }
        if ("DRAFT".equals(st.getStatus())) {
            st.setStatus("IN_PROGRESS");
            st.setStartedAt(Instant.now());
        }
        refreshTotals(st);
        stocktakeRepository.save(st);
        return toDto(st);
    }

    private void refreshTotals(WarehouseStocktake st) {
        List<WarehouseStocktakeLine> lines = lineRepository.findByStocktakeIdOrderByLineIdAsc(st.getStocktakeId());
        int book = 0;
        int counted = 0;
        int diff = 0;
        int diffLines = 0;
        for (WarehouseStocktakeLine line : lines) {
            book += line.getBookQty();
            if (line.getCountedQty() != null) {
                counted += line.getCountedQty();
            }
            if ("ADJUSTED".equals(line.getStatus())) {
                continue;
            }
            diff += line.getDiffQty();
            if (line.getDiffQty() != 0) {
                diffLines++;
            }
        }
        st.setBookQty(book);
        st.setCountedQty(counted);
        st.setDiffQty(diff);
        st.setDiffLineCount(diffLines);
    }

    private WarehouseStocktake requireStocktake(Long stocktakeId) {
        return stocktakeRepository.findById(stocktakeId)
                .orElseThrow(() -> notFound("stocktake"));
    }

    private StocktakeDto toDto(WarehouseStocktake st) {
        List<StocktakeLineDto> lines = lineRepository.findByStocktakeIdOrderByLineIdAsc(st.getStocktakeId())
                .stream().map(this::toLineDto).toList();
        String warehouseName = warehouseRepository.findById(st.getWarehouseId())
                .map(w -> w.getWarehouseName())
                .orElse(st.getWarehouseId());
        return new StocktakeDto(
                st.getStocktakeId(),
                st.getStocktakeNo(),
                st.getWarehouseId(),
                warehouseName,
                st.getMode(),
                st.getStatus(),
                st.getBookQty(),
                st.getCountedQty(),
                st.getDiffQty(),
                st.getDiffLineCount(),
                st.getOperatorId(),
                st.getNotes(),
                st.getCreatedAt(),
                st.getStartedAt(),
                st.getCompletedAt(),
                lines);
    }

    private StocktakeLineDto toLineDto(WarehouseStocktakeLine line) {
        String skuName = skuCatalogRepository.findById(line.getSkuId())
                .map(s -> s.getSkuName())
                .orElse(line.getSkuId());
        return new StocktakeLineDto(
                line.getLineId(),
                line.getStocktakeId(),
                line.getSkuId(),
                skuName,
                line.getBatchNo(),
                line.getProductionDate(),
                line.getExpiryDate(),
                line.getBookQty(),
                line.getCountedQty(),
                line.getDiffQty(),
                line.getStatus(),
                line.getNotes(),
                line.getAdjustedAt());
    }

    private static String generateNo() {
        String date = LocalDate.now(ZONE).format(DateTimeFormatter.BASIC_ISO_DATE);
        String suffix = ThreadLocalRandom.current().ints(6, 0, 36)
                .mapToObj(i -> Character.toString("ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".charAt(i)))
                .reduce("", (a, b) -> a + b);
        return "STK" + date + "-" + suffix;
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, name + " required");
        }
        return value.trim();
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static ResponseStatusException notFound(String name) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, name + " not found");
    }

    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }
}
