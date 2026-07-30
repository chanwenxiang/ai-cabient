package com.aicabinet.trade.service;

import com.aicabinet.common.dto.GravityDeltaRequest;
import com.aicabinet.trade.client.VisionServiceClient;
import com.aicabinet.trade.domain.DeviceSkuInventory;
import com.aicabinet.trade.domain.DeviceSkuInventoryId;
import com.aicabinet.trade.mapper.DeviceSkuInventoryMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class InventoryService {

    private static final Logger log = LoggerFactory.getLogger(InventoryService.class);
    /** 单柜库存扣减锁租约（秒） */
    private static final long INV_LOCK_LEASE_SECONDS = 30;
    private static final long INV_LOCK_WAIT_SECONDS = 5;

    private final DeviceSkuInventoryMapper inventoryRepository;
    private final InventoryLotService inventoryLotService;
    private final DeviceSlotService deviceSlotService;
    private final GravitySettlementHelper gravityHelper;
    private final DeviceValidationService deviceValidationService;
    private final DistributedLockService lockService;

    public InventoryService(DeviceSkuInventoryMapper inventoryRepository,
                            InventoryLotService inventoryLotService,
                            DeviceSlotService deviceSlotService,
                            GravitySettlementHelper gravityHelper,
                            DeviceValidationService deviceValidationService,
                            DistributedLockService lockService) {
        this.inventoryRepository = inventoryRepository;
        this.inventoryLotService = inventoryLotService;
        this.deviceSlotService = deviceSlotService;
        this.gravityHelper = gravityHelper;
        this.deviceValidationService = deviceValidationService;
        this.lockService = lockService;
    }

    /** 扣减库存，有批次时 FEFO；返回 skuId -> 主批次号。 */
    @Transactional
    public Map<String, String> deductForOrder(String deviceId, List<VisionServiceClient.RecognizedItem> items) {
        return deductForOrder(deviceId, items, null, null);
    }

    @Transactional
    public Map<String, String> deductForOrder(String deviceId, List<VisionServiceClient.RecognizedItem> items,
                                              String refId) {
        return deductForOrder(deviceId, items, refId, null);
    }

    @Transactional
    public Map<String, String> deductForOrder(String deviceId, List<VisionServiceClient.RecognizedItem> items,
                                              String refId, String gravityDeltasJson) {
        String lockKey = "inv:" + deviceId;
        if (!lockService.tryLock(lockKey, INV_LOCK_LEASE_SECONDS, INV_LOCK_WAIT_SECONDS)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "库存繁忙，请稍后重试");
        }
        try {
            return doDeductForOrder(deviceId, items, refId, gravityDeltasJson);
        } finally {
            lockService.unlock(lockKey);
        }
    }

    private Map<String, String> doDeductForOrder(String deviceId, List<VisionServiceClient.RecognizedItem> items,
                                                 String refId, String gravityDeltasJson) {
        deviceValidationService.ensureSettlementAllowed(deviceId);
        List<GravityDeltaRequest.GravityDeltaItem> gravityDeltas = gravityHelper.parse(gravityDeltasJson);
        if ((items == null || items.isEmpty())
                && gravityHelper.hasSlotSpecificDeltas(gravityDeltas)) {
            return deductFromSlotGravity(deviceId, gravityDeltas, refId);
        }
        Map<String, String> batchBySku = new HashMap<>();
        Map<String, Integer> slotQtySold = new HashMap<>();
        Map<String, Integer> skuQtySold = new HashMap<>();
        if (items == null || items.isEmpty()) {
            return batchBySku;
        }
        for (VisionServiceClient.RecognizedItem item : items) {
            if (item.quantity() <= 0) {
                continue;
            }
            skuQtySold.merge(item.skuId(), item.quantity(), Integer::sum);
            if (inventoryLotService.hasSellableLots(deviceId, item.skuId())) {
                InventoryLotService.FefoDeductResult result = inventoryLotService.deductFefo(
                        deviceId, item.skuId(), item.quantity(), "ORDER", refId);
                batchBySku.put(item.skuId(), result.primaryBatch());
                result.slotQtyDeducted().forEach((slot, qty) -> slotQtySold.merge(slot, qty, Integer::sum));
            } else {
                applyDelta(deviceId, item.skuId(), -item.quantity());
            }
        }
        if (!slotQtySold.isEmpty()) {
            deviceSlotService.applyPhysicalAfterSale(deviceId, slotQtySold, refId);
        } else {
            deviceSlotService.applyPhysicalAfterSkuSale(deviceId, skuQtySold, refId);
        }
        return batchBySku;
    }

    private Map<String, String> deductFromSlotGravity(String deviceId,
                                                      List<GravityDeltaRequest.GravityDeltaItem> deltas,
                                                      String refId) {
        Map<String, String> batchBySku = new HashMap<>();
        Map<String, Integer> slotQtySold = new HashMap<>();
        for (GravityDeltaRequest.GravityDeltaItem delta : deltas) {
            if (delta.delta() >= 0 || delta.slotId() == null || delta.slotId().isBlank()) {
                continue;
            }
            int qty = -delta.delta();
            String slot = delta.slotId().trim().toUpperCase();
            InventoryLotService.FefoDeductResult result = inventoryLotService.deductFefo(
                    deviceId, delta.skuId(), qty, "ORDER", refId, slot);
            batchBySku.putIfAbsent(delta.skuId(), result.primaryBatch());
            slotQtySold.merge(slot, qty, Integer::sum);
        }
        deviceSlotService.applyPhysicalAfterSale(deviceId, slotQtySold, refId);
        return batchBySku;
    }

    @Transactional
    public void restoreForOrder(String deviceId, List<VisionServiceClient.RecognizedItem> items,
                                Map<String, String> batchBySku) {
        if (items == null || items.isEmpty()) {
            return;
        }
        Map<String, Integer> slotQtyRestored = new HashMap<>();
        Map<String, Integer> skuQtyRestored = new HashMap<>();
        for (VisionServiceClient.RecognizedItem item : items) {
            if (item.quantity() <= 0) {
                continue;
            }
            skuQtyRestored.merge(item.skuId(), item.quantity(), Integer::sum);
            String batch = batchBySku != null ? batchBySku.get(item.skuId()) : null;
            String slotId;
            if (batch != null && !batch.isBlank()) {
                slotId = inventoryLotService.restoreToBatch(
                        deviceId, item.skuId(), batch, item.quantity(), "ORDER", null);
            } else if (inventoryLotService.hasSellableLots(deviceId, item.skuId())) {
                slotId = inventoryLotService.restoreToBatch(deviceId, item.skuId(),
                        "ADJ-" + item.skuId(), item.quantity(), "ORDER", null);
            } else {
                applyDelta(deviceId, item.skuId(), item.quantity());
                slotId = null;
            }
            if (slotId != null && !slotId.isBlank()) {
                slotQtyRestored.merge(slotId.trim().toUpperCase(), item.quantity(), Integer::sum);
            }
        }
        if (!slotQtyRestored.isEmpty()) {
            deviceSlotService.applyPhysicalAfterRestore(deviceId, slotQtyRestored, "REFUND");
        } else {
            deviceSlotService.applyPhysicalAfterSkuRestore(deviceId, skuQtyRestored, "REFUND");
        }
    }

    @Transactional
    public void adjustForOrder(String deviceId,
                               List<VisionServiceClient.RecognizedItem> oldItems,
                               List<VisionServiceClient.RecognizedItem> newItems) {
        adjustForOrder(deviceId, oldItems, newItems, Map.of());
    }

    /**
     * 改单库存差量：多扣则继续 FEFO 扣减；少扣则按原批次（batchBySku）优先回库，与货道实测同步。
     */
    @Transactional
    public void adjustForOrder(String deviceId,
                               List<VisionServiceClient.RecognizedItem> oldItems,
                               List<VisionServiceClient.RecognizedItem> newItems,
                               Map<String, String> batchBySku) {
        Map<String, Integer> oldQty = toQtyMap(oldItems);
        Map<String, Integer> newQty = toQtyMap(newItems);
        for (String skuId : unionKeys(oldQty, newQty)) {
            int delta = newQty.getOrDefault(skuId, 0) - oldQty.getOrDefault(skuId, 0);
            if (delta == 0) {
                continue;
            }
            if (delta > 0) {
                deductForOrder(deviceId, List.of(new VisionServiceClient.RecognizedItem(skuId, delta, 1f)));
            } else {
                Map<String, String> restoreBatch = Map.of();
                if (batchBySku != null) {
                    String batch = batchBySku.get(skuId);
                    if (batch != null && !batch.isBlank()) {
                        restoreBatch = Map.of(skuId, batch);
                    }
                }
                restoreForOrder(deviceId,
                        List.of(new VisionServiceClient.RecognizedItem(skuId, -delta, 1f)), restoreBatch);
            }
        }
    }

    private void applyDelta(String deviceId, String skuId, int delta) {
        DeviceSkuInventoryId id = new DeviceSkuInventoryId(deviceId, skuId);
        DeviceSkuInventory inv = inventoryRepository.findById(id).orElseGet(() -> {
            DeviceSkuInventory created = new DeviceSkuInventory();
            created.setId(id);
            created.setQuantity(0);
            created.setCapacity(20);
            created.setLowThreshold(2);
            return created;
        });
        int next = inv.getQuantity() + delta;
        if (next < 0) {
            log.warn("库存不足 device={} sku={} qty={} delta={}", deviceId, skuId, inv.getQuantity(), delta);
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "库存不足 sku=" + skuId + " 当前=" + inv.getQuantity() + " 变更=" + delta);
        }
        inv.setQuantity(next);
        inventoryRepository.save(inv);
        log.info("库存已更新 device={} sku={} qty={}", deviceId, skuId, next);
    }

    private static Map<String, Integer> toQtyMap(List<VisionServiceClient.RecognizedItem> items) {
        if (items == null) {
            return Map.of();
        }
        return items.stream()
                .filter(i -> i.quantity() > 0)
                .collect(Collectors.toMap(
                        VisionServiceClient.RecognizedItem::skuId,
                        VisionServiceClient.RecognizedItem::quantity,
                        Integer::sum));
    }

    private static java.util.Set<String> unionKeys(Map<String, Integer> a, Map<String, Integer> b) {
        java.util.Set<String> keys = new java.util.HashSet<>(a.keySet());
        keys.addAll(b.keySet());
        return keys;
    }
}
