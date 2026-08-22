package com.aicabinet.trade.service;

import com.aicabinet.common.dto.DeviceProductDto;
import com.aicabinet.trade.domain.DeviceSkuInventory;
import com.aicabinet.trade.domain.SkuCatalog;
import com.aicabinet.trade.mapper.DeviceSkuInventoryMapper;
import com.aicabinet.trade.mapper.DeviceSkuLotMapper;
import com.aicabinet.trade.mapper.SkuCatalogMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class DeviceCatalogService {

    private final DeviceSkuInventoryMapper inventoryRepository;
    private final DeviceSkuLotMapper lotRepository;
    private final SkuCatalogMapper skuCatalogRepository;
    private final MerchantSkuPricingService skuPricingService;

    public DeviceCatalogService(DeviceSkuInventoryMapper inventoryRepository,
                                DeviceSkuLotMapper lotRepository,
                                SkuCatalogMapper skuCatalogRepository,
                                MerchantSkuPricingService skuPricingService) {
        this.inventoryRepository = inventoryRepository;
        this.lotRepository = lotRepository;
        this.skuCatalogRepository = skuCatalogRepository;
        this.skuPricingService = skuPricingService;
    }

    /**
     * 消费者可见商品库存：与运营后台货道「账面」同源（可售批次 ON_SALE/NEAR_EXPIRY）。
     * 若柜机尚无批次账本，则回退到 device_sku_inventory（旧数据兼容）。
     */
    @Transactional(readOnly = true)
    public List<DeviceProductDto> listProducts(String deviceId) {
        String dev = deviceId != null ? deviceId.trim() : "";
        if (dev.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "deviceId required");
        }

        Map<String, Integer> qtyBySku = sellableQtyBySku(dev);
        boolean useLots = !lotRepository.findByDeviceId(dev).isEmpty();
        if (!useLots) {
            qtyBySku = inventoryQtyBySku(dev);
        }

        List<String> skuIds = qtyBySku.entrySet().stream()
                .filter(e -> e.getValue() != null && e.getValue() > 0)
                .map(Map.Entry::getKey)
                .toList();
        if (skuIds.isEmpty()) {
            return List.of();
        }

        Map<String, SkuCatalog> skuMap = skuCatalogRepository.findAllById(skuIds).stream()
                .filter(s -> "ACTIVE".equals(s.getStatus()))
                .collect(Collectors.toMap(SkuCatalog::getSkuId, s -> s));

        List<DeviceProductDto> result = new ArrayList<>();
        for (String skuId : skuIds) {
            SkuCatalog sku = skuMap.get(skuId);
            if (sku == null) {
                continue;
            }
            int qty = qtyBySku.getOrDefault(skuId, 0);
            if (qty <= 0) {
                continue;
            }
            result.add(new DeviceProductDto(
                    sku.getSkuId(),
                    sku.getSkuName(),
                    skuPricingService.resolveUnitPriceCents(dev, sku),
                    qty,
                    sku.getImageUrl(),
                    sku.getCategory(),
                    sku.getDescription()
            ));
        }
        result.sort((a, b) -> a.skuName().compareToIgnoreCase(b.skuName()));
        return result;
    }

    private Map<String, Integer> sellableQtyBySku(String deviceId) {
        Map<String, Integer> map = new HashMap<>();
        for (Object[] row : lotRepository.sumSellableBySku(deviceId)) {
            if (row == null || row.length < 2 || row[0] == null || row[1] == null) {
                continue;
            }
            map.merge(String.valueOf(row[0]), ((Number) row[1]).intValue(), Integer::sum);
        }
        return map;
    }

    private Map<String, Integer> inventoryQtyBySku(String deviceId) {
        Map<String, Integer> map = new HashMap<>();
        for (DeviceSkuInventory row : inventoryRepository.findByIdDeviceId(deviceId)) {
            if (row.getQuantity() > 0) {
                map.put(row.getId().getSkuId(), row.getQuantity());
            }
        }
        return map;
    }
}
