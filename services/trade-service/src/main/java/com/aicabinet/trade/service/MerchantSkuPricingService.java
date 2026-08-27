package com.aicabinet.trade.service;

import com.aicabinet.common.dto.MerchantSkuPriceChangeDto;
import com.aicabinet.common.dto.MerchantSkuPricingDto;
import com.aicabinet.common.dto.UpdateMerchantSkuPriceRequest;
import com.aicabinet.trade.domain.*;
import com.aicabinet.trade.mapper.*;
import com.aicabinet.trade.support.ApiMessages;
import com.aicabinet.trade.support.MerchantPortalGuard;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class MerchantSkuPricingService {
    private static final String MERCHANT_SKU_PRICE = "MERCHANT_SKU_PRICE";


    private static final int DEFAULT_MAX_MULTIPLIER = 2;

    private final DeviceSkuPriceMapper priceRepository;
    private final DeviceSkuInventoryMapper inventoryRepository;
    private final SkuCatalogMapper skuCatalogRepository;
    private final DeviceInfoMapper deviceRepository;
    private final PermissionService permissionService;
    private final MerchantPortalGuard merchantPortalGuard;
    private final AdminAuditService auditService;
    private final AdminAuditLogMapper auditLogRepository;
    private final MerchantSelfServiceGate merchantSelfServiceGate;
    private final MerchantFeaturePackService merchantFeaturePackService;
    private final InventoryLotService inventoryLotService;
    private final DistributedLockService distributedLockService;
    /** 经 Spring 代理调用本类 @Transactional 方法，避免自调用失效。 */
    private final MerchantSkuPricingService self;

    public MerchantSkuPricingService(DeviceSkuPriceMapper priceRepository,
                                     DeviceSkuInventoryMapper inventoryRepository,
                                     SkuCatalogMapper skuCatalogRepository,
                                     DeviceInfoMapper deviceRepository,
                                     PermissionService permissionService,
                                     MerchantPortalGuard merchantPortalGuard,
                                     AdminAuditService auditService,
                                     AdminAuditLogMapper auditLogRepository,
                                     MerchantSelfServiceGate merchantSelfServiceGate,
                                     MerchantFeaturePackService merchantFeaturePackService,
                                     InventoryLotService inventoryLotService,
                                     DistributedLockService distributedLockService, @Lazy MerchantSkuPricingService self) {
        this.priceRepository = priceRepository;
        this.inventoryRepository = inventoryRepository;
        this.skuCatalogRepository = skuCatalogRepository;
        this.deviceRepository = deviceRepository;
        this.permissionService = permissionService;
        this.merchantPortalGuard = merchantPortalGuard;
        this.auditService = auditService;
        this.auditLogRepository = auditLogRepository;
        this.merchantSelfServiceGate = merchantSelfServiceGate;
        this.merchantFeaturePackService = merchantFeaturePackService;
        this.inventoryLotService = inventoryLotService;
        this.distributedLockService = distributedLockService;
        this.self = self;
    }

    @Transactional(readOnly = true)
    public int resolveUnitPriceCents(String deviceId, SkuCatalog sku) {
        if (deviceId == null || sku == null) {
            return sku != null ? sku.getPriceCents() : 0;
        }
        return priceRepository.findByDeviceIdAndSkuId(deviceId, sku.getSkuId())
                .map(DeviceSkuPrice::getPriceCents)
                .orElse(sku.getPriceCents());
    }

    @Transactional(readOnly = true)
    public List<MerchantSkuPricingDto> listPricing(Long userId, String deviceId) {
        permissionService.requirePermission(userId, "merchant:pricing:view");
        merchantPortalGuard.requireAccess(userId);
        Set<String> allowedDevices = merchantFeaturePackService.allowedDeviceIdsForPack(
                userId, MerchantFeaturePacks.BIZ);
        if (allowedDevices != null && allowedDevices.isEmpty()) {
            return List.of();
        }
        if (deviceId != null && !deviceId.isBlank()) {
            merchantFeaturePackService.requireDevicePack(userId, deviceId.trim(), MerchantFeaturePacks.BIZ);
        }
        List<DeviceInfo> devices = merchantFeaturePackService.allowedDevicesForPack(userId, MerchantFeaturePacks.BIZ).stream()
                .filter(d -> deviceId == null || deviceId.isBlank() || deviceId.trim().equals(d.getDeviceId()))
                .toList();
        if (devices.isEmpty()) {
            return List.of();
        }
        Set<String> deviceIds = devices.stream().map(DeviceInfo::getDeviceId).collect(Collectors.toSet());
        Map<String, String> deviceNames = devices.stream()
                .collect(Collectors.toMap(DeviceInfo::getDeviceId, DeviceInfo::getDeviceName));
        Map<String, Map<String, DeviceSkuPrice>> overrideByDevice = priceRepository.findByIdDeviceIdIn(deviceIds)
                .stream()
                .collect(Collectors.groupingBy(p -> p.getId().getDeviceId(),
                        Collectors.toMap(p -> p.getId().getSkuId(), p -> p, (a, b) -> a)));

        List<MerchantSkuPricingDto> rows = new ArrayList<>();
        for (String devId : deviceIds) {
            rows.addAll(buildPricingRowsForDevice(devId, deviceNames, overrideByDevice));
        }
        rows.sort(Comparator
                .comparing(MerchantSkuPricingDto::deviceId)
                .thenComparing(MerchantSkuPricingDto::skuName));
        return rows;
    }

    private List<MerchantSkuPricingDto> buildPricingRowsForDevice(
            String deviceId,
            Map<String, String> deviceNames,
            Map<String, Map<String, DeviceSkuPrice>> overrideByDevice) {
        List<DeviceSkuInventory> invRows = inventoryRepository.findByIdDeviceId(deviceId);
        Set<String> skuIds = invRows.stream().map(r -> r.getId().getSkuId()).collect(Collectors.toSet());
        if (skuIds.isEmpty()) {
            return List.of();
        }
        Map<String, SkuCatalog> skuMap = skuCatalogRepository.findAllById(skuIds).stream()
                .filter(s -> "ACTIVE".equalsIgnoreCase(s.getStatus()))
                .collect(Collectors.toMap(SkuCatalog::getSkuId, s -> s));
        Map<String, Integer> qtyBySku = resolveSellableQtyBySku(deviceId, invRows, skuIds);
        Map<String, DeviceSkuPrice> overrides = overrideByDevice.getOrDefault(deviceId, Map.of());
        List<MerchantSkuPricingDto> rows = new ArrayList<>();
        for (String skuId : skuIds) {
            SkuCatalog sku = skuMap.get(skuId);
            if (sku == null) {
                continue;
            }
            DeviceSkuPrice override = overrides.get(skuId);
            int effective = override != null ? override.getPriceCents() : sku.getPriceCents();
            rows.add(new MerchantSkuPricingDto(
                    deviceId,
                    deviceNames.getOrDefault(deviceId, deviceId),
                    skuId,
                    sku.getSkuName(),
                    sku.getPriceCents(),
                    override != null ? override.getPriceCents() : null,
                    effective,
                    minAllowedPrice(sku),
                    maxAllowedPrice(sku),
                    qtyBySku.getOrDefault(skuId, 0),
                    override != null ? override.getUpdatedAt() : null,
                    sku.getImageUrl(),
                    sku.getBarcode()
            ));
        }
        return rows;
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

    @Transactional
    public MerchantSkuPricingDto updatePricing(Long userId, String skuId,
                                             UpdateMerchantSkuPriceRequest request) {
        permissionService.requirePermission(userId, "merchant:pricing:edit");
        merchantPortalGuard.requireAccess(userId);
        merchantSelfServiceGate.requirePricingEdit(userId);
        if (request.deviceId() == null || request.deviceId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "设备 ID 不能为空");
        }
        String deviceId = request.deviceId().trim();
        return runWithSkuPriceLock(deviceId, skuId, () -> doUpdatePricing(userId, skuId, deviceId, request));
    }

    private MerchantSkuPricingDto doUpdatePricing(Long userId, String skuId, String deviceId,
                                                  UpdateMerchantSkuPriceRequest request) {
        merchantFeaturePackService.requireDevicePack(userId, deviceId, MerchantFeaturePacks.BIZ);
        SkuCatalog sku = skuCatalogRepository.findById(skuId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.SKU_NOT_FOUND));
        DeviceInfo device = deviceRepository.findById(deviceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.DEVICE_NOT_FOUND));

        Integer newPrice = request.priceCents();
        DeviceSkuPriceId priceId = new DeviceSkuPriceId(deviceId, skuId);
        Optional<DeviceSkuPrice> existing = priceRepository.findByDeviceIdAndSkuIdForUpdate(deviceId, skuId);
        Integer oldOverride = existing.map(DeviceSkuPrice::getPriceCents).orElse(null);

        if (newPrice == null) {
            existing.ifPresent(priceRepository::delete);
            auditService.appendLog(userId, MERCHANT_SKU_PRICE, "SKU_PRICE", deviceId + ":" + skuId,
                    "reset to base " + sku.getPriceCents() + " (was override " + oldOverride + ")");
        } else {
            validatePrice(sku, newPrice);
            DeviceSkuPrice row = existing.orElseGet(DeviceSkuPrice::new);
            row.setId(priceId);
            row.setPriceCents(newPrice);
            row.setUpdatedByUserId(userId);
            priceRepository.save(row);
            auditService.appendLog(userId, MERCHANT_SKU_PRICE, "SKU_PRICE", deviceId + ":" + skuId,
                    "base=" + sku.getPriceCents() + " override " + oldOverride + " -> " + newPrice);
        }

        int qty = inventoryLotService.deviceUsesLotLedger(deviceId)
                ? inventoryLotService.sellableQuantity(deviceId, skuId)
                : inventoryRepository.findByIdDeviceId(deviceId).stream()
                        .filter(r -> skuId.equals(r.getId().getSkuId()))
                        .mapToInt(DeviceSkuInventory::getQuantity)
                        .sum();
        Optional<DeviceSkuPrice> saved = priceRepository.findByDeviceIdAndSkuId(deviceId, skuId);
        return new MerchantSkuPricingDto(
                deviceId,
                device.getDeviceName(),
                skuId,
                sku.getSkuName(),
                sku.getPriceCents(),
                saved.map(DeviceSkuPrice::getPriceCents).orElse(null),
                self.resolveUnitPriceCents(deviceId, sku),
                minAllowedPrice(sku),
                maxAllowedPrice(sku),
                qty,
                saved.map(DeviceSkuPrice::getUpdatedAt).orElse(null),
                sku.getImageUrl(),
                sku.getBarcode()
        );
    }

    @Transactional(readOnly = true)
    public List<MerchantSkuPriceChangeDto> listPriceHistory(Long userId, String deviceId, String skuId) {
        permissionService.requirePermission(userId, "merchant:pricing:view");
        merchantPortalGuard.requireAccess(userId);
        String requestedDeviceId = normalize(deviceId);
        String requestedSkuId = normalize(skuId);
        Set<String> allowedDeviceIds = merchantFeaturePackService.allowedDeviceIdsForPack(
                userId, MerchantFeaturePacks.BIZ);
        if (requestedDeviceId != null) {
            merchantFeaturePackService.requireDevicePack(
                    userId, requestedDeviceId, MerchantFeaturePacks.BIZ);
        }
        return auditLogRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(0, 200)).stream()
                .filter(l -> MERCHANT_SKU_PRICE.equals(l.getAction()))
                .filter(l -> priceHistoryTargetMatches(
                        l.getTargetId(), requestedDeviceId, requestedSkuId, allowedDeviceIds))
                .limit(50)
                .map(l -> {
                    String dev = l.getTargetId();
                    String sku = null;
                    if (dev != null && dev.contains(":")) {
                        int idx = dev.indexOf(':');
                        sku = dev.substring(idx + 1);
                        dev = dev.substring(0, idx);
                    }
                    return new MerchantSkuPriceChangeDto(dev, sku, l.getDetail(), l.getCreatedAt());
                })
                .toList();
    }

    static boolean priceHistoryTargetMatches(String targetId,
                                             String requestedDeviceId,
                                             String requestedSkuId,
                                             Set<String> allowedDeviceIds) {
        if (targetId == null) {
            return false;
        }
        int separator = targetId.indexOf(':');
        if (separator <= 0 || separator == targetId.length() - 1) {
            return false;
        }
        String targetDeviceId = targetId.substring(0, separator);
        String targetSkuId = targetId.substring(separator + 1);
        if (allowedDeviceIds != null && !allowedDeviceIds.contains(targetDeviceId)) {
            return false;
        }
        if (requestedDeviceId != null && !requestedDeviceId.equals(targetDeviceId)) {
            return false;
        }
        return requestedSkuId == null || requestedSkuId.equals(targetSkuId);
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private void validatePrice(SkuCatalog sku, int priceCents) {
        if (priceCents <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "价格必须大于 0");
        }
        int min = minAllowedPrice(sku);
        int max = maxAllowedPrice(sku);
        if (priceCents < min) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "价格不能低于最低限价 ¥" + (min / 100.0));
        }
        if (priceCents > max) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "价格不能高于最高限价 ¥" + (max / 100.0));
        }
    }

    private static int minAllowedPrice(SkuCatalog sku) {
        if (sku.getPurchaseCostCents() != null && sku.getPurchaseCostCents() > 0) {
            return sku.getPurchaseCostCents();
        }
        return 1;
    }

    private static int maxAllowedPrice(SkuCatalog sku) {
        if (sku.getMaxPriceCents() != null && sku.getMaxPriceCents() > 0) {
            return sku.getMaxPriceCents();
        }
        return Math.max(sku.getPriceCents() * DEFAULT_MAX_MULTIPLIER, sku.getPriceCents());
    }

    static String skuPriceLockKey(String deviceId, String skuId) {
        return "merchant:sku-price:" + deviceId + ":" + skuId;
    }

    private <T> T runWithSkuPriceLock(String deviceId, String skuId, java.util.function.Supplier<T> action) {
        if (!distributedLockService.tryLock(skuPriceLockKey(deviceId, skuId), 60, 5)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "商品价格调整处理中，请稍后重试");
        }
        try {
            return action.get();
        } finally {
            distributedLockService.unlock(skuPriceLockKey(deviceId, skuId));
        }
    }

}
