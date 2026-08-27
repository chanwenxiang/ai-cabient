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
import org.springframework.context.annotation.Lazy;
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
    private static final String NEAR_EXPIRY = "NEAR_EXPIRY";
    private static final String REPLENISH = "REPLENISH";
    private static final String WRITE_OFF = "WRITE_OFF";
    private static final String PULL_OFF = "PULL_OFF";
    private static final String DEPLETED = "DEPLETED";
    private static final String BLOCKED = "BLOCKED";
    private static final String ON_SALE = "ON_SALE";


    private static final Logger log = LoggerFactory.getLogger(InventoryLotService.class);

    private final DeviceSkuLotMapper lotRepository;
    private final InventoryMovementMapper movementRepository;
    private final DeviceSkuInventoryMapper inventoryRepository;
    private final SkuCatalogMapper skuCatalogRepository;
    private final PullOffTaskMapper pullOffTaskRepository;
    private final DeviceSlotMapper slotRepository;
    private final DistributedLockService distributedLockService;
    private final InventoryLotService self;

    public InventoryLotService(DeviceSkuLotMapper lotRepository,
                               InventoryMovementMapper movementRepository,
                               DeviceSkuInventoryMapper inventoryRepository,
                               SkuCatalogMapper skuCatalogRepository,
                               PullOffTaskMapper pullOffTaskRepository,
                               DeviceSlotMapper slotRepository,
                               DistributedLockService distributedLockService,
                               @Lazy InventoryLotService self) {
        this.lotRepository = lotRepository;
        this.movementRepository = movementRepository;
        this.inventoryRepository = inventoryRepository;
        this.skuCatalogRepository = skuCatalogRepository;
        this.pullOffTaskRepository = pullOffTaskRepository;
        this.slotRepository = slotRepository;
        this.distributedLockService = distributedLockService;
        this.self = self;
    }

    public boolean hasSellableLots(String deviceId, String skuId) {
        return lotRepository.sumSellableQuantity(deviceId, skuId) > 0;
    }

    /** 柜机已启用批次账本（存在任意 lot 行）时，可售以批次为准，禁止只改汇总表。 */
    public boolean deviceUsesLotLedger(String deviceId) {
        if (deviceId == null || deviceId.isBlank()) {
            return false;
        }
        return !lotRepository.findByDeviceId(deviceId.trim()).isEmpty();
    }

    public int sellableQuantity(String deviceId, String skuId) {
        return lotRepository.sumSellableQuantity(deviceId, skuId);
    }

    /**
     * 消费者/购物车可见可售量：有批次账本时用可售批次汇总，否则回退 device_sku_inventory。
     */
    public int availableSellableQuantity(String deviceId, String skuId) {
        if (deviceId == null || deviceId.isBlank() || skuId == null || skuId.isBlank()) {
            return 0;
        }
        String dev = deviceId.trim();
        String sku = skuId.trim();
        if (deviceUsesLotLedger(dev)) {
            return sellableQuantity(dev, sku);
        }
        return inventoryRepository.findById(new DeviceSkuInventoryId(dev, sku))
                .map(DeviceSkuInventory::getQuantity)
                .orElse(0);
    }

    /** deviceId → skuId → 可售数量（ON_SALE/NEAR_EXPIRY）。 */
    public Map<String, Integer> sellableQtyBySku(String deviceId) {
        Map<String, Integer> map = new LinkedHashMap<>();
        for (Object[] row : lotRepository.sumSellableBySku(deviceId)) {
            if (row == null || row.length < 2 || row[0] == null || row[1] == null) {
                continue;
            }
            map.merge(String.valueOf(row[0]), ((Number) row[1]).intValue(), Integer::sum);
        }
        return map;
    }

    /** FEFO 扣减，返回主批次号与按货道扣减数量。 */
    @Transactional
    public FefoDeductResult deductFefo(String deviceId, String skuId, int quantity, String refType, String refId) {
        return self.deductFefo(deviceId, skuId, quantity, refType, refId, null);
    }

    @Transactional
    public FefoDeductResult deductFefo(String deviceId, String skuId, int quantity,
                                       String refType, String refId, String slotId) {
        return runWithDeviceLotLock(deviceId, () -> doDeductFefo(deviceId, skuId, quantity, refType, refId, slotId));
    }

    private FefoDeductResult doDeductFefo(String deviceId, String skuId, int quantity,
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
        DeductionAccumulator acc = deductSellableLots(
                deviceId, skuId, quantity, refType, refId, minExpiry, lots);
        if (acc.remaining() > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "sellable lot inventory insufficient for sku=" + skuId + " need=" + quantity);
        }
        self.syncAggregateInventory(deviceId, skuId);
        return new FefoDeductResult(acc.primaryBatch(), acc.slotQty());
    }

    private record DeductionAccumulator(String primaryBatch, Map<String, Integer> slotQty, int remaining) {}

    private DeductionAccumulator deductSellableLots(String deviceId, String skuId, int quantity,
                                                    String refType, String refId, LocalDate minExpiry,
                                                    List<DeviceSkuLot> lots) {
        int remaining = quantity;
        String primaryBatch = null;
        Map<String, Integer> slotQty = new LinkedHashMap<>();
        for (DeviceSkuLot lot : lots) {
            if (remaining > 0 && isSellable(lot, minExpiry)) {
                int take = Math.min(lot.getQuantity(), remaining);
                if (take > 0) {
                    if (primaryBatch == null) {
                        primaryBatch = lot.getBatchNo();
                    }
                    lot.setQuantity(lot.getQuantity() - take);
                    if (lot.getQuantity() == 0) {
                        lot.setStatus(DEPLETED);
                    }
                    lotRepository.save(lot);
                    recordMovement(deviceId, skuId, new InventoryMovementCommand(
                            lot.getBatchNo(), "SALE", -take, new LotMovementRef(refType, refId, null)));
                    if (lot.getSlotId() != null && !lot.getSlotId().isBlank()) {
                        String slotCode = lot.getSlotId().trim().toUpperCase();
                        slotQty.merge(slotCode, take, Integer::sum);
                    }
                    remaining -= take;
                }
            }
        }
        return new DeductionAccumulator(primaryBatch, slotQty, remaining);
    }

    public record FefoDeductResult(String primaryBatch, Map<String, Integer> slotQtyDeducted) {}

    public record LotMovementRef(String refType, String refId, Long operatorId) {}

    private record InventoryMovementCommand(
            String batchNo, String movementType, int deltaQty, LotMovementRef ref) {}

    public record RestockCommand(
            String batchNo, LocalDate productionDate, LocalDate expiryDate,
            int quantity, String slotId, LotMovementRef ref) {}

    private record BatchLotDeductCommand(
            String batchNo, int quantity, String movementType, LotMovementRef ref) {}

    /** 同批次跨货道扣减（下架/报损按批次号操作时）。 */
    private void deductBatchAcrossLots(String deviceId, String skuId, BatchLotDeductCommand command) {
        List<DeviceSkuLot> lots = lotRepository.findAllByDeviceIdAndSkuIdAndBatchNo(
                deviceId, skuId, command.batchNo());
        if (lots.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "lot not found");
        }
        int remaining = command.quantity();
        int totalTaken = 0;
        for (DeviceSkuLot lot : lots) {
            if (remaining > 0 && lot.getQuantity() > 0) {
                int take = Math.min(lot.getQuantity(), remaining);
                lot.setQuantity(lot.getQuantity() - take);
                if (lot.getQuantity() == 0) {
                    lot.setStatus(DEPLETED);
                }
                lotRepository.save(lot);
                remaining -= take;
                totalTaken += take;
            }
        }
        if (totalTaken <= 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "lot not found");
        }
        recordMovement(deviceId, skuId, new InventoryMovementCommand(
                command.batchNo(), command.movementType(), -totalTaken, command.ref()));
    }

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
            if (lot.getQuantity() > 0) {
                boolean sellable = WRITE_OFF.equals(movementType) || "ADJ".equals(movementType)
                        || PULL_OFF.equals(movementType) || isSellable(lot, minExpiry);
                if (sellable) {
                    int take = Math.min(lot.getQuantity(), remaining);
                    lot.setQuantity(lot.getQuantity() - take);
                    if (lot.getQuantity() == 0) {
                        lot.setStatus(DEPLETED);
                    }
                    lotRepository.save(lot);
                    recordMovement(deviceId, skuId, new InventoryMovementCommand(
                            lot.getBatchNo(), movementType, -take,
                            new LotMovementRef(refType, refId, operatorId)));
                    remaining -= take;
                }
            }
        }

        if (remaining > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "lot inventory insufficient for sku=" + skuId + " need=" + quantity);
        }
    }

    @Transactional
    public String restoreToBatch(String deviceId, String skuId, String batchNo, int quantity, String refType, String refId) {
        return runWithDeviceLotLock(deviceId, () -> doRestoreToBatch(deviceId, skuId, batchNo, quantity, refType, refId));
    }

    private String doRestoreToBatch(String deviceId, String skuId, String batchNo, int quantity, String refType, String refId) {
        if (quantity <= 0) {
            return null;
        }
        DeviceSkuLot lot = lotRepository.findByDeviceIdAndSkuIdAndBatchNo(deviceId, skuId, batchNo)
                .orElseGet(() -> createFallbackLot(deviceId, skuId, batchNo));
        if (lot.getSlotId() == null || lot.getSlotId().isBlank()) {
            String primarySlot = resolvePrimarySlotCode(deviceId, skuId);
            if (primarySlot != null) {
                lot.setSlotId(primarySlot);
            }
        }
        lot.setQuantity(lot.getQuantity() + quantity);
        if (DEPLETED.equals(lot.getStatus()) || BLOCKED.equals(lot.getStatus())) {
            lot.setStatus(resolveLotStatus(lot));
        }
        lotRepository.save(lot);
        recordMovement(deviceId, skuId, new InventoryMovementCommand(
                batchNo, "REFUND", quantity, new LotMovementRef(refType, refId, null)));
        self.syncAggregateInventory(deviceId, skuId);
        return lot.getSlotId();
    }

    @Transactional
    public void addRestock(String deviceId, String skuId, RestockCommand command) {
        runWithDeviceLotLock(deviceId, () -> {
            doAddRestock(deviceId, skuId, command);
            return null;
        });
    }

    private void doAddRestock(String deviceId, String skuId, RestockCommand command) {
        validateRestockExpiry(skuId, command.expiryDate());
        ensureSlotCapacity(deviceId, command.slotId(), command.quantity());
        String resolvedBatch = (command.batchNo() == null || command.batchNo().isBlank())
                ? "B-" + LocalDate.now() + "-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase()
                : command.batchNo().trim();

        String slotCode = (command.slotId() == null || command.slotId().isBlank())
                ? null : command.slotId().trim().toUpperCase();
        DeviceSkuLot lot = null;
        if (slotCode != null) {
            lot = lotRepository
                    .findByDeviceIdAndSkuIdAndBatchNoAndSlotId(deviceId, skuId, resolvedBatch, slotCode)
                    .orElse(null);
        }
        if (lot == null && slotCode == null) {
            lot = lotRepository.findByDeviceIdAndSkuIdAndBatchNo(deviceId, skuId, resolvedBatch).orElse(null);
        }
        if (lot == null) {
            lot = new DeviceSkuLot();
            lot.setLotId("L" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase());
            lot.setDeviceId(deviceId);
            lot.setSkuId(skuId);
            lot.setBatchNo(resolvedBatch);
            lot.setProductionDate(command.productionDate());
            lot.setExpiryDate(command.expiryDate());
            lot.setSlotId(slotCode);
            lot.setStatus(ON_SALE);
            lot.setQuantity(0);
        }
        lot.setQuantity(lot.getQuantity() + command.quantity());
        lot.setStatus(resolveLotStatus(lot));
        if (slotCode != null) {
            lot.setSlotId(slotCode);
        }
        lotRepository.save(lot);
        recordMovement(deviceId, skuId, new InventoryMovementCommand(
                resolvedBatch, "RESTOCK", command.quantity(),
                new LotMovementRef(REPLENISH, command.ref().refId(), command.ref().operatorId())));
        self.syncAggregateInventory(deviceId, skuId);
        log.info("restock lot device={} sku={} batch={} qty={}", deviceId, skuId, resolvedBatch, command.quantity());
    }

    @Transactional
    public void pullOff(String deviceId, String skuId, String batchNo, int quantity,
                        Long operatorId, String refId) {
        runWithDeviceLotLock(deviceId, () -> {
            doPullOff(deviceId, skuId, batchNo, quantity, operatorId, refId);
            return null;
        });
    }

    private void doPullOff(String deviceId, String skuId, String batchNo, int quantity,
                           Long operatorId, String refId) {
        if (quantity <= 0) {
            return;
        }
        if (batchNo != null && !batchNo.isBlank()) {
            deductBatchAcrossLots(deviceId, skuId, new BatchLotDeductCommand(
                    batchNo, quantity, PULL_OFF, new LotMovementRef(REPLENISH, refId, operatorId)));
        } else {
            deductFefoForRef(deviceId, skuId, quantity, PULL_OFF, REPLENISH, refId, operatorId);
        }
        self.syncAggregateInventory(deviceId, skuId);
    }

    @Transactional
    public void writeOffLots(String deviceId, String skuId, String batchNo, int quantity,
                             Long operatorId, String refId) {
        runWithDeviceLotLock(deviceId, () -> {
            doWriteOffLots(deviceId, skuId, batchNo, quantity, operatorId, refId);
            return null;
        });
    }

    private void doWriteOffLots(String deviceId, String skuId, String batchNo, int quantity,
                              Long operatorId, String refId) {
        if (quantity <= 0) {
            return;
        }
        if (batchNo != null && !batchNo.isBlank()) {
            deductBatchAcrossLots(deviceId, skuId, new BatchLotDeductCommand(
                    batchNo, quantity, WRITE_OFF, new LotMovementRef(WRITE_OFF, refId, operatorId)));
        } else {
            deductFefoForRef(deviceId, skuId, quantity, WRITE_OFF, WRITE_OFF, refId, operatorId);
        }
        self.syncAggregateInventory(deviceId, skuId);
    }

    /**
     * 售后仅退款不回库：不改批次数量，只记审计流水（销售扣减已发生，成本留在售出侧）。
     */
    @Transactional
    public void recordRefundKeptNote(String deviceId, String skuId, String batchNo,
                                     int quantity, String orderId) {
        if (quantity <= 0 || orderId == null || orderId.isBlank()) {
            return;
        }
        String batch = (batchNo == null || batchNo.isBlank()) ? "-" : batchNo;
        // delta=0：数量不变；quantity 语义写在 refId 旁供运营检索
        recordMovement(deviceId, skuId, new InventoryMovementCommand(
                batch, "REFUND_KEPT", 0, new LotMovementRef("ORDER_REFUND", orderId + ":qty=" + quantity, null)));
        log.info("REFUND_KEPT noted device={} sku={} batch={} qty={} order={}",
                deviceId, skuId, batch, quantity, orderId);
    }

    @Transactional
    public void stocktakeAdjust(String deviceId, String skuId, int countedQuantity,
                                Long operatorId, String refId) {
        runWithDeviceLotLock(deviceId, () -> {
            doStocktakeAdjust(deviceId, skuId, countedQuantity, operatorId, refId);
            return null;
        });
    }

    private void doStocktakeAdjust(String deviceId, String skuId, int countedQuantity,
                                   Long operatorId, String refId) {
        DeviceSkuInventoryId id = new DeviceSkuInventoryId(deviceId, skuId);
        int current = deviceUsesLotLedger(deviceId)
                ? lotRepository.sumSellableQuantity(deviceId, skuId)
                : inventoryRepository.findById(id).map(DeviceSkuInventory::getQuantity).orElse(0);
        int delta = countedQuantity - current;
        if (delta == 0) {
            self.syncAggregateInventory(deviceId, skuId);
            return;
        }
        if (delta > 0) {
            SkuCatalog sku = skuCatalogRepository.findById(skuId).orElse(null);
            int shelfDays = sku != null && sku.getShelfLifeDays() != null ? sku.getShelfLifeDays() : 180;
            LocalDate expiry = LocalDate.now().plusDays(shelfDays);
            doAddRestock(deviceId, skuId, new RestockCommand(
                    "STOCKTAKE-" + LocalDate.now(), LocalDate.now(), expiry, delta, null,
                    new LotMovementRef(REPLENISH, refId, operatorId)));
        } else {
            deductFefoForRef(deviceId, skuId, -delta, "ADJ", "STOCKTAKE", refId, operatorId);
        }
        self.syncAggregateInventory(deviceId, skuId);
    }

    /**
     * 按货道实盘回写账面：只动该货道上该 SKU 的批次，避免串道。
     */
    @Transactional
    public void stocktakeAdjustForSlot(String deviceId, String skuId, String slotCode,
                                       int countedQuantity, Long operatorId, String refId) {
        runWithDeviceLotLock(deviceId, () -> {
            doStocktakeAdjustForSlot(deviceId, skuId, slotCode, countedQuantity, operatorId, refId);
            return null;
        });
    }

    private void doStocktakeAdjustForSlot(String deviceId, String skuId, String slotCode,
                                          int countedQuantity, Long operatorId, String refId) {
        if (slotCode == null || slotCode.isBlank()) {
            doStocktakeAdjust(deviceId, skuId, countedQuantity, operatorId, refId);
            return;
        }
        String slot = slotCode.trim().toUpperCase();
        ensureSlotBookTarget(deviceId, slot, countedQuantity);
        List<DeviceSkuLot> lots = lotRepository
                .findByDeviceIdAndSkuIdAndSlotIdOrderByExpiryDateAsc(deviceId, skuId, slot);
        int current = lots.stream().mapToInt(DeviceSkuLot::getQuantity).sum();
        int delta = countedQuantity - current;
        if (delta == 0) {
            return;
        }
        if (delta > 0) {
            SkuCatalog sku = skuCatalogRepository.findById(skuId).orElse(null);
            int shelfDays = sku != null && sku.getShelfLifeDays() != null ? sku.getShelfLifeDays() : 180;
            LocalDate expiry = LocalDate.now().plusDays(shelfDays);
            doAddRestock(deviceId, skuId, new RestockCommand(
                    "STOCKTAKE-" + LocalDate.now(), LocalDate.now(), expiry, delta, slot,
                    new LotMovementRef(REPLENISH, refId, operatorId)));
            return;
        }
        int remaining = -delta;
        for (DeviceSkuLot lot : lots) {
            if (remaining > 0 && lot.getQuantity() > 0) {
                int take = Math.min(lot.getQuantity(), remaining);
                lot.setQuantity(lot.getQuantity() - take);
                if (lot.getQuantity() == 0) {
                    lot.setStatus(DEPLETED);
                }
                lotRepository.save(lot);
                recordMovement(deviceId, skuId, new InventoryMovementCommand(
                        lot.getBatchNo(), "ADJ", -take, new LotMovementRef("STOCKTAKE", refId, operatorId)));
                remaining -= take;
            }
        }
        if (remaining > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "货道 " + slot + " 账面不足以盘亏，差额 " + remaining);
        }
        self.syncAggregateInventory(deviceId, skuId);
    }

    @Transactional
    public void applyReplenishmentLine(String deviceId, ReplenishmentTaskLine line, Long operatorId, String refId) {
        runWithDeviceLotLock(deviceId, () -> {
            doApplyReplenishmentLine(deviceId, line, operatorId, refId);
            return null;
        });
    }

    private void doApplyReplenishmentLine(String deviceId, ReplenishmentTaskLine line, Long operatorId, String refId) {
        if ("RESTOCK".equalsIgnoreCase(line.getLineType())) {
            LocalDate expiry = line.getExpiryDate();
            LocalDate production = line.getProductionDate();
            if (expiry == null) {
                SkuCatalog sku = skuCatalogRepository.findById(line.getSkuId()).orElse(null);
                int shelfDays = sku != null && sku.getShelfLifeDays() != null ? sku.getShelfLifeDays() : 180;
                if (production != null) {
                    expiry = production.plusDays(shelfDays);
                } else {
                    // 现场补货未填批次日期时，按保质期从今天起算，避免阻断上架回传
                    production = LocalDate.now();
                    expiry = production.plusDays(shelfDays);
                }
            }
            doAddRestock(deviceId, line.getSkuId(), new RestockCommand(
                    line.getBatchNo(), production, expiry, line.getQuantity(), line.getSlotId(),
                    new LotMovementRef(REPLENISH, refId, operatorId)));
        } else if (PULL_OFF.equalsIgnoreCase(line.getLineType())) {
            doPullOff(deviceId, line.getSkuId(), line.getBatchNo(), line.getQuantity(), operatorId, refId);
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
        // 覆盖常见 nearExpiryDays（通常 ≤30）；更长窗口留给后续批次/手动盘点
        LocalDate horizon = today.plusDays(90);
        List<DeviceSkuLot> lots = lotRepository.findForExpiryScan(
                List.of(ON_SALE, NEAR_EXPIRY, BLOCKED), horizon, 500);
        int alerts = 0;
        for (DeviceSkuLot lot : lots) {
            alerts += scanSingleLotForExpiry(lot, today);
        }
        return alerts;
    }

    private int scanSingleLotForExpiry(DeviceSkuLot lot, LocalDate today) {
        SkuCatalog sku = skuCatalogRepository.findById(lot.getSkuId()).orElse(null);
        int nearDays = sku != null ? sku.getNearExpiryDays() : 7;
        LocalDate nearThreshold = today.plusDays(nearDays);
        if (lot.getExpiryDate().isBefore(today)) {
            return processExpiredLot(lot);
        }
        if (!lot.getExpiryDate().isAfter(nearThreshold)) {
            return processNearExpiryLot(lot);
        }
        return 0;
    }

    private int processExpiredLot(DeviceSkuLot lot) {
        int alerts = 0;
        if (!BLOCKED.equals(lot.getStatus())) {
            lot.setStatus(BLOCKED);
            lotRepository.save(lot);
        }
        if (createPullOffTaskIfAbsent(lot, "EXPIRED")) {
            alerts++;
        }
        return alerts;
    }

    private int processNearExpiryLot(DeviceSkuLot lot) {
        int alerts = 0;
        if (ON_SALE.equals(lot.getStatus())) {
            lot.setStatus(NEAR_EXPIRY);
            lotRepository.save(lot);
            alerts++;
        }
        // 临期批次同步生成下架任务，避免只改状态无人处理
        if ((NEAR_EXPIRY.equals(lot.getStatus()) || ON_SALE.equals(lot.getStatus()))
                && createPullOffTaskIfAbsent(lot, NEAR_EXPIRY)) {
            alerts++;
        }
        return alerts;
    }

    /** @return true 若新建了 OPEN 下架任务 */
    private boolean createPullOffTaskIfAbsent(DeviceSkuLot lot, String reason) {
        if (!pullOffTaskRepository.findByLotIdAndStatus(lot.getLotId(), "OPEN").isEmpty()) {
            return false;
        }
        PullOffTask task = new PullOffTask();
        task.setDeviceId(lot.getDeviceId());
        task.setSkuId(lot.getSkuId());
        task.setLotId(lot.getLotId());
        task.setBatchNo(lot.getBatchNo());
        task.setQuantity(lot.getQuantity());
        task.setReason(reason);
        pullOffTaskRepository.save(task);
        return true;
    }

    /**
     * 按 lot 幂等创建 OPEN 下架任务（临期/过期盘点入口）。
     */
    @Transactional
    public PullOffTask ensureOpenPullOffTask(String lotId) {
        if (lotId == null || lotId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "批次不能为空");
        }
        DeviceSkuLot lot = lotRepository.findById(lotId.trim())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "批次不存在"));
        if (lot.getQuantity() <= 0) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED, "批次已无库存");
        }
        return pullOffTaskRepository.findByLotIdAndStatus(lot.getLotId(), "OPEN")
                .orElseGet(() -> {
                    LocalDate today = LocalDate.now();
                    String reason = lot.getExpiryDate() != null && lot.getExpiryDate().isBefore(today)
                            ? "EXPIRED" : NEAR_EXPIRY;
                    if (ON_SALE.equals(lot.getStatus()) && NEAR_EXPIRY.equals(reason)) {
                        lot.setStatus(NEAR_EXPIRY);
                        lotRepository.save(lot);
                    }
                    PullOffTask task = new PullOffTask();
                    task.setDeviceId(lot.getDeviceId());
                    task.setSkuId(lot.getSkuId());
                    task.setLotId(lot.getLotId());
                    task.setBatchNo(lot.getBatchNo());
                    task.setQuantity(lot.getQuantity());
                    task.setReason(reason);
                    pullOffTaskRepository.save(task);
                    log.info("ensure pull-off task lot={} reason={} qty={}", lot.getLotId(), reason, lot.getQuantity());
                    return task;
                });
    }

    /**
     * 货道间挪货（容量校正：溢出货道 → 同 SKU 尚有容量的货道）。
     */
    @Transactional
    public void transferBetweenSlots(String deviceId, String skuId, String fromSlotCode, String toSlotCode,
                                     int quantity, Long operatorId, String refId) {
        if (quantity <= 0) {
            return;
        }
        runWithDeviceLotLock(deviceId, () -> {
            doTransferBetweenSlots(deviceId, skuId, fromSlotCode, toSlotCode, quantity, operatorId, refId);
            return null;
        });
    }

    private void doTransferBetweenSlots(String deviceId, String skuId, String fromSlotCode, String toSlotCode,
                                        int quantity, Long operatorId, String refId) {
        String from = fromSlotCode.trim().toUpperCase();
        String to = toSlotCode.trim().toUpperCase();
        ensureSlotCapacity(deviceId, to, quantity);
        List<DeviceSkuLot> lots = lotRepository
                .findByDeviceIdAndSkuIdAndSlotIdOrderByExpiryDateAsc(deviceId, skuId, from);
        int remaining = quantity;
        for (DeviceSkuLot lot : lots) {
            if (remaining > 0 && lot.getQuantity() > 0) {
                int take = Math.min(lot.getQuantity(), remaining);
                String batch = lot.getBatchNo();
                LocalDate production = lot.getProductionDate();
                LocalDate expiry = lot.getExpiryDate();
                lot.setQuantity(lot.getQuantity() - take);
                if (lot.getQuantity() == 0) {
                    lot.setStatus(DEPLETED);
                }
                lotRepository.save(lot);
                recordMovement(deviceId, skuId, new InventoryMovementCommand(
                        batch, "ADJ", -take, new LotMovementRef("SLOT_TRANSFER", refId, operatorId)));
                doAddRestock(deviceId, skuId, new RestockCommand(
                        batch, production, expiry, take, to,
                        new LotMovementRef(REPLENISH, refId, operatorId)));
                remaining -= take;
            }
        }
        if (remaining > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "货道 " + from + " 可挪库存不足，还差 " + remaining);
        }
        self.syncAggregateInventory(deviceId, skuId);
    }

    /** 盘点/实盘目标数量不得超过货道容量。 */
    private void ensureSlotBookTarget(String deviceId, String slotCode, int targetQty) {
        if (targetQty < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "数量不能为负");
        }
        DeviceSlot slot = slotRepository.findById(new DeviceSlotId(deviceId, slotCode.trim().toUpperCase()))
                .orElse(null);
        if (slot == null) {
            return;
        }
        int cap = slotCapacity(slot, 0);
        if (cap > 0 && targetQty > cap) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    String.format(com.aicabinet.trade.support.ApiMessages.SLOT_QTY_OVER_CAPACITY,
                            slotCode, cap, targetQty));
        }
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
        int cap = slotCapacity(slot, 0);
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
                    String.format(com.aicabinet.trade.support.ApiMessages.REPLENISHMENT_SLOT_CAPACITY,
                            slotCode, cap, book, quantity));
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
        if (BLOCKED.equals(status) || DEPLETED.equals(status)) {
            return false;
        }
        return lot.getExpiryDate().isAfter(minExpiry);
    }

    private String resolveLotStatus(DeviceSkuLot lot) {
        LocalDate today = LocalDate.now();
        if (lot.getExpiryDate().isBefore(today)) {
            return BLOCKED;
        }
        SkuCatalog sku = skuCatalogRepository.findById(lot.getSkuId()).orElse(null);
        int nearDays = sku != null ? sku.getNearExpiryDays() : 7;
        if (!lot.getExpiryDate().isAfter(today.plusDays(nearDays))) {
            return NEAR_EXPIRY;
        }
        return ON_SALE;
    }

    private DeviceSkuLot createFallbackLot(String deviceId, String skuId, String batchNo) {
        DeviceSkuLot lot = new DeviceSkuLot();
        lot.setLotId("L" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase());
        lot.setDeviceId(deviceId);
        lot.setSkuId(skuId);
        lot.setBatchNo(batchNo);
        lot.setExpiryDate(LocalDate.now().plusDays(365));
        lot.setStatus(ON_SALE);
        lot.setQuantity(0);
        lot.setSlotId(resolvePrimarySlotCode(deviceId, skuId));
        return lot;
    }

    /** 回库无货道时绑定该 SKU 账面最多的启用货道，避免账面/实测分叉。 */
    private String resolvePrimarySlotCode(String deviceId, String skuId) {
        List<DeviceSlot> slots = slotRepository.findByIdDeviceIdOrderByRowNoAscColNoAsc(deviceId).stream()
                .filter(s -> s.isEnabled() && skuId.equals(s.getAssignedSkuId()))
                .toList();
        if (slots.isEmpty()) {
            return null;
        }
        Map<String, Integer> bookBySlot = new LinkedHashMap<>();
        for (Object[] row : lotRepository.sumBookQtyBySlot(deviceId)) {
            if (row == null || row.length < 2 || row[0] == null || row[1] == null) {
                continue;
            }
            bookBySlot.merge(String.valueOf(row[0]), ((Number) row[1]).intValue(), Integer::sum);
        }
        String best = null;
        int bestQty = -1;
        for (DeviceSlot slot : slots) {
            String code = slot.getId().getSlotCode();
            int book = bookBySlot.getOrDefault(code, 0);
            if (book > bestQty) {
                bestQty = book;
                best = code;
            }
        }
        return best != null ? best : slots.get(0).getId().getSlotCode();
    }

    private void recordMovement(String deviceId, String skuId, InventoryMovementCommand command) {
        InventoryMovement m = new InventoryMovement();
        m.setDeviceId(deviceId);
        m.setSkuId(skuId);
        m.setBatchNo(command.batchNo());
        m.setMovementType(command.movementType());
        m.setDeltaQty(command.deltaQty());
        m.setRefType(command.ref().refType());
        m.setRefId(command.ref().refId());
        m.setOperatorId(command.ref().operatorId());
        movementRepository.save(m);
    }

    private <T> T runWithDeviceLotLock(String deviceId, java.util.function.Supplier<T> action) {
        String lockKey = InventoryService.deviceLockKey(deviceId);
        if (!distributedLockService.tryLock(lockKey, 60, 5)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "库存繁忙，请稍后重试");
        }
        try {
            return action.get();
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage(), e);
        } finally {
            distributedLockService.unlock(lockKey);
        }
    }

    private static int slotCapacity(DeviceSlot slot, int whenBothZero) {
        if (slot.getMaxLevel() > 0) {
            return slot.getMaxLevel();
        }
        if (slot.getParLevel() > 0) {
            return slot.getParLevel();
        }
        return whenBothZero;
    }
}
