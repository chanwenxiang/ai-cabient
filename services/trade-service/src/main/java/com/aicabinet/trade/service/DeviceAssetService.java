package com.aicabinet.trade.service;

import com.aicabinet.common.dto.DeviceLifecycleEventDto;
import com.aicabinet.common.dto.DeviceLifecycleRequest;
import com.aicabinet.common.dto.StockHealthPageDto;
import com.aicabinet.common.dto.StockHealthRowDto;
import com.aicabinet.trade.domain.DeviceInfo;
import com.aicabinet.trade.domain.DeviceLifecycleEvent;
import com.aicabinet.trade.domain.DeviceSkuInventory;
import com.aicabinet.trade.domain.DeviceSkuLot;
import com.aicabinet.trade.domain.SkuCatalog;
import com.aicabinet.trade.mapper.DeviceInfoMapper;
import com.aicabinet.trade.mapper.DeviceLifecycleEventMapper;
import com.aicabinet.trade.mapper.DeviceSkuInventoryMapper;
import com.aicabinet.trade.mapper.DeviceSkuLotMapper;
import com.aicabinet.trade.mapper.MerchantMapper;
import com.aicabinet.trade.mapper.SkuCatalogMapper;
import com.aicabinet.trade.support.ApiMessages;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.http.HttpStatus;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class DeviceAssetService {
    private static final String NEAR_EXPIRY = "NEAR_EXPIRY";
    private static final String RETURNING = "RETURNING";
    private static final String STOCKOUT = "STOCKOUT";
    private static final String DEPLOYED = "DEPLOYED";
    private static final String INBOUND = "INBOUND";
    private static final String RETIRED = "RETIRED";


    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Set<String> LIFECYCLE = Set.of(INBOUND, "IDLE", DEPLOYED, RETURNING, RETIRED);
    private static final Set<String> COOP = Set.of("SELF", "FRANCHISE", "CONSIGN");

    private final DeviceInfoMapper deviceInfoMapper;
    private final DeviceLifecycleEventMapper lifecycleEventMapper;
    private final DeviceSkuInventoryMapper inventoryMapper;
    private final DeviceSkuLotMapper lotMapper;
    private final SkuCatalogMapper skuCatalogMapper;
    private final MerchantMapper merchantMapper;
    private final MerchantScopeService merchantScopeService;
    private final PermissionService permissionService;
    private final AdminAuditService auditService;
    private final DistributedLockService distributedLockService;
        /** 经 Spring 代理调用本类 @Transactional 方法，避免自调用失效。 */
    private final DeviceAssetService self;

    public DeviceAssetService(DeviceInfoMapper deviceInfoMapper,
                              DeviceLifecycleEventMapper lifecycleEventMapper,
                              DeviceSkuInventoryMapper inventoryMapper,
                              DeviceSkuLotMapper lotMapper,
                              SkuCatalogMapper skuCatalogMapper,
                              MerchantMapper merchantMapper,
                              MerchantScopeService merchantScopeService,
                              PermissionService permissionService,
                              AdminAuditService auditService,
                              DistributedLockService distributedLockService, @Lazy DeviceAssetService self) {
        this.deviceInfoMapper = deviceInfoMapper;
        this.lifecycleEventMapper = lifecycleEventMapper;
        this.inventoryMapper = inventoryMapper;
        this.lotMapper = lotMapper;
        this.skuCatalogMapper = skuCatalogMapper;
        this.merchantMapper = merchantMapper;
        this.merchantScopeService = merchantScopeService;
        this.permissionService = permissionService;
        this.auditService = auditService;
        this.distributedLockService = distributedLockService;
        this.self = self;
    }

    @Transactional
    public DeviceInfo applyLifecycle(Long operatorId, String deviceId, DeviceLifecycleRequest request) {
        return runWithDeviceAssetLock(deviceId, () -> doApplyLifecycle(operatorId, deviceId, request));
    }

    private DeviceInfo doApplyLifecycle(Long operatorId, String deviceId, DeviceLifecycleRequest request) {
        permissionService.requirePermission(operatorId, "ops:device:edit");
        merchantScopeService.requireDeviceAccess(operatorId, deviceId);
        if (request == null || request.action() == null || request.action().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少 action");
        }
        DeviceInfo device = deviceInfoMapper.findByIdForUpdate(deviceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.DEVICE_NOT_FOUND));
        String action = request.action().trim().toUpperCase(Locale.ROOT);
        String from = normalizeLifecycle(device.getLifecycleStatus());
        String remark = blankToNull(request.remark());
        LifecycleTransition transition = resolveLifecycleTransition(operatorId, device, action, from, remark, request);
        if (transition.noop()) {
            return device;
        }
        device.setLifecycleStatus(transition.toStatus());
        if (remark != null) {
            device.setLifecycleRemark(remark);
        }
        device.setUpdatedAt(Instant.now());
        deviceInfoMapper.save(device);
        recordEvent(deviceId, from, transition.toStatus(), action, operatorId, remark);
        auditService.record(operatorId, "DEVICE_LIFECYCLE", "DEVICE", deviceId,
                action + ":" + from + "->" + transition.toStatus());
        return device;
    }

    private LifecycleTransition resolveLifecycleTransition(Long operatorId, DeviceInfo device, String action,
                                                           String from, String remark,
                                                           DeviceLifecycleRequest request) {
        return switch (action) {
            case INBOUND -> {
                requireNotRetired(from);
                yield new LifecycleTransition(INBOUND, false);
            }
            case "UNDEPLOY", "IDLE" -> {
                requireNotRetired(from);
                yield new LifecycleTransition("IDLE", false);
            }
            case "DEPLOY" -> applyDeployTransition(device, from);
            case "RETURN" -> {
                requireNotRetired(from);
                yield new LifecycleTransition(RETURNING, false);
            }
            case "RETIRE" -> applyRetireTransition(from, remark);
            case "BIND" -> applyBindTransition(operatorId, device, from, request);
            case "UNBIND" -> {
                requireNotRetired(from);
                device.setMerchantId(null);
                yield new LifecycleTransition("IDLE", false);
            }
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "action 支持 BIND/UNBIND/DEPLOY/UNDEPLOY/RETURN/RETIRE/INBOUND");
        };
    }

    private LifecycleTransition applyDeployTransition(DeviceInfo device, String from) {
        requireNotRetired(from);
        if (RETURNING.equals(from) || RETIRED.equals(from)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "返厂/退役柜不可直接投放");
        }
        device.setDeployedAt(Instant.now());
        return new LifecycleTransition(DEPLOYED, false);
    }

    private static LifecycleTransition applyRetireTransition(String from, String remark) {
        if (RETIRED.equals(from)) {
            return new LifecycleTransition(RETIRED, true);
        }
        if (remark == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "退役需填写备注");
        }
        return new LifecycleTransition(RETIRED, false);
    }

    private LifecycleTransition applyBindTransition(Long operatorId, DeviceInfo device, String from,
                                                    DeviceLifecycleRequest request) {
        requireNotRetired(from);
        String merchantId = blankToNull(request.merchantId());
        if (merchantId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "绑定需指定商户");
        }
        if (!merchantMapper.existsById(merchantId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ApiMessages.INVALID_REQUEST);
        }
        merchantScopeService.requireMerchantAccess(operatorId, merchantId);
        device.setMerchantId(merchantId);
        if (INBOUND.equals(from) || "IDLE".equals(from)) {
            device.setDeployedAt(Instant.now());
            return new LifecycleTransition(DEPLOYED, false);
        }
        return new LifecycleTransition(from, false);
    }

    private record LifecycleTransition(String toStatus, boolean noop) {}

    @Transactional(readOnly = true)
    public List<DeviceLifecycleEventDto> listLifecycleEvents(Long operatorId, String deviceId, int limit) {
        permissionService.requirePermission(operatorId, "ops:device:list");
        merchantScopeService.requireDeviceAccess(operatorId, deviceId);
        int lim = Math.min(Math.max(limit, 1), 100);
        return lifecycleEventMapper.selectList(Wrappers.<DeviceLifecycleEvent>lambdaQuery()
                        .eq(DeviceLifecycleEvent::getDeviceId, deviceId)
                        .orderByDesc(DeviceLifecycleEvent::getCreatedAt)
                        .last("LIMIT " + lim))
                .stream()
                .map(e -> new DeviceLifecycleEventDto(
                        e.getEventId(), e.getDeviceId(), e.getFromStatus(), e.getToStatus(),
                        e.getAction(), e.getOperatorId(), e.getRemark(), e.getCreatedAt()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<StockHealthRowDto> stockHealth(Long operatorId, String dimension,
                                               String merchantId, String routeCode, String lifecycleStatus) {
        return self.stockHealth(operatorId, dimension, merchantId, routeCode, lifecycleStatus, null);
    }

    @Transactional(readOnly = true)
    public List<StockHealthRowDto> stockHealth(Long operatorId, String dimension,
                                               String merchantId, String routeCode, String lifecycleStatus,
                                               String deviceId) {
        permissionService.requireAnyPermission(operatorId, "ops:stock-health:list", "ops:device:list", "ops:replenishment:list");
        Set<String> allowed = merchantScopeService.allowedDeviceIds(operatorId);
        String dim = dimension == null || dimension.isBlank() ? "ALL" : dimension.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("ALL", STOCKOUT, "LOW", NEAR_EXPIRY).contains(dim)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "dimension 仅支持 ALL/STOCKOUT/LOW/NEAR_EXPIRY");
        }
        String deviceFilter = deviceId == null ? "" : deviceId.trim();

        Map<String, DeviceInfo> devices = deviceInfoMapper.findAll().stream()
                .filter(d -> allowed == null || allowed.contains(d.getDeviceId()))
                .filter(d -> deviceFilter.isEmpty() || deviceFilter.equalsIgnoreCase(d.getDeviceId()))
                .filter(d -> merchantId == null || merchantId.isBlank()
                        || merchantId.equalsIgnoreCase(String.valueOf(d.getMerchantId())))
                .filter(d -> routeCode == null || routeCode.isBlank()
                        || routeCode.equalsIgnoreCase(String.valueOf(d.getRouteCode())))
                .filter(d -> lifecycleStatus == null || lifecycleStatus.isBlank()
                        || lifecycleStatus.equalsIgnoreCase(normalizeLifecycle(d.getLifecycleStatus())))
                .collect(Collectors.toMap(DeviceInfo::getDeviceId, d -> d, (a, b) -> a));

        if (devices.isEmpty()) {
            return List.of();
        }

        Map<String, SkuCatalog> skus = skuCatalogMapper.findAll().stream()
                .collect(Collectors.toMap(SkuCatalog::getSkuId, s -> s, (a, b) -> a));

        List<StockHealthRowDto> rows = new ArrayList<>();
        Map<String, Boolean> ledgerByDevice = new HashMap<>();
        Map<String, Map<String, Integer>> sellableByDevice = new HashMap<>();
        if (!NEAR_EXPIRY.equals(dim)) {
            List<DeviceSkuInventory> inv = inventoryMapper.selectList(Wrappers.<DeviceSkuInventory>lambdaQuery()
                    .in(DeviceSkuInventory::getDeviceId, devices.keySet()));
            for (DeviceSkuInventory row : inv) {
                DeviceInfo d = devices.get(row.getDeviceId());
                if (d == null) continue;
                int qty = effectiveSellableQty(row, ledgerByDevice, sellableByDevice);
                boolean stockout = qty <= 0;
                boolean low = !stockout && qty <= Math.max(row.getLowThreshold(), 0);
                if (STOCKOUT.equals(dim) && !stockout) continue;
                if ("LOW".equals(dim) && !low) continue;
                if ("ALL".equals(dim) && !stockout && !low) continue;
                String kind = stockout ? STOCKOUT : "LOW";
                int capacity = Math.max(row.getCapacity(), 0);
                double rate = capacity <= 0 ? (stockout ? 100d : 0d)
                        : Math.max(0d, (1d - (qty * 1d / capacity)) * 100d);
                Integer daysOut = stockout ? estimateDaysOut(row.getDeviceId(), row.getSkuId(), row.getUpdatedAt()) : null;
                SkuCatalog sku = skus.get(row.getSkuId());
                rows.add(new StockHealthRowDto(
                        kind,
                        d.getDeviceId(),
                        d.getDeviceName(),
                        d.getMerchantId(),
                        d.getRouteCode(),
                        normalizeLifecycle(d.getLifecycleStatus()),
                        row.getSkuId(),
                        sku == null ? row.getSkuId() : sku.getSkuName(),
                        qty,
                        capacity,
                        row.getLowThreshold(),
                        Math.round(rate * 10d) / 10d,
                        daysOut,
                        null,
                        row.getUpdatedAt(),
                        null,
                        null
                ));
            }
        }
        Map<String, Integer> capacityByDeviceSku = new HashMap<>();
        if (NEAR_EXPIRY.equals(dim) || "ALL".equals(dim)) {
            List<DeviceSkuInventory> caps = inventoryMapper.selectList(Wrappers.<DeviceSkuInventory>lambdaQuery()
                    .in(DeviceSkuInventory::getDeviceId, devices.keySet()));
            for (DeviceSkuInventory inv : caps) {
                capacityByDeviceSku.put(inv.getDeviceId() + "\0" + inv.getSkuId(), Math.max(inv.getCapacity(), 0));
            }
        }
        if (NEAR_EXPIRY.equals(dim) || "ALL".equals(dim)) {
            LocalDate today = LocalDate.now(ZONE);
            List<DeviceSkuLot> lots = lotMapper.selectList(Wrappers.<DeviceSkuLot>lambdaQuery()
                    .in(DeviceSkuLot::getDeviceId, devices.keySet())
                    .gt(DeviceSkuLot::getQuantity, 0)
                    .isNotNull(DeviceSkuLot::getExpiryDate));
            for (DeviceSkuLot lot : lots) {
                DeviceInfo d = devices.get(lot.getDeviceId());
                if (d == null || lot.getExpiryDate() == null) continue;
                SkuCatalog sku = skus.get(lot.getSkuId());
                int nearDays = sku != null ? Math.max(sku.getNearExpiryDays(), 0) : 7;
                long daysLeft = ChronoUnit.DAYS.between(today, lot.getExpiryDate());
                if (daysLeft > nearDays) continue;
                int capacity = capacityByDeviceSku.getOrDefault(lot.getDeviceId() + "\0" + lot.getSkuId(), 0);
                rows.add(new StockHealthRowDto(
                        NEAR_EXPIRY,
                        d.getDeviceId(),
                        d.getDeviceName(),
                        d.getMerchantId(),
                        d.getRouteCode(),
                        normalizeLifecycle(d.getLifecycleStatus()),
                        lot.getSkuId(),
                        sku == null ? lot.getSkuId() : sku.getSkuName(),
                        lot.getQuantity(),
                        capacity,
                        null,
                        0d,
                        null,
                        lot.getExpiryDate(),
                        lot.getUpdatedAt(),
                        lot.getLotId(),
                        lot.getBatchNo()
                ));
            }
        }
        rows.sort(Comparator
                .comparing(StockHealthRowDto::dimension)
                .thenComparing(StockHealthRowDto::deviceId)
                .thenComparing(StockHealthRowDto::skuId));
        return rows;
    }

    /**
     * 库存健康分页：先按筛选算全量（含 KPI / 一键补货柜机），再切片返回当前页。
     */
    @Transactional(readOnly = true)
    public StockHealthPageDto stockHealthPage(Long operatorId, String dimension,
                                              String merchantId, String routeCode, String lifecycleStatus,
                                              String deviceId, int page, int size) {
        List<StockHealthRowDto> all = self.stockHealth(
                operatorId, dimension, merchantId, routeCode, lifecycleStatus, deviceId);
        int p = Math.max(page, 0);
        int s = Math.min(Math.max(size, 1), 100);
        long stockoutCount = all.stream().filter(r -> STOCKOUT.equals(r.dimension())).count();
        long lowCount = all.stream().filter(r -> "LOW".equals(r.dimension())).count();
        long nearExpiryCount = all.stream().filter(r -> NEAR_EXPIRY.equals(r.dimension())).count();
        long deviceCount = all.stream().map(StockHealthRowDto::deviceId).filter(id -> id != null && !id.isBlank()).distinct().count();
        List<String> planDeviceIds = all.stream()
                .filter(r -> STOCKOUT.equals(r.dimension()) || "LOW".equals(r.dimension()))
                .map(StockHealthRowDto::deviceId)
                .filter(id -> id != null && !id.isBlank())
                .collect(Collectors.collectingAndThen(Collectors.toCollection(LinkedHashSet::new), ArrayList::new));
        int from = Math.min(p * s, all.size());
        int to = Math.min(from + s, all.size());
        return new StockHealthPageDto(all.subList(from, to), p, s, all.size(),
                stockoutCount, lowCount, nearExpiryCount, deviceCount, planDeviceIds);
    }

    public static String normalizeLifecycle(String status) {
        if (status == null || status.isBlank()) {
            return DEPLOYED;
        }
        String s = status.trim().toUpperCase(Locale.ROOT);
        return LIFECYCLE.contains(s) ? s : DEPLOYED;
    }

    public static String normalizeCoop(String mode) {
        if (mode == null || mode.isBlank()) {
            return null;
        }
        String s = mode.trim().toUpperCase(Locale.ROOT);
        return COOP.contains(s) ? s : null;
    }

    private int effectiveSellableQty(DeviceSkuInventory row,
                                     Map<String, Boolean> ledgerByDevice,
                                     Map<String, Map<String, Integer>> sellableByDevice) {
        String deviceId = row.getDeviceId();
        boolean ledger = ledgerByDevice.computeIfAbsent(deviceId,
                d -> !lotMapper.findByDeviceId(d).isEmpty());
        if (!ledger) {
            return row.getQuantity();
        }
        Map<String, Integer> bySku = sellableByDevice.computeIfAbsent(deviceId, d -> {
            Map<String, Integer> map = new HashMap<>();
            for (Object[] r : lotMapper.sumSellableBySku(d)) {
                if (r == null || r.length < 2 || r[0] == null || r[1] == null) {
                    continue;
                }
                map.merge(String.valueOf(r[0]), ((Number) r[1]).intValue(), Integer::sum);
            }
            return map;
        });
        return bySku.getOrDefault(row.getSkuId(), 0);
    }

    private Integer estimateDaysOut(String deviceId, String skuId, Instant updatedAt) {
        if (updatedAt == null) return null;
        long days = ChronoUnit.DAYS.between(updatedAt.atZone(ZONE).toLocalDate(), LocalDate.now(ZONE));
        return (int) Math.max(days, 0);
    }

    private void requireNotRetired(String from) {
        if (RETIRED.equals(from)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "已退役设备不可操作");
        }
    }

    private void recordEvent(String deviceId, String from, String to, String action, Long operatorId, String remark) {
        DeviceLifecycleEvent e = new DeviceLifecycleEvent();
        e.setDeviceId(deviceId);
        e.setFromStatus(from);
        e.setToStatus(to);
        e.setAction(action);
        e.setOperatorId(operatorId);
        e.setRemark(remark);
        e.setCreatedAt(Instant.now());
        lifecycleEventMapper.insert(e);
    }

    private static String blankToNull(String v) {
        if (v == null) return null;
        String t = v.trim();
        return t.isEmpty() ? null : t;
    }

    static String deviceAssetLockKey(String deviceId) {
        return "device:asset:" + deviceId;
    }

    private <T> T runWithDeviceAssetLock(String deviceId, java.util.function.Supplier<T> action) {
        if (!distributedLockService.tryLock(deviceAssetLockKey(deviceId), 60, 5)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "设备资产处理中，请稍后重试");
        }
        try {
            return action.get();
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage(), e);
        } finally {
            distributedLockService.unlock(deviceAssetLockKey(deviceId));
        }
    }
}
