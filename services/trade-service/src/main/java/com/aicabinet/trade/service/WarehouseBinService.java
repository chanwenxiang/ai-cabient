package com.aicabinet.trade.service;

import com.aicabinet.common.dto.BinInboundRequest;
import com.aicabinet.common.dto.BinMoveRequest;
import com.aicabinet.common.dto.UpsertWarehouseBinRequest;
import com.aicabinet.common.dto.WarehouseBinDto;
import com.aicabinet.common.dto.WarehouseBinStockDto;
import com.aicabinet.trade.domain.WarehouseBin;
import com.aicabinet.trade.domain.WarehouseBinStock;
import com.aicabinet.trade.mapper.SkuCatalogMapper;
import com.aicabinet.trade.mapper.WarehouseBinMapper;
import com.aicabinet.trade.mapper.WarehouseBinStockMapper;
import com.aicabinet.trade.mapper.WarehouseMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 仓库货位管理：货位档案、货位库存查询、入库到货位、货位间移库。
 * 入库/出库会同步仓库总库存并记录库存流水，保证货位账与仓库账一致。
 */
@Service
public class WarehouseBinService {

    private final PermissionService permissionService;
    private final WarehouseBinMapper binRepository;
    private final WarehouseBinStockMapper binStockRepository;
    private final WarehouseMapper warehouseRepository;
    private final SkuCatalogMapper skuCatalogRepository;
    private final WarehouseService warehouseService;

    public WarehouseBinService(PermissionService permissionService,
                               WarehouseBinMapper binRepository,
                               WarehouseBinStockMapper binStockRepository,
                               WarehouseMapper warehouseRepository,
                               SkuCatalogMapper skuCatalogRepository,
                               WarehouseService warehouseService) {
        this.permissionService = permissionService;
        this.binRepository = binRepository;
        this.binStockRepository = binStockRepository;
        this.warehouseRepository = warehouseRepository;
        this.skuCatalogRepository = skuCatalogRepository;
        this.warehouseService = warehouseService;
    }

    @Transactional(readOnly = true)
    public List<WarehouseBinDto> listBins(Long operatorId, String warehouseId) {
        permissionService.requirePermission(operatorId, "ops:warehouse:list");
        String wh = warehouseId == null || warehouseId.isBlank() ? null : warehouseId.trim();
        return binRepository.findAll().stream()
                .filter(b -> wh == null || wh.equals(b.getWarehouseId()))
                .sorted(Comparator.comparing(WarehouseBin::getWarehouseId)
                        .thenComparing(WarehouseBin::getBinCode))
                .map(this::toDto)
                .toList();
    }

    @Transactional
    public WarehouseBinDto upsertBin(Long operatorId, UpsertWarehouseBinRequest request) {
        permissionService.requirePermission(operatorId, "ops:warehouse:edit");
        String wh = required(request.warehouseId(), "warehouseId").trim();
        String binCode = required(request.binCode(), "binCode").trim();
        warehouseRepository.findById(wh).orElseThrow(() -> notFound("warehouse"));
        WarehouseBin bin = binRepository.findByWarehouseIdAndBinCode(wh, binCode)
                .orElseGet(() -> {
                    WarehouseBin n = new WarehouseBin();
                    n.setWarehouseId(wh);
                    n.setBinCode(binCode);
                    n.setCreatedAt(Instant.now());
                    return n;
                });
        bin.setBinName(trimToNull(request.binName()));
        bin.setStatus(request.status() != null && !request.status().isBlank()
                ? request.status().trim().toUpperCase() : "ACTIVE");
        return toDto(binRepository.save(bin));
    }

    @Transactional(readOnly = true)
    public List<WarehouseBinStockDto> listBinStock(Long operatorId, String warehouseId, Long binId) {
        permissionService.requirePermission(operatorId, "ops:warehouse:list");
        List<WarehouseBin> bins = binRepository.findAll().stream()
                .filter(b -> binId == null || binId.equals(b.getBinId()))
                .filter(b -> warehouseId == null || warehouseId.isBlank()
                        || warehouseId.trim().equals(b.getWarehouseId()))
                .toList();
        List<WarehouseBinStockDto> out = new ArrayList<>();
        for (WarehouseBin bin : bins) {
            for (WarehouseBinStock row : binStockRepository.findByBinIdOrderByExpiryDateAsc(bin.getBinId())) {
                out.add(toStockDto(bin, row));
            }
        }
        out.sort(Comparator.comparing(WarehouseBinStockDto::expiryDate,
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(WarehouseBinStockDto::binCode)
                .thenComparing(WarehouseBinStockDto::skuId));
        return out;
    }

    @Transactional
    public void inboundToBin(Long operatorId, BinInboundRequest request) {
        permissionService.requirePermission(operatorId, "ops:warehouse:edit");
        String wh = required(request.warehouseId(), "warehouseId").trim();
        String binCode = required(request.binCode(), "binCode").trim();
        int qty = request.quantity() != null ? request.quantity() : 0;
        if (qty <= 0) {
            throw badRequest("quantity must be positive");
        }
        if (request.expiryDate() == null) {
            throw badRequest("expiryDate required");
        }
        if (request.productionDate() != null && request.productionDate().isAfter(request.expiryDate())) {
            throw badRequest("productionDate cannot be after expiryDate");
        }
        String skuId = required(request.skuId(), "skuId").trim();
        String batchNo = required(request.batchNo(), "batchNo").trim();
        if (!skuCatalogRepository.existsById(skuId)) {
            throw badRequest("sku not found: " + skuId);
        }
        WarehouseBin bin = requireActiveBin(wh, binCode);
        warehouseService.binStockChange(wh, skuId, batchNo,
                request.productionDate(), request.expiryDate(), qty,
                operatorId, "BIN_INBOUND", String.valueOf(bin.getBinId()));
        addBinStock(bin.getBinId(), skuId, batchNo,
                request.productionDate(), request.expiryDate(), qty);
    }

    @Transactional
    public void moveBetweenBins(Long operatorId, BinMoveRequest request) {
        permissionService.requirePermission(operatorId, "ops:warehouse:edit");
        if (request.fromBinId().equals(request.toBinId())) {
            throw badRequest("from and to bin must differ");
        }
        int qty = request.quantity() != null ? request.quantity() : 0;
        if (qty <= 0) {
            throw badRequest("quantity must be positive");
        }
        WarehouseBin from = binRepository.findById(request.fromBinId())
                .orElseThrow(() -> notFound("source bin"));
        WarehouseBin to = binRepository.findById(request.toBinId())
                .orElseThrow(() -> notFound("target bin"));
        if (!from.getWarehouseId().equals(to.getWarehouseId())) {
            throw badRequest("bins must belong to the same warehouse");
        }
        String skuId = required(request.skuId(), "skuId").trim();
        String batchNo = required(request.batchNo(), "batchNo").trim();
        WarehouseBinStock fromRow = binStockRepository
                .findByBinIdAndSkuIdAndBatchNo(from.getBinId(), skuId, batchNo)
                .orElseThrow(() -> badRequest("source bin has no stock: " + skuId + "/" + batchNo));
        if (fromRow.getQuantity() < qty) {
            throw badRequest("source bin insufficient stock");
        }
        fromRow.setQuantity(fromRow.getQuantity() - qty);
        fromRow.setUpdatedAt(Instant.now());
        binStockRepository.save(fromRow);
        addBinStock(to.getBinId(), skuId, batchNo,
                fromRow.getProductionDate(), fromRow.getExpiryDate(), qty);
    }

    private void addBinStock(Long binId, String skuId, String batchNo,
                             LocalDate productionDate, LocalDate expiryDate, int qty) {
        WarehouseBinStock row = binStockRepository.findByBinIdAndSkuIdAndBatchNo(binId, skuId, batchNo)
                .orElseGet(() -> {
                    WarehouseBinStock n = new WarehouseBinStock();
                    n.setBinId(binId);
                    n.setSkuId(skuId);
                    n.setBatchNo(batchNo);
                    n.setProductionDate(productionDate);
                    n.setExpiryDate(expiryDate);
                    n.setQuantity(0);
                    return n;
                });
        row.setQuantity(row.getQuantity() + qty);
        if (productionDate != null) {
            row.setProductionDate(productionDate);
        }
        if (expiryDate != null) {
            row.setExpiryDate(expiryDate);
        }
        row.setUpdatedAt(Instant.now());
        binStockRepository.save(row);
    }

    private WarehouseBin requireActiveBin(String warehouseId, String binCode) {
        return binRepository.findByWarehouseIdAndBinCode(warehouseId, binCode)
                .filter(b -> "ACTIVE".equalsIgnoreCase(b.getStatus()))
                .orElseThrow(() -> badRequest("bin not found or inactive: " + binCode));
    }

    private WarehouseBinDto toDto(WarehouseBin bin) {
        String warehouseName = warehouseRepository.findById(bin.getWarehouseId())
                .map(w -> w.getWarehouseName())
                .orElse(bin.getWarehouseId());
        return new WarehouseBinDto(
                bin.getBinId(),
                bin.getWarehouseId(),
                warehouseName,
                bin.getBinCode(),
                bin.getBinName(),
                bin.getStatus(),
                bin.getCreatedAt());
    }

    private WarehouseBinStockDto toStockDto(WarehouseBin bin, WarehouseBinStock row) {
        String skuName = skuCatalogRepository.findById(row.getSkuId())
                .map(s -> s.getSkuName())
                .orElse(row.getSkuId());
        return new WarehouseBinStockDto(
                row.getId(),
                bin.getBinId(),
                bin.getWarehouseId(),
                bin.getBinCode(),
                row.getSkuId(),
                skuName,
                row.getBatchNo(),
                row.getProductionDate(),
                row.getExpiryDate(),
                row.getQuantity());
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
}
