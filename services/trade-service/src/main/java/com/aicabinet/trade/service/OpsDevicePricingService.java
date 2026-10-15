package com.aicabinet.trade.service;

import com.aicabinet.common.dto.DeviceSkuPricingRowDto;
import com.aicabinet.common.dto.UpdateDeviceSkuPriceRequest;
import com.aicabinet.trade.domain.DeviceInfo;
import com.aicabinet.trade.domain.DeviceSkuInventory;
import com.aicabinet.trade.domain.DeviceSkuPrice;
import com.aicabinet.trade.domain.DeviceSkuPriceId;
import com.aicabinet.trade.domain.SkuCatalog;
import com.aicabinet.trade.mapper.DeviceInfoMapper;
import com.aicabinet.trade.mapper.DeviceSkuInventoryMapper;
import com.aicabinet.trade.mapper.DeviceSkuPriceMapper;
import com.aicabinet.trade.mapper.SkuCatalogMapper;
import com.aicabinet.trade.support.ApiMessages;
import com.aicabinet.trade.support.OptimisticLocking;
import com.aicabinet.trade.support.SkuPriceBounds;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * admin 设备详情页「单柜定价」（V350，CB-037）。
 *
 * <p>复用既有 {@code device_sku_price} 覆盖价与 {@link MerchantSkuPricingService#resolveUnitPriceCents}
 * 取价链路——本类只补 admin 侧入口与「启用/停用」开关，不引入第二套价格模型。
 * 限价规则与商户端同源（{@link SkuPriceBounds}）。
 */
@Service
public class OpsDevicePricingService {

    private static final String OPS_SKU_PRICE = "OPS_SKU_PRICE";

    private final DeviceSkuPriceMapper priceRepository;
    private final DeviceSkuInventoryMapper inventoryRepository;
    private final SkuCatalogMapper skuCatalogRepository;
    private final DeviceInfoMapper deviceRepository;
    private final PermissionService permissionService;
    private final AdminAuditService auditService;
    private final InventoryLotService inventoryLotService;
    private final DistributedLockService distributedLockService;
    /** 复用商户端取价链路（覆盖价→基准价→临期价→折扣）；经 Spring 代理，避免自调用问题。 */
    private final MerchantSkuPricingService pricingService;

    public OpsDevicePricingService(DeviceSkuPriceMapper priceRepository,
                                   DeviceSkuInventoryMapper inventoryRepository,
                                   SkuCatalogMapper skuCatalogRepository,
                                   DeviceInfoMapper deviceRepository,
                                   PermissionService permissionService,
                                   AdminAuditService auditService,
                                   InventoryLotService inventoryLotService,
                                   DistributedLockService distributedLockService,
                                   @Lazy MerchantSkuPricingService pricingService) {
        this.priceRepository = priceRepository;
        this.inventoryRepository = inventoryRepository;
        this.skuCatalogRepository = skuCatalogRepository;
        this.deviceRepository = deviceRepository;
        this.permissionService = permissionService;
        this.auditService = auditService;
        this.inventoryLotService = inventoryLotService;
        this.distributedLockService = distributedLockService;
        this.pricingService = pricingService;
    }

    @Transactional(readOnly = true)
    public List<DeviceSkuPricingRowDto> listPricing(Long operatorId, String deviceId) {
        permissionService.requirePermission(operatorId, "ops:device:list");
        DeviceInfo device = requireDevice(deviceId);
        List<DeviceSkuInventory> invRows = inventoryRepository.findByIdDeviceId(deviceId);
        Set<String> skuIds = invRows.stream().map(r -> r.getId().getSkuId()).collect(Collectors.toSet());
        if (skuIds.isEmpty()) {
            return List.of();
        }
        Map<String, SkuCatalog> skuMap = skuCatalogRepository.findAllById(skuIds).stream()
                .collect(Collectors.toMap(SkuCatalog::getSkuId, s -> s));
        Map<String, Integer> qtyBySku = resolveSellableQtyBySku(deviceId, invRows, skuIds);
        Map<String, DeviceSkuPrice> overrides = priceRepository.findByIdDeviceId(deviceId).stream()
                .collect(Collectors.toMap(p -> p.getId().getSkuId(), p -> p, (a, b) -> a));
        return skuIds.stream()
                .map(skuMap::get)
                .filter(sku -> sku != null)
                .sorted((a, b) -> a.getSkuId().compareTo(b.getSkuId()))
                .map(sku -> toRow(deviceId, device.getDeviceName(), sku,
                        overrides.get(sku.getSkuId()), qtyBySku.getOrDefault(sku.getSkuId(), 0)))
                .toList();
    }

    @Transactional
    public DeviceSkuPricingRowDto updatePricing(Long operatorId, String deviceId, String skuId,
                                                UpdateDeviceSkuPriceRequest request) {
        permissionService.requirePermission(operatorId, "ops:pricing:edit");
        DeviceInfo device = requireDevice(deviceId);
        SkuCatalog sku = skuCatalogRepository.findById(skuId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.SKU_NOT_FOUND));
        if (!distributedLockService.tryLock(MerchantSkuPricingService.skuPriceLockKey(deviceId, skuId), 60, 5)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "商品价格调整处理中，请稍后重试");
        }
        try {
            return doUpdate(operatorId, device, sku, request);
        } finally {
            distributedLockService.unlock(MerchantSkuPricingService.skuPriceLockKey(deviceId, skuId));
        }
    }

    private DeviceSkuPricingRowDto doUpdate(Long operatorId, DeviceInfo device, SkuCatalog sku,
                                            UpdateDeviceSkuPriceRequest request) {
        String deviceId = device.getDeviceId();
        String skuId = sku.getSkuId();
        Optional<DeviceSkuPrice> existing = priceRepository.findByDeviceIdAndSkuIdForUpdate(deviceId, skuId);
        Integer oldOverride = existing.map(DeviceSkuPrice::getPriceCents).orElse(null);
        if (existing.isPresent()) {
            OptimisticLocking.requireMatchingExpectedVersion(
                    request.expectedVersion(), existing.get().getVersion());
        }

        Integer newPrice = request.priceCents();
        boolean enabled = request.enabled() != null
                ? request.enabled()
                : existing.map(DeviceSkuPrice::isEnabled).orElse(true);
        if (newPrice != null) {
            SkuPriceBounds.validatePrice(sku, newPrice);
        }

        if (newPrice == null) {
            // 清除覆盖价 = 删除行（跟随基准价）；「停用」用 enabled=false 表达，不删行（保留预设价）
            existing.ifPresent(priceRepository::delete);
            auditService.appendLog(operatorId, OPS_SKU_PRICE, "SKU_PRICE", deviceId + ":" + skuId,
                    MerchantSkuPricingService.formatPriceChangeAuditDetail(
                            sku.getPriceCents(), oldOverride, null));
        } else {
            DeviceSkuPrice row = existing.orElseGet(DeviceSkuPrice::new);
            boolean wasEnabled = existing.map(DeviceSkuPrice::isEnabled).orElse(true);
            row.setId(new DeviceSkuPriceId(deviceId, skuId));
            row.setVersion(existing.isEmpty() ? 0 : request.expectedVersion());
            row.setPriceCents(newPrice);
            row.setEnabled(enabled);
            row.setUpdatedByUserId(operatorId);
            priceRepository.save(row);
            String detail = MerchantSkuPricingService.formatPriceChangeAuditDetail(
                    sku.getPriceCents(), oldOverride, newPrice);
            if (enabled != wasEnabled) {
                detail += enabled ? "；覆盖价启用" : "；覆盖价停用（预设价暂不生效）";
            }
            auditService.appendLog(operatorId, OPS_SKU_PRICE, "SKU_PRICE", deviceId + ":" + skuId, detail);
        }

        int qty = resolveSellableQtyBySku(deviceId,
                inventoryRepository.findByIdDeviceId(deviceId), Set.of(skuId)).getOrDefault(skuId, 0);
        DeviceSkuPrice saved = priceRepository.findByDeviceIdAndSkuId(deviceId, skuId).orElse(null);
        return toRow(deviceId, device.getDeviceName(), sku, saved, qty);
    }

    private DeviceSkuPricingRowDto toRow(String deviceId, String deviceName, SkuCatalog sku,
                                         DeviceSkuPrice override, int sellableQty) {
        int base = sku.getPriceCents();
        boolean overrideActive = override != null && override.isEnabled();
        int effective = overrideActive ? override.getPriceCents() : base;
        // 生效价只做「覆盖 vs 基准」的静态对比；时段/清仓折扣在下单时由取价链路叠加，此处不预演
        return new DeviceSkuPricingRowDto(
                deviceId,
                deviceName,
                sku.getSkuId(),
                sku.getSkuName(),
                base,
                override != null ? override.getPriceCents() : null,
                override != null && override.isEnabled(),
                effective,
                SkuPriceBounds.minAllowedPrice(sku),
                SkuPriceBounds.maxAllowedPrice(sku),
                sellableQty,
                sku.getImageUrl(),
                sku.getPurchaseCostCents(),
                override != null ? override.getVersion() : 0L,
                override != null ? override.getUpdatedAt() : null
        );
    }

    /**
     * 批量重置价格（图1 对照，叮当小柜「重置价格」按钮，2026-10-15 用户拍板：恢复商品库默认价）。
     * 实现=清除该柜全部覆盖价行（device_sku_price 删行即跟随基准价）。
     * 并发说明：未逐 SKU 加锁，与单 SKU 改价并发时以先落库者为准（运营低频操作，可接受）。
     */
    @Transactional
    public int resetPricing(Long operatorId, String deviceId) {
        permissionService.requirePermission(operatorId, "ops:pricing:edit");
        DeviceInfo device = requireDevice(deviceId);
        List<DeviceSkuPrice> overrides = priceRepository.findByIdDeviceId(deviceId);
        if (overrides.isEmpty()) {
            return 0;
        }
        String detail = overrides.stream()
                .map(o -> o.getId().getSkuId() + ":" + o.getPriceCents() + "→基准价")
                .collect(Collectors.joining("，"));
        overrides.forEach(priceRepository::delete);
        auditService.appendLog(operatorId, OPS_SKU_PRICE, "SKU_PRICE", device.getDeviceId(),
                "批量重置价格（恢复商品库默认价），清除 " + overrides.size() + " 个覆盖价[" + detail + "]");
        return overrides.size();
    }

    private DeviceInfo requireDevice(String deviceId) {
        DeviceInfo device = deviceRepository.findById(deviceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.DEVICE_NOT_FOUND));
        return device;
    }

    private Map<String, Integer> resolveSellableQtyBySku(String deviceId,
                                                         List<DeviceSkuInventory> invRows,
                                                         Set<String> skuIds) {
        if (inventoryLotService.deviceUsesLotLedger(deviceId)) {
            Map<String, Integer> qtyBySku = new HashMap<>(inventoryLotService.sellableQtyBySku(deviceId));
            for (String skuId : skuIds) {
                qtyBySku.putIfAbsent(skuId, 0);
            }
            return qtyBySku;
        }
        return invRows.stream()
                .collect(Collectors.toMap(r -> r.getId().getSkuId(), DeviceSkuInventory::getQuantity, Integer::sum));
    }
}
