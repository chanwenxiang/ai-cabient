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
    private final SysDictService sysDictService;

    public DeviceCatalogService(DeviceSkuInventoryMapper inventoryRepository,
                                DeviceSkuLotMapper lotRepository,
                                SkuCatalogMapper skuCatalogRepository,
                                MerchantSkuPricingService skuPricingService,
                                SysDictService sysDictService) {
        this.inventoryRepository = inventoryRepository;
        this.lotRepository = lotRepository;
        this.skuCatalogRepository = skuCatalogRepository;
        this.skuPricingService = skuPricingService;
        this.sysDictService = sysDictService;
    }

    /**
     * 消费者可见商品库存：与运营后台货道「账面」同源（可售批次 ON_SALE/NEAR_EXPIRY），
     * 但**只统计启用货道** —— 运营在设备详情里关掉的货道，其商品不再对消费者可见。
     * 若柜机尚无批次账本，则回退到 device_sku_inventory（旧数据兼容）。
     */
    @Transactional(readOnly = true)
    public List<DeviceProductDto> listProducts(String deviceId) {
        String dev = deviceId != null ? deviceId.trim() : "";
        if (dev.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "deviceId required");
        }

        Map<String, Integer> qtyBySku = sellableQtyBySkuOnEnabledSlots(dev);
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
        // 促销策略快照读一次、循环内复用：配置是直读 DB 无缓存的，逐 SKU 读会放大成 N×4 次查库。
        PricingPromoPolicy promoPolicy = skuPricingService.loadPromoPolicy();
        // 分类字典映射一次取全（2026-10-22）：sku_catalog.category 存的是 category_code 字典值（如 1），
        // 消费者端直接显示会露出「1」这种字典码；此处统一翻译成中文标签（如「饮料」）。
        // 字典没配的值（历史自由文本如「零食」）原样返回，不吞掉。
        Map<String, String> categoryLabels = sysDictService.labelMapOf("category_code");
        for (String skuId : skuIds) {
            SkuCatalog sku = skuMap.get(skuId);
            int qty = qtyBySku.getOrDefault(skuId, 0);
            if (sku != null && qty > 0) {
                result.add(new DeviceProductDto(
                    sku.getSkuId(),
                    sku.getSkuName(),
                    sku.getSpec(),
                    sku.getUnit(),
                    skuPricingService.resolveUnitPriceCents(dev, sku, promoPolicy),
                    qty,
                    sku.getImageUrl(),
                    displayCategory(sku.getCategory(), categoryLabels),
                    sku.getDescription()
            ));
            }
        }
        result.sort((a, b) -> a.skuName().compareToIgnoreCase(b.skuName()));
        return result;
    }

    /**
     * 消费者口径的可售量：**只统计启用货道**上的批次。
     *
     * <p>刻意不复用 {@code sumSellableBySku}（运营口径的物理库存全量）：那个口径下禁用货道也要算，
     * 拿来当消费者可见量会让「禁用货道」开关失效 —— 禁用后小程序照样显示（线上实测过）。
     */
    private Map<String, Integer> sellableQtyBySkuOnEnabledSlots(String deviceId) {
        Map<String, Integer> map = new HashMap<>();
        for (Object[] row : lotRepository.sumSellableBySkuOnEnabledSlots(deviceId)) {
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

    /**
     * 分类展示值：字典值翻译成中文标签；空值与字典未收录的值（历史自由文本）原样返回。
     * 查找口径与 {@code SysDictService.labelOf} 一致（value trim+大写后匹配）。
     */
    private static String displayCategory(String raw, Map<String, String> categoryLabels) {
        if (raw == null || raw.isBlank()) {
            return raw;
        }
        return categoryLabels.getOrDefault(raw.trim().toUpperCase(), raw);
    }
}
