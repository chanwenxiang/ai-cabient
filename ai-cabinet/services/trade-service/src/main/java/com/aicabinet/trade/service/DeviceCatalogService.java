package com.aicabinet.trade.service;

import com.aicabinet.common.dto.DeviceProductDto;
import com.aicabinet.trade.domain.DeviceSkuInventory;
import com.aicabinet.trade.domain.SkuCatalog;
import com.aicabinet.trade.repository.DeviceSkuInventoryRepository;
import com.aicabinet.trade.repository.SkuCatalogRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class DeviceCatalogService {

    private final DeviceSkuInventoryRepository inventoryRepository;
    private final SkuCatalogRepository skuCatalogRepository;

    public DeviceCatalogService(DeviceSkuInventoryRepository inventoryRepository,
                                SkuCatalogRepository skuCatalogRepository) {
        this.inventoryRepository = inventoryRepository;
        this.skuCatalogRepository = skuCatalogRepository;
    }

    @Transactional(readOnly = true)
    public List<DeviceProductDto> listProducts(String deviceId) {
        String dev = deviceId != null ? deviceId.trim() : "";
        if (dev.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "deviceId required");
        }
        List<DeviceSkuInventory> rows = inventoryRepository.findByIdDeviceId(dev);
        if (rows.isEmpty()) {
            return List.of();
        }
        List<String> skuIds = rows.stream()
                .filter(r -> r.getQuantity() > 0)
                .map(r -> r.getId().getSkuId())
                .toList();
        if (skuIds.isEmpty()) {
            return List.of();
        }
        Map<String, SkuCatalog> skuMap = skuCatalogRepository.findAllById(skuIds).stream()
                .filter(s -> "ACTIVE".equals(s.getStatus()))
                .collect(Collectors.toMap(SkuCatalog::getSkuId, s -> s));
        List<DeviceProductDto> result = new ArrayList<>();
        for (DeviceSkuInventory row : rows) {
            if (row.getQuantity() <= 0) {
                continue;
            }
            SkuCatalog sku = skuMap.get(row.getId().getSkuId());
            if (sku == null) {
                continue;
            }
            result.add(new DeviceProductDto(
                    sku.getSkuId(),
                    sku.getSkuName(),
                    sku.getPriceCents(),
                    row.getQuantity(),
                    sku.getImageUrl(),
                    sku.getCategory(),
                    sku.getDescription()
            ));
        }
        result.sort((a, b) -> a.skuName().compareToIgnoreCase(b.skuName()));
        return result;
    }
}
