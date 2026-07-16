package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.DeviceSlot;
import com.aicabinet.trade.domain.DeviceSlotId;
import com.aicabinet.trade.domain.DeviceSkuInventory;
import com.aicabinet.trade.domain.DeviceSkuInventoryId;
import com.aicabinet.trade.domain.DeviceSkuLot;
import com.aicabinet.trade.domain.InventoryMovement;
import com.aicabinet.trade.domain.PullOffTask;
import com.aicabinet.trade.domain.ReplenishmentTaskLine;
import com.aicabinet.trade.domain.SkuCatalog;
import com.aicabinet.trade.mapper.DeviceSlotMapper;
import com.aicabinet.trade.mapper.DeviceSkuInventoryMapper;
import com.aicabinet.trade.mapper.DeviceSkuLotMapper;
import com.aicabinet.trade.mapper.InventoryMovementMapper;
import com.aicabinet.trade.mapper.PullOffTaskMapper;
import com.aicabinet.trade.mapper.SkuCatalogMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class InventoryLotService {

    private static final Logger log = LoggerFactory.getLogger(InventoryLotService.class);

    private final DeviceSkuLotMapper lotRepository;
    private final InventoryMovementMapper movementRepository;
    private final DeviceSkuInventoryMapper inventoryRepository;
    private final SkuCatalogMapper skuCatalogRepository;
    private final PullOffTaskMapper pullOffTaskRepository;
    private final DeviceSlotMapper slotRepository;

    public InventoryLotService(DeviceSkuLotMapper lotRepository,
                               InventoryMovementMapper movementRepository,
                               DeviceSkuInventoryMapper inventoryRepository,
                               SkuCatalogMapper skuCatalogRepository,
                               PullOffTaskMapper pullOffTaskRepository,
                               DeviceSlotMapper slotRepository) {
        this.lotRepository = lotRepository;
        this.movementRepository = movementRepository;
        this.inventoryRepository = inventoryRepository;
        this.skuCatalogRepository = skuCatalogRepository;
        this.pullOffTaskRepository = pullOffTaskRepository;
        this.slotRepository = slotRepository;
    }

    public boolean hasSellableLots(String deviceId, String skuId) {
        return lotRepository.sumSellableQuantity(deviceId, skuId) > 0;
    }

    /** FEFO 扣减，返回主批次号与按货道扣减数量。 */
    @Transactional
    public FefoDeductResult deductFefo(String deviceId, String skuId, int quantity, String refType, String refId) {
        return deductFefo(deviceId, skuId, quantity, refType, refId, null);
    }

    @Transactional
    public FefoDeductResult deductFefo(String deviceId, String skuId, int quantity,
                                       String refType, String refId, String slotId) {
        if (quantity <= 0) {
            return new FefoDeductResult(null, Map.of());
        }
        SkuCatalog sku = skuCatalogRepository.findById(skuId).orElse(null);
        int blockDays = sku != null ? sku.getBlockSaleDaysBeforeExpiry() : 0;
        LocalDate today = LocalDate.now();
        LocalDate minExpiry = today.plusDays(blockDays);

        List<DeviceSkuLot> lots = (slotId != null && !slotId.isBlank())
                ? lotRepository.findByDeviceIdAndSkuIdAndSlotIdOrderByExpiryDateAsc(
                        deviceId, skuId, slotId.trim().toUpperCase())
                : lotRepository.findByDeviceIdAndSkuIdOrderByExpiryDateAsc(deviceId, skuId);
        int remaining = quantity;
        String primaryBatch = null;
        Map<String, Integer> slotQty = new LinkedHashMap<>();

        for (DeviceSkuLot lot : lots) {
            if (remaining <= 0) {
                break;
            }
            if (!isSellable(lot, minExpiry)) {
                continue;
            }
            int take = Math.min(lot.getQuantity(), remaining);
            if (take <= 0) {
                continue;
            }
            if (primaryBatch == null) {
                primaryBatch = lot.getBatchNo();
            }
            lot.setQuantity(lot.getQuantity() - take);
            if (lot.getQuantity() == 0) {
                lot.setStatus("DEPLETED");
            }
            lotRepository.save(lot);
            recordMovement(deviceId, skuId, lot.getBatchNo(), "SALE", -take, refType, refId, null);
            if (lot.getSlotId() != null && !lot.getSlotId().isBlank()) {
                String slotCode = lot.getSlotId().trim().toUpperCase();
                slotQty.merge(slotCode, take, Integer::sum);
            }
            remaining -= take;
        }

        if (remaining > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "sellable lot inventory insufficient for sku=" + skuId + " need=" + quantity);
        }
        syncAggregateInventory(deviceId, skuId);
        return new FefoDeductResult(primaryBatch, slotQty);
    }

    public record FefoDeductResult(String primaryBatch, Map<String, Integer> slotQtyDeducted) {}

    private void deductFefoForRef(String deviceId, String skuId, int quantity,
                                  String movementType, String refType, String refId, Long operatorId) {
        SkuCatalog sku = skuCatalogRepository.findById(skuId).orElse(null);
        int blockDays = sku != null ? sku.getBlockSaleDaysBeforeExpiry() : 0;
        LocalDate minExpiry = LocalDate.now().plusDays(blockDays);

        List<DeviceSkuLot> lots = lotRepository.findByDeviceIdAndSkuIdOrderByExpiryDateAsc(deviceId, skuId);
        int remaining = quantity;

        for (DeviceSkuLot lot : lots) {
            if (remaining <= 0) {
                break;
            }
            if (lot.getQuantity() <= 0) {
                continue;
            }
            if ("WRITE_OFF".equals(movementType) || "ADJ".equals(movementType) || "PULL_OFF".equals(movementType)) {
                // allow blocked lots for write-off/pull-off/stocktake shrink
            } else if (!isSellable(lot, minExpiry)) {
                continue;
            }
            int take = Math.min(lot.getQuantity(), remaining);
            lot.setQuantity(lot.getQuantity() - take);
            if (lot.getQuantity() == 0) {
                lot.setStatus("DEPLETED");
            }
            lotRepository.save(lot);
            recordMovement(deviceId, skuId, lot.getBatchNo(), movementType, -take, refType, refId, operatorId);
            remaining -= take;
        }

        if (remaining > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "lot inventory insufficient for sku=" + skuId + " need=" + quantity);
        }
    }

    @Transactional
    public void restoreToBatch(String deviceId, String skuId, String batchNo, int quantity, String refType, String refId) {
        if (quantity <= 0) {
            return;
        }
        DeviceSkuLot lot = lotRepository.findByDeviceIdAndSkuIdAndBatchNo(deviceId, skuId, batchNo)
                .orElseGet(() -> createFallbackLot(deviceId, skuId, batchNo));
        lot.setQuantity(lot.getQuantity() + quantity);
        if ("DEPLETED".equals(lot.getStatus()) || "BLOCKED".equals(lot.getStatus())) {
            lot.setStatus(resolveLotStatus(lot));
        }
        lotRepository.save(lot);
        recordMovement(deviceId, skuId, batchNo, "REFUND", quantity, refType, refId, null);
        syncAggregateInventory(deviceId, skuId);
    }

    @Transactional
    public void addRestock(String deviceId, String skuId, String batchNo,
                           LocalDate productionDate, LocalDate expiryDate,
                           int quantity, String slotId, Long operatorId, String refId) {
        validateRestockExpiry(skuId, expiryDate);
        ensureSlotCapacity(deviceId, slotId, quantity);
        String resolvedBatch = (batchNo == null || batchNo.isBlank())
                ? "B-" + LocalDate.now() + "-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase()
                : batchNo.trim();

        DeviceSkuLot lot = lotRepository.findByDeviceIdAndSkuIdAndBatchNo(deviceId, skuId, resolvedBatch)
                .orElseGet(() -> {
                    DeviceSkuLot created = new DeviceSkuLot();
                    created.setLotId("L" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase());
                    created.setDeviceId(deviceId);
                    created.setSkuId(skuId);
                    created.setBatchNo(resolvedBatch);
                    created.setProductionDate(productionDate);
                    created.setExpiryDate(expiryDate);
                    created.setSlotId(slotId);
                    created.setStatus("ON_SALE");
                    return created;
                });
        lot.setQuantity(lot.getQuantity() + quantity);
        lot.setStatus(resolveLotStatus(lot));
        if (slotId != null && !slotId.isBlank()) {
            lot.setSlotId(slotId);
        }
        lotRepository.save(lot);
        recordMovement(deviceId, skuId, resolvedBatch, "RESTOCK", quantity, "REPLENISH", refId, operatorId);
        syncAggregateInventory(deviceId, skuId);
        log.info("restock lot device={} sku={} batch={} qty={}", deviceId, skuId, resolvedBatch, quantity);
    }

    @Transactional
    public void pullOff(String deviceId, String skuId, String batchNo, int quantity,
                        Long operatorId, String refId) {
        if (quantity <= 0) {
            return;
        }
        if (batchNo != null && !batchNo.isBlank()) {
            DeviceSkuLot lot = lotRepository.findByDeviceIdAndSkuIdAndBatchNo(deviceId, skuId, batchNo)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "lot not found"));
            int take = Math.min(lot.getQuantity(), quantity);
            lot.setQuantity(lot.getQuantity() - take);
            if (lot.getQuantity() == 0) {
                lot.setStatus("DEPLETED");
            }
            lotRepository.save(lot);
            recordMovement(deviceId, skuId, batchNo, "PULL_OFF", -take, "REPLENISH", refId, operatorId);
        } else {
            deductFefoForRef(deviceId, skuId, quantity, "PULL_OFF", "REPLENISH", refId, operatorId);
        }
        syncAggregateInventory(deviceId, skuId);
    }

    @Transactional
    public void writeOffLots(String deviceId, String skuId, String batchNo, int quantity,
                             Long operatorId, String refId) {
        if (quantity <= 0) {
            return;
        }
        if (batchNo != null && !batchNo.isBlank()) {
            DeviceSkuLot lot = lotRepository.findByDeviceIdAndSkuIdAndBatchNo(deviceId, skuId, batchNo)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "lot not found"));
            int take = Math.min(lot.getQuantity(), quantity);
            lot.setQuantity(lot.getQuantity() - take);
            if (lot.getQuantity() == 0) {
                lot.setStatus("DEPLETED");
            }
            lotRepository.save(lot);
            recordMovement(deviceId, skuId, batchNo, "WRITE_OFF", -take, "WRITE_OFF", refId, operatorId);
        } else {
            deductFefoForRef(deviceId, skuId, quantity, "WRITE_OFF", "WRITE_OFF", refId, operatorId);
        }
        syncAggregateInventory(deviceId, skuId);
    }

    @Transactional
    public void stocktakeAdjust(String deviceId, String skuId, int countedQuantity,
                                Long operatorId, String refId) {
        DeviceSkuInventoryId id = new DeviceSkuInventoryId(deviceId, skuId);
        int current = inventoryRepository.findById(id).map(DeviceSkuInventory::getQuantity).orElse(0);
        int delta = countedQuantity - current;
        if (delta == 0) {
            return;
        }
        if (delta > 0) {
            SkuCatalog sku = skuCatalogRepository.findById(skuId).orElse(null);
            int shelfDays = sku != null && sku.getShelfLifeDays() != null ? sku.getShelfLifeDays() : 180;
            LocalDate expiry = LocalDate.now().plusDays(shelfDays);
            addRestock(deviceId, skuId, "STOCKTAKE-" + LocalDate.now(), LocalDate.now(), expiry,
                    delta, null, operatorId, refId);
        } else {
            deductFefoForRef(deviceId, skuId, -delta, "ADJ", "STOCKTAKE", refId, operatorId);
        }
        syncAggregateInventory(deviceId, skuId);
    }

    @Transactional
    public void applyReplenishmentLine(String deviceId, ReplenishmentTaskLine line, Long operatorId, String refId) {
        if ("RESTOCK".equalsIgnoreCase(line.getLineType())) {
            LocalDate expiry = line.getExpiryDate();
            if (expiry == null) {
                SkuCatalog sku = skuCatalogRepository.findById(line.getSkuId()).orElse(null);
                if (sku != null && sku.getShelfLifeDays() != null && line.getProductionDate() != null) {
                    expiry = line.getProductionDate().plusDays(sku.getShelfLifeDays());
                } else {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "expiryDate required for RESTOCK sku=" + line.getSkuId());
                }
            }
            addRestock(deviceId, line.getSkuId(), line.getBatchNo(), line.getProductionDate(), expiry,
                    line.getQuantity(), line.getSlotId(), operatorId, refId);
        } else if ("PULL_OFF".equalsIgnoreCase(line.getLineType())) {
            pullOff(deviceId, line.getSkuId(), line.getBatchNo(), line.getQuantity(), operatorId, refId);
        } else {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unknown lineType=" + line.getLineType());
        }
    }

    @Transactional
    public void syncAggregateInventory(String deviceId, String skuId) {
        int sum = lotRepository.sumSellableQuantity(deviceId, skuId);
        DeviceSkuInventoryId id = new DeviceSkuInventoryId(deviceId, skuId);
        DeviceSkuInventory inv = inventoryRepository.findById(id).orElseGet(() -> {
            DeviceSkuInventory created = new DeviceSkuInventory();
            created.setId(id);
            created.setCapacity(20);
            created.setLowThreshold(2);
            return created;
        });
        inv.setQuantity(sum);
        inventoryRepository.save(inv);
    }

    @Transactional(readOnly = true)
    public long countNearExpiryLots() {
        LocalDate today = LocalDate.now();
        return lotRepository.countNearExpiry(today, today.plusDays(7));
    }

    @Transactional(readOnly = true)
    public long countExpiredLotsWithStock() {
        return lotRepository.countExpiredWithStock(LocalDate.now());
    }

    @Transactional(readOnly = true)
    public long countOpenPullOffTasks() {
        return pullOffTaskRepository.countByStatus("OPEN");
    }

    @Transactional
    public int scanExpiryAlerts() {
        LocalDate today = LocalDate.now();
        List<DeviceSkuLot> lots = lotRepository.findByStatusInAndQuantityGreaterThan(
                List.of("ON_SALE", "NEAR_EXPIRY", "BLOCKED"), 0);
        int alerts = 0;
        for (DeviceSkuLot lot : lots) {
            SkuCatalog sku = skuCatalogRepository.findById(lot.getSkuId()).orElse(null);
            int nearDays = sku != null ? sku.getNearExpiryDays() : 7;
            LocalDate nearThreshold = today.plusDays(nearDays);

            if (lot.getExpiryDate().isBefore(today)) {
                if (!"BLOCKED".equals(lot.getStatus())) {
                    lot.setStatus("BLOCKED");
                    lotRepository.save(lot);
                }
                if (pullOffTaskRepository.findByLotIdAndStatus(lot.getLotId(), "OPEN").isEmpty()) {
                    PullOffTask task = new PullOffTask();
                    task.setDeviceId(lot.getDeviceId());
                    task.setSkuId(lot.getSkuId());
                    task.setLotId(lot.getLotId());
                    task.setBatchNo(lot.getBatchNo());
                    task.setQuantity(lot.getQuantity());
                    task.setReason("EXPIRED");
                    pullOffTaskRepository.save(task);
                    alerts++;
                }
            } else if (!lot.getExpiryDate().isAfter(nearThreshold)) {
                if ("ON_SALE".equals(lot.getStatus())) {
                    lot.setStatus("NEAR_EXPIRY");
                    lotRepository.save(lot);
                    alerts++;
                }
            }
        }
        return alerts;
    }

    /** 有货道时校验补货后账面不超过 maxLevel / parLevel。 */
    private void ensureSlotCapacity(String deviceId, String slotId, int quantity) {
        if (slotId == null || slotId.isBlank() || quantity <= 0) {
            return;
        }
        String slotCode = slotId.trim().toUpperCase();
        DeviceSlot slot = slotRepository.findById(new DeviceSlotId(deviceId, slotCode)).orElse(null);
        if (slot == null) {
            return;
        }
        int cap = slot.getMaxLevel() > 0 ? slot.getMaxLevel()
                : (slot.getParLevel() > 0 ? slot.getParLevel() : 0);
        if (cap <= 0) {
            return;
        }
        int book = 0;
        for (Object[] row : lotRepository.sumBookQtyBySlot(deviceId)) {
            if (row != null && row.length >= 2 && slotCode.equalsIgnoreCase(String.valueOf(row[0]))) {
                book = ((Number) row[1]).intValue();
                break;
            }
        }
        if (book + quantity > cap) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "slot " + slotCode + " exceeds max " + cap
                            + " (book=" + book + " add=" + quantity + ")");
        }
    }

    private void validateRestockExpiry(String skuId, LocalDate expiryDate) {
        if (expiryDate == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "expiryDate required");
        }
        SkuCatalog sku = skuCatalogRepository.findById(skuId).orElse(null);
        int blockDays = sku != null ? sku.getBlockSaleDaysBeforeExpiry() : 0;
        LocalDate minExpiry = LocalDate.now().plusDays(blockDays);
        if (!expiryDate.isAfter(minExpiry)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "expiry too soon for sku=" + skuId + " min=" + minExpiry);
        }
    }

    private static boolean isSellable(DeviceSkuLot lot, LocalDate minExpiry) {
        if (lot.getQuantity() <= 0) {
            return false;
        }
        String status = lot.getStatus();
        if ("BLOCKED".equals(status) || "DEPLETED".equals(status)) {
            return false;
        }
        return lot.getExpiryDate().isAfter(minExpiry);
    }

    private String resolveLotStatus(DeviceSkuLot lot) {
        LocalDate today = LocalDate.now();
        if (lot.getExpiryDate().isBefore(today)) {
            return "BLOCKED";
        }
        SkuCatalog sku = skuCatalogRepository.findById(lot.getSkuId()).orElse(null);
        int nearDays = sku != null ? sku.getNearExpiryDays() : 7;
        if (!lot.getExpiryDate().isAfter(today.plusDays(nearDays))) {
            return "NEAR_EXPIRY";
        }
        return "ON_SALE";
    }

    private DeviceSkuLot createFallbackLot(String deviceId, String skuId, String batchNo) {
        DeviceSkuLot lot = new DeviceSkuLot();
        lot.setLotId("L" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase());
        lot.setDeviceId(deviceId);
        lot.setSkuId(skuId);
        lot.setBatchNo(batchNo);
        lot.setExpiryDate(LocalDate.now().plusDays(365));
        lot.setStatus("ON_SALE");
        return lot;
    }

    private void recordMovement(String deviceId, String skuId, String batchNo,
                                String movementType, int deltaQty,
                                String refType, String refId, Long operatorId) {
        InventoryMovement m = new InventoryMovement();
        m.setDeviceId(deviceId);
        m.setSkuId(skuId);
        m.setBatchNo(batchNo);
        m.setMovementType(movementType);
        m.setDeltaQty(deltaQty);
        m.setRefType(refType);
        m.setRefId(refId);
        m.setOperatorId(operatorId);
        movementRepository.save(m);
    }
}
