package com.aicabinet.trade.service;

import com.aicabinet.common.dto.GravityDeltaRequest;
import com.aicabinet.trade.client.VisionServiceClient;
import com.aicabinet.trade.domain.DeviceSkuInventory;
import com.aicabinet.trade.domain.DeviceSkuInventoryId;
import com.aicabinet.trade.mapper.DeviceSkuInventoryMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
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
    private static final String ORDER = "ORDER";


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
    /** 经 Spring 代理调用本类 @Transactional 方法，避免自调用失效。 */
    private final InventoryService self;

    public InventoryService(DeviceSkuInventoryMapper inventoryRepository,
                            InventoryLotService inventoryLotService,
                            DeviceSlotService deviceSlotService,
                            GravitySettlementHelper gravityHelper,
                            DeviceValidationService deviceValidationService,
                            DistributedLockService lockService, @Lazy InventoryService self) {
        this.inventoryRepository = inventoryRepository;
        this.inventoryLotService = inventoryLotService;
        this.deviceSlotService = deviceSlotService;
        this.gravityHelper = gravityHelper;
        this.deviceValidationService = deviceValidationService;
        this.lockService = lockService;
        this.self = self;
    }

    /** 扣减库存，有批次时 FEFO；返回 skuId -> 主批次号。 */
    @Transactional
    public Map<String, String> deductForOrder(String deviceId, List<VisionServiceClient.RecognizedItem> items) {
        return self.deductForOrder(deviceId, items, null, null);
    }

    @Transactional
    public Map<String, String> deductForOrder(String deviceId, List<VisionServiceClient.RecognizedItem> items,
                                              String refId) {
        return self.deductForOrder(deviceId, items, refId, null);
    }

    @Transactional
    public Map<String, String> deductForOrder(String deviceId, List<VisionServiceClient.RecognizedItem> items,
                                              String refId, String gravityDeltasJson) {
        return runWithDeviceLock(deviceId, () -> doDeductForOrder(deviceId, items, refId, gravityDeltasJson));
    }

    private Map<String, String> runWithDeviceLock(String deviceId, java.util.function.Supplier<Map<String, String>> action) {
        String lockKey = deviceLockKey(deviceId);
        if (!lockService.tryLock(lockKey, INV_LOCK_LEASE_SECONDS, INV_LOCK_WAIT_SECONDS)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "库存繁忙，请稍后重试");
        }
        try {
            return action.get();
        } finally {
            lockService.unlock(lockKey);
        }
    }

    static String deviceLockKey(String deviceId) {
        return "inv:" + deviceId;
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
        boolean lotLedger = inventoryLotService.deviceUsesLotLedger(deviceId);
        for (VisionServiceClient.RecognizedItem item : items) {
            if (item.quantity() <= 0) {
                continue;
            }
            skuQtySold.merge(item.skuId(), item.quantity(), Integer::sum);
            // 有批次账本时一律 FEFO：可售为 0 时禁止靠汇总表虚扣（会与消费者/货道账面不一致）
            if (lotLedger || inventoryLotService.hasSellableLots(deviceId, item.skuId())) {
                InventoryLotService.FefoDeductResult result = inventoryLotService.deductFefo(
                        deviceId, item.skuId(), item.quantity(), ORDER, refId);
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
                    deviceId, delta.skuId(), qty, ORDER, refId, slot);
            batchBySku.putIfAbsent(delta.skuId(), result.primaryBatch());
            slotQtySold.merge(slot, qty, Integer::sum);
        }
        deviceSlotService.applyPhysicalAfterSale(deviceId, slotQtySold, refId);
        return batchBySku;
    }

    @Transactional
    public void restoreForOrder(String deviceId, List<VisionServiceClient.RecognizedItem> items,
                                Map<String, String> batchBySku) {
        runWithDeviceLock(deviceId, () -> {
            doRestoreForOrder(deviceId, items, batchBySku);
            return Map.<String, String>of();
        });
    }

    private void doRestoreForOrder(String deviceId, List<VisionServiceClient.RecognizedItem> items,
                                   Map<String, String> batchBySku) {
        if (items == null || items.isEmpty()) {
            return;
        }
        Map<String, Integer> slotQtyRestored = new HashMap<>();
        Map<String, Integer> skuQtyRestored = new HashMap<>();
        boolean lotLedger = inventoryLotService.deviceUsesLotLedger(deviceId);
        for (VisionServiceClient.RecognizedItem item : items) {
            if (item.quantity() <= 0) {
                continue;
            }
            skuQtyRestored.merge(item.skuId(), item.quantity(), Integer::sum);
            String slotId = restoreSingleItem(deviceId, item, batchBySku, lotLedger);
            if (slotId != null && !slotId.isBlank()) {
                slotQtyRestored.merge(slotId.trim().toUpperCase(), item.quantity(), Integer::sum);
            }
        }
        applyPhysicalRestore(deviceId, slotQtyRestored, skuQtyRestored);
    }

    private String restoreSingleItem(String deviceId, VisionServiceClient.RecognizedItem item,
                                     Map<String, String> batchBySku, boolean lotLedger) {
        String batch = batchBySku != null ? batchBySku.get(item.skuId()) : null;
        if (batch != null && !batch.isBlank()) {
            return inventoryLotService.restoreToBatch(
                    deviceId, item.skuId(), batch, item.quantity(), ORDER, null);
        }
        if (lotLedger || inventoryLotService.hasSellableLots(deviceId, item.skuId())) {
            return inventoryLotService.restoreToBatch(deviceId, item.skuId(),
                    "ADJ-" + item.skuId(), item.quantity(), ORDER, null);
        }
        applyDelta(deviceId, item.skuId(), item.quantity());
        return null;
    }

    private void applyPhysicalRestore(String deviceId, Map<String, Integer> slotQtyRestored,
                                      Map<String, Integer> skuQtyRestored) {
        if (!slotQtyRestored.isEmpty()) {
            deviceSlotService.applyPhysicalAfterRestore(deviceId, slotQtyRestored, "REFUND");
        } else {
            deviceSlotService.applyPhysicalAfterSkuRestore(deviceId, skuQtyRestored, "REFUND");
        }
    }

    /**
     * 仅退款不回库：销售时已 FEFO 扣减，此处只写 {@code REFUND_KEPT} 审计流水（delta=0），
     * 禁止再走报损扣库，避免账实与财务双重计损。
     */
    @Transactional
    public void recordRefundKeptGoods(String deviceId, List<VisionServiceClient.RecognizedItem> items,
                                      Map<String, String> batchBySku, String orderId) {
        runWithDeviceLock(deviceId, () -> {
            doRecordRefundKeptGoods(deviceId, items, batchBySku, orderId);
            return Map.<String, String>of();
        });
    }

    private void doRecordRefundKeptGoods(String deviceId, List<VisionServiceClient.RecognizedItem> items,
                                         Map<String, String> batchBySku, String orderId) {
        if (items == null || items.isEmpty() || orderId == null || orderId.isBlank()) {
            return;
        }
        for (VisionServiceClient.RecognizedItem item : items) {
            if (item == null || item.quantity() <= 0 || item.skuId() == null || item.skuId().isBlank()) {
                continue;
            }
            String batch = batchBySku != null ? batchBySku.get(item.skuId()) : null;
            inventoryLotService.recordRefundKeptNote(
                    deviceId, item.skuId(), batch, item.quantity(), orderId);
        }
        log.info("refund kept goods noted device={} order={} skus={}",
                deviceId, orderId, items.size());
    }

    @Transactional
    public Map<String, String> adjustForOrder(String deviceId,
                               List<VisionServiceClient.RecognizedItem> oldItems,
                               List<VisionServiceClient.RecognizedItem> newItems) {
        return self.adjustForOrder(deviceId, oldItems, newItems, Map.of());
    }

    /**
     * 改单库存差量：多扣则继续 FEFO 扣减；少扣则按原批次（batchBySku）优先回库，与货道实测同步。
     * 返回应写回订单行的 sku→batch（保留原批次；新扣减 SKU 用 FEFO 主批次补齐）。
     * <p>持有 {@link #deviceLockKey} 后会再进入 {@link InventoryLotService#deductFefo} 同 key 加锁；
     * 依赖 Redisson {@code RLock} 同线程可重入，勿换成不可重入实现。
     */
    @Transactional
    public Map<String, String> adjustForOrder(String deviceId,
                               List<VisionServiceClient.RecognizedItem> oldItems,
                               List<VisionServiceClient.RecognizedItem> newItems,
                               Map<String, String> batchBySku) {
        return runWithDeviceLock(deviceId, () -> doAdjustForOrder(deviceId, oldItems, newItems, batchBySku));
    }

    private Map<String, String> doAdjustForOrder(String deviceId,
                                                 List<VisionServiceClient.RecognizedItem> oldItems,
                                                 List<VisionServiceClient.RecognizedItem> newItems,
                                                 Map<String, String> batchBySku) {
        Map<String, String> resultBatches = new HashMap<>();
        if (batchBySku != null) {
            batchBySku.forEach((sku, batch) -> {
                if (sku != null && batch != null && !batch.isBlank()) {
                    resultBatches.put(sku, batch);
                }
            });
        }
        Map<String, Integer> oldQty = toQtyMap(oldItems);
        Map<String, Integer> newQty = toQtyMap(newItems);
        for (String skuId : unionKeys(oldQty, newQty)) {
            applySkuInventoryDelta(deviceId, skuId, newQty, oldQty, batchBySku, resultBatches);
        }
        return resultBatches;
    }

    private void applySkuInventoryDelta(String deviceId, String skuId,
                                        Map<String, Integer> newQty, Map<String, Integer> oldQty,
                                        Map<String, String> batchBySku,
                                        Map<String, String> resultBatches) {
        int delta = newQty.getOrDefault(skuId, 0) - oldQty.getOrDefault(skuId, 0);
        if (delta == 0) {
            return;
        }
        if (delta > 0) {
            Map<String, String> deducted = doDeductForOrder(
                    deviceId, List.of(new VisionServiceClient.RecognizedItem(skuId, delta, 1f)), null, null);
            deducted.forEach(resultBatches::putIfAbsent);
            return;
        }
        Map<String, String> restoreBatch = Map.of();
        if (batchBySku != null) {
            String batch = batchBySku.get(skuId);
            if (batch != null && !batch.isBlank()) {
                restoreBatch = Map.of(skuId, batch);
            }
        }
        doRestoreForOrder(deviceId,
                List.of(new VisionServiceClient.RecognizedItem(skuId, -delta, 1f)), restoreBatch);
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
