package com.aicabinet.trade.service;

import com.aicabinet.common.dto.StocktakeAdjustRequest;
import com.aicabinet.common.dto.WriteOffDto;
import com.aicabinet.common.dto.WriteOffRequest;
import com.aicabinet.trade.domain.DeviceSkuInventory;
import com.aicabinet.trade.domain.DeviceSkuInventoryId;
import com.aicabinet.trade.domain.InventoryWriteOff;
import com.aicabinet.trade.domain.SkuCatalog;
import com.aicabinet.trade.repository.DeviceSkuInventoryRepository;
import com.aicabinet.trade.repository.InventoryWriteOffRepository;
import com.aicabinet.trade.repository.SkuCatalogRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Set;
import java.util.UUID;

@Service
public class InventoryOpsService {

    private static final Set<String> WRITE_OFF_REASONS = Set.of(
            "EXPIRED", "DAMAGED", "THEFT", "OTHER");

    private final InventoryLotService lotService;
    private final DeviceValidationService deviceValidationService;
    private final SkuCatalogRepository skuCatalogRepository;
    private final DeviceSkuInventoryRepository inventoryRepository;
    private final InventoryWriteOffRepository writeOffRepository;

    public InventoryOpsService(InventoryLotService lotService,
                               DeviceValidationService deviceValidationService,
                               SkuCatalogRepository skuCatalogRepository,
                               DeviceSkuInventoryRepository inventoryRepository,
                               InventoryWriteOffRepository writeOffRepository) {
        this.lotService = lotService;
        this.deviceValidationService = deviceValidationService;
        this.skuCatalogRepository = skuCatalogRepository;
        this.inventoryRepository = inventoryRepository;
        this.writeOffRepository = writeOffRepository;
    }

    @Transactional
    public WriteOffDto writeOff(Long operatorId, WriteOffRequest request) {
        deviceValidationService.requireDevice(request.deviceId());
        skuCatalogRepository.findById(request.skuId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "sku not found"));
        String reason = request.reason().trim().toUpperCase();
        if (!WRITE_OFF_REASONS.contains(reason)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid write-off reason");
        }

        String refId = "WO-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        lotService.writeOffLots(request.deviceId(), request.skuId(), request.batchNo(),
                request.quantity(), operatorId, refId);

        SkuCatalog sku = skuCatalogRepository.findById(request.skuId()).orElseThrow();
        Integer unitCost = sku.getPurchaseCostCents();
        int costCents = unitCost != null ? unitCost * request.quantity() : 0;

        InventoryWriteOff record = new InventoryWriteOff();
        record.setDeviceId(request.deviceId());
        record.setSkuId(request.skuId());
        record.setBatchNo(request.batchNo());
        record.setQuantity(request.quantity());
        record.setReason(reason);
        record.setCostCents(costCents);
        record.setOperatorId(operatorId);
        record = writeOffRepository.save(record);

        return new WriteOffDto(
                record.getWriteOffId(),
                record.getDeviceId(),
                record.getSkuId(),
                record.getBatchNo(),
                record.getQuantity(),
                record.getReason(),
                record.getCostCents(),
                record.getOperatorId(),
                record.getCreatedAt()
        );
    }

    @Transactional
    public DeviceSkuInventory stocktakeAdjust(Long operatorId, StocktakeAdjustRequest request) {
        deviceValidationService.requireDevice(request.deviceId());
        skuCatalogRepository.findById(request.skuId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "sku not found"));

        DeviceSkuInventoryId id = new DeviceSkuInventoryId(request.deviceId(), request.skuId());
        DeviceSkuInventory inv = inventoryRepository.findById(id).orElseGet(() -> {
            DeviceSkuInventory created = new DeviceSkuInventory();
            created.setId(id);
            created.setCapacity(20);
            created.setLowThreshold(2);
            created.setQuantity(0);
            return created;
        });

        int current = inv.getQuantity();
        int delta = request.countedQuantity() - current;
        if (delta == 0) {
            return inv;
        }

        String refId = "ST-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        lotService.stocktakeAdjust(request.deviceId(), request.skuId(),
                request.countedQuantity(), operatorId, refId);

        return inventoryRepository.findById(id).orElseThrow();
    }
}
