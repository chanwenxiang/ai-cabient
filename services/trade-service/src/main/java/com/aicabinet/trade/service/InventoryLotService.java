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
import com.aicabinet.trade.support.ScheduleZones;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class InventoryLotService {
    private static final String NEAR_EXPIRY = "NEAR_EXPIRY";
    private static final String REPLENISH = "REPLENISH";
    private static final String WRITE_OFF = "WRITE_OFF";
    private static final String PULL_OFF = "PULL_OFF";
    private static final String DEPLETED = "DEPLETED";
    private static final String BLOCKED = "BLOCKED";
    private static final String ON_SALE = "ON_SALE";
    /** 无批次支撑的历史库存（裸库存）在首次开批次账时并入的批次号。 */
    private static final String LEGACY_OPENING_BATCH = "LEGACY-OPENING";


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

    /**
     * 该柜**已建批次账**的 SKU 集合（含 DEPLETED/EXPIRED 等零可售批次——只表示进过账本）。
     *
     * <p>用来区分「该 SKU 卖光了」和「该 SKU 从来没进过账本」：前者在可售汇总里查不到就是 0，
     * 后者查不到只是因为汇总按可售状态过滤、行压根不存在。两者都读成 0 会把历史分叉行
     * （{@code device_sku_inventory} 有值、账本空）永久显示成缺货。
     */
    public Set<String> lotLedgerSkuIds(String deviceId) {
        if (deviceId == null || deviceId.isBlank()) {
            return Set.of();
        }
        return lotRepository.findByDeviceId(deviceId.trim()).stream()
                .map(DeviceSkuLot::getSkuId)
                .collect(Collectors.toSet());
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
        return runWithDeviceLotLock(deviceId, () -> doDeductFefo(deviceId, skuId, quantity, refType, refId));
    }

    private FefoDeductResult doDeductFefo(String deviceId, String skuId, int quantity,
                                          String refType, String refId) {
        if (quantity <= 0) {
            return new FefoDeductResult(null);
        }
        SkuCatalog sku = skuCatalogRepository.findById(skuId).orElse(null);
        int blockDays = sku != null ? sku.getBlockSaleDaysBeforeExpiry() : 0;
        LocalDate today = LocalDate.now();
        LocalDate minExpiry = today.plusDays(blockDays);

        // 批 3b 第十八刀：FEFO 不再有「按货道扣减」维度（开门柜无货道；真柜 lot.slot_id 恒 NULL）
        List<DeviceSkuLot> lots = lotRepository.findByDeviceIdAndSkuIdOrderByExpiryDateAsc(deviceId, skuId);
        DeductionAccumulator acc = deductSellableLots(
                deviceId, skuId, quantity, refType, refId, minExpiry, lots);
        if (acc.remaining() > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "sellable lot inventory insufficient for sku=" + skuId + " need=" + quantity);
        }
        self.syncAggregateInventory(deviceId, skuId);
        return new FefoDeductResult(acc.primaryBatch());
    }

    private record DeductionAccumulator(String primaryBatch, int remaining) {}

    private DeductionAccumulator deductSellableLots(String deviceId, String skuId, int quantity,
                                                    String refType, String refId, LocalDate minExpiry,
                                                    List<DeviceSkuLot> lots) {
        int remaining = quantity;
        String primaryBatch = null;
        for (DeviceSkuLot lot : lots) {
            if (remaining > 0) {
                LotDeductionStep step = tryDeductFromSellableLot(
                        deviceId, skuId, lot, remaining, refType, refId, minExpiry);
                if (step != null) {
                    if (primaryBatch == null) {
                        primaryBatch = step.batchNo();
                    }
                    if (step.slotCode() != null) {
                    }
                    remaining -= step.taken();
                }
            }
        }
        return new DeductionAccumulator(primaryBatch, remaining);
    }

    private record LotDeductionStep(int taken, String batchNo, String slotCode) {}

    /**
     * 集群就绪 P-1：批次扣减改走 {@link DeviceSkuLotMapper#deductQuantityAtomic} 单语句
     * 原子 UPDATE（数量谓词与写入同语句），DB 层另有 V457 CHECK 兜底。此前「读→算→写」
     * 依赖 Redis 设备锁作唯一防线，多副本 + 租约过期时可超扣负库存。
     * 返回 null = 本批次没扣到（不足/被并发扣空），调用方继续下一批次。
     */
    private LotDeductionStep tryDeductFromSellableLot(String deviceId, String skuId, DeviceSkuLot lot,
                                                      int remaining, String refType, String refId,
                                                      LocalDate minExpiry) {
        if (!isSellable(lot, minExpiry)) {
            return null;
        }
        int take = Math.min(lot.getQuantity(), remaining);
        if (take <= 0) {
            return null;
        }
        int rows = lotRepository.deductQuantityAtomic(lot.getLotId(), take);
        if (rows == 0) {
            // 并发竞争：本方读到的量已被别人扣走，按 DB 最新量收敛重试一次
            DeviceSkuLot fresh = lotRepository.findById(lot.getLotId()).orElse(null);
            if (fresh == null) {
                return null;
            }
            take = Math.min(fresh.getQuantity(), remaining);
            if (take <= 0) {
                return null;
            }
            rows = lotRepository.deductQuantityAtomic(lot.getLotId(), take);
            if (rows == 0) {
                return null;
            }
        }
        recordMovement(deviceId, skuId, new InventoryMovementCommand(
                lot.getBatchNo(), "SALE", -take, new LotMovementRef(refType, refId, null)));
        String slotCode = null;
        if (lot.getSlotId() != null && !lot.getSlotId().isBlank()) {
            slotCode = lot.getSlotId().trim().toUpperCase();
        }
        return new LotDeductionStep(take, lot.getBatchNo(), slotCode);
    }

    public record FefoDeductResult(String primaryBatch) {}

    public record LotMovementRef(String refType, String refId, Long operatorId) {}

    private record InventoryMovementCommand(
            String batchNo, String movementType, int deltaQty, LotMovementRef ref) {}

    public record RestockCommand(
            String batchNo, LocalDate productionDate, LocalDate expiryDate,
            int quantity, LotMovementRef ref) {}

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
                // R18-2：单语句原子扣（数量谓词与写入同语句）。读出的 quantity 只是 take 上界的
                // 快照——并发方先扣走导致 0 行时，本批不重复扣，remaining 找下一批；都不足则
                // 末尾 CONFLICT 整笔回滚。归零置 DEPLETED 由 UPDATE 内 CASE 完成。
                int rows = lotRepository.deductQuantityAtomic(lot.getLotId(), take);
                if (rows > 0) {
                    remaining -= take;
                    totalTaken += take;
                }
            }
        }
        if (totalTaken <= 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "lot not found");
        }
        if (remaining > 0) {
            // 批次在库量不足以覆盖本次核销/下架数量：拒绝部分扣减，与 FEFO 路径保持一致
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "批次库存不足：batch=" + command.batchNo() + " need=" + command.quantity()
                            + " short=" + remaining);
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
                    // R18-2：单语句原子扣（同 deductBatchAcrossLots）；0 行=并发被先扣，量留给下批兜
                    int rows = lotRepository.deductQuantityAtomic(lot.getLotId(), take);
                    if (rows > 0) {
                        recordMovement(deviceId, skuId, new InventoryMovementCommand(
                                lot.getBatchNo(), movementType, -take,
                                new LotMovementRef(refType, refId, operatorId)));
                        remaining -= take;
                    }
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
        // R18-2：已有批次的量走单语句原子累加——实体 save 是绝对覆盖（DeviceSkuLot 无
        // version），与下架/报损/FEFO 并发时后写者会覆盖已扣量。status/slot 是低冲突属性列，
        // 用只写这两列的独立 UPDATE 修正，不触碰 quantity。
        DeviceSkuLot existing = lotRepository.findByDeviceIdAndSkuIdAndBatchNo(deviceId, skuId, batchNo)
                .orElse(null);
        DeviceSkuLot lot = existing != null ? existing : createFallbackLot(deviceId, skuId, batchNo);
        if (existing == null) {
            // 新建批次：quantity 一次随 INSERT 写入，无覆盖窗口
            if (lot.getSlotId() == null || lot.getSlotId().isBlank()) {
                String primarySlot = resolvePrimarySlotCode(deviceId, skuId);
                if (primarySlot != null) {
                    lot.setSlotId(primarySlot);
                }
            }
            lot.setQuantity(quantity);
            lotRepository.save(lot);
        } else {
            lotRepository.increaseQuantityAtomic(lot.getLotId(), quantity);
            String targetStatus = DEPLETED.equals(lot.getStatus()) || BLOCKED.equals(lot.getStatus())
                    ? resolveLotStatus(lot)
                    : lot.getStatus();
            String slotPatch = (lot.getSlotId() == null || lot.getSlotId().isBlank())
                    ? resolvePrimarySlotCode(deviceId, skuId) : null;
            lotRepository.updateStatusAndSlotById(lot.getLotId(), targetStatus, slotPatch);
            lot.setStatus(targetStatus);
            if (slotPatch != null) {
                lot.setSlotId(slotPatch);
            }
        }
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
        String resolvedBatch = (command.batchNo() == null || command.batchNo().isBlank())
                ? "B-" + LocalDate.now(ScheduleZones.ZONE) + "-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase()
                : command.batchNo().trim();

        // 批 3b 第十八刀 2/2：入库不再有「按货道建账」维度——lot 行身份是 (device, sku, batch)。
        // 生产 BOUND 柜原本就落这条路径（slot_id 恒 NULL），实测也不会合并任何现存行（12 行/0 带 slot/0 重复组）。
        DeviceSkuLot lot = lotRepository
                .findByDeviceIdAndSkuIdAndBatchNo(deviceId, skuId, resolvedBatch).orElse(null);
        if (lot == null) {
            lot = new DeviceSkuLot();
            lot.setLotId("L" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase());
            lot.setDeviceId(deviceId);
            lot.setSkuId(skuId);
            lot.setBatchNo(resolvedBatch);
            lot.setProductionDate(command.productionDate());
            lot.setExpiryDate(command.expiryDate());
            lot.setStatus(ON_SALE);
            lot.setQuantity(0);
        }
        lot.setQuantity(lot.getQuantity() + command.quantity());
        lot.setStatus(resolveLotStatus(lot));
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
                    "STOCKTAKE-" + LocalDate.now(), LocalDate.now(), expiry, delta,
                    new LotMovementRef(REPLENISH, refId, operatorId)));
        } else {
            deductFefoForRef(deviceId, skuId, -delta, "ADJ", "STOCKTAKE", refId, operatorId);
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
                    line.getBatchNo(), production, expiry, line.getQuantity(),
                    new LotMovementRef(REPLENISH, refId, operatorId)));
        } else if (PULL_OFF.equalsIgnoreCase(line.getLineType())) {
            doPullOff(deviceId, line.getSkuId(), line.getBatchNo(), line.getQuantity(), operatorId, refId);
        } else {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unknown lineType=" + line.getLineType());
        }
    }

    @Transactional
    public void syncAggregateInventory(String deviceId, String skuId) {
        materializeUntrackedInventory(deviceId, skuId);
        DeviceSkuInventoryId id = new DeviceSkuInventoryId(deviceId, skuId);
        // R18-5：算术 UPDATE 现在递增 version，实体 save 的 CAS 不再对算术写失明——
        // 409 说明并发方刚动过台账（本方或他方实例），重读-重算-再写让其收敛。
        // 汇总值恒为「当前批次和」，重试不放大差异；3 轮仍冲突则按 save 原口径抛 409 回滚。
        for (int attempt = 0; ; attempt++) {
            int sum = lotRepository.sumSellableQuantity(deviceId, skuId);
            DeviceSkuInventory inv = inventoryRepository.findById(id).orElseGet(() -> {
                DeviceSkuInventory created = new DeviceSkuInventory();
                created.setId(id);
                created.setCapacity(20);
                created.setLowThreshold(2);
                return created;
            });
            inv.setQuantity(sum);
            try {
                inventoryRepository.save(inv);
                return;
            } catch (ResponseStatusException e) {
                if (!HttpStatus.CONFLICT.equals(e.getStatusCode()) || attempt >= 2) {
                    throw e;
                }
                log.info("aggregate sync CAS conflict, retrying device={} sku={} attempt={}",
                        deviceId, skuId, attempt + 1);
            }
        }
    }

    /**
     * 首次为该「柜 + SKU」开批次账时，把**无批次支撑的裸库存**并成一条批次。
     *
     * <p>成因：{@code syncAggregateInventory} 用批次汇总<b>覆盖</b>账面数量。若某 SKU 的
     * 账面有货却从未写过批次（历史补货/演示数据/早期无批次口径留下的行），第一次上架
     * 写批次后汇总只剩新批次，裸库存被静默吞掉——实测 100013 账面 2、上架 2，结果仍是 2。
     *
     * <p>判据刻意取「该柜该 SKU 一条批次都没有」：这是唯一能证明账面数量全部无批次支撑
     * 的状态。已有批次时不动，避免把「部分有批次」的账外差异凭空补成批次（差异应走盘点）。
     *
     * <p>放在汇总入口而不是 {@code doAddRestock}，是为了同时盖住退货回库、盘盈、调拨入库
     * 等所有「先建批次再汇总」的路径，避免同一类缺陷换个入口复现。
     */
    private void materializeUntrackedInventory(String deviceId, String skuId) {
        if (!lotRepository.findByDeviceIdAndSkuIdOrderByExpiryDateAsc(deviceId, skuId).isEmpty()) {
            return;
        }
        int bare = inventoryRepository
                .findById(new DeviceSkuInventoryId(deviceId, skuId))
                .map(DeviceSkuInventory::getQuantity)
                .orElse(0);
        if (bare <= 0) {
            return;
        }
        DeviceSkuLot lot = createFallbackLot(deviceId, skuId, LEGACY_OPENING_BATCH);
        lot.setQuantity(bare);
        lotRepository.save(lot);
        log.warn("materialize untracked inventory device={} sku={} qty={} -> batch={}",
                deviceId, skuId, bare, LEGACY_OPENING_BATCH);
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
            // R18-2 收口：过期扫描不在设备锁内，save 是绝对覆盖会与并发扣减竞争
            // （用旧快照覆盖已扣量）——状态更新走只写 status/slot 的专用 UPDATE
            lotRepository.updateStatusAndSlotById(lot.getLotId(), BLOCKED, null);
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
            // R18-2 收口：同上，状态更新走专用 UPDATE 不碰 quantity
            lotRepository.updateStatusAndSlotById(lot.getLotId(), NEAR_EXPIRY, null);
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
                        // R18-2 收口：同上，状态更新走专用 UPDATE 不碰 quantity
                        lotRepository.updateStatusAndSlotById(lot.getLotId(), NEAR_EXPIRY, null);
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
        if (isNearExpiryByDate(lot.getExpiryDate(), nearDays, today)) {
            return NEAR_EXPIRY;
        }
        return ON_SALE;
    }

    /**
     * 按保质期日期判断是否临期（不依赖批次 status，避免扫描任务未跑时漏价）。
     * 已过期不算临期（不可售）。
     */
    public static boolean isNearExpiryByDate(LocalDate expiryDate, int nearExpiryDays, LocalDate today) {
        if (expiryDate == null || today == null) {
            return false;
        }
        if (expiryDate.isBefore(today)) {
            return false;
        }
        int nearDays = Math.max(0, nearExpiryDays);
        return !expiryDate.isAfter(today.plusDays(nearDays));
    }

    /**
     * 结算计价用：窥探 FEFO 首个可售批次（不扣减）。无批次账本或无可售批次时 empty。
     */
    @Transactional(readOnly = true)
    public Optional<DeviceSkuLot> peekFefoPrimarySellableLot(String deviceId, String skuId) {
        if (deviceId == null || deviceId.isBlank() || skuId == null || skuId.isBlank()) {
            return Optional.empty();
        }
        String dev = deviceId.trim();
        String sku = skuId.trim();
        if (!deviceUsesLotLedger(dev)) {
            return Optional.empty();
        }
        SkuCatalog catalog = skuCatalogRepository.findById(sku).orElse(null);
        int blockDays = catalog != null ? catalog.getBlockSaleDaysBeforeExpiry() : 0;
        LocalDate minExpiry = LocalDate.now().plusDays(blockDays);
        List<DeviceSkuLot> lots = lotRepository.findByDeviceIdAndSkuIdOrderByExpiryDateAsc(dev, sku);
        for (DeviceSkuLot lot : lots) {
            if (isSellable(lot, minExpiry)) {
                return Optional.of(lot);
            }
        }
        return Optional.empty();
    }

    /**
     * 清仓（滞销）判定：该设备该 SKU **可售批次中最早入库的那一批**的入库时间。
     *
     * <p>「可售」口径与 FEFO 窥探一致（复用 {@link #isSellable}）：有库存、未被封禁、
     * 且到期日在「今日 + SKU 的 block_sale_days_before_expiry」之后。无批次账本、
     * 无可售批次或入库时间为空时返回 empty —— 调用方据此判「不清仓」（fail-closed）。
     *
     * <p>刻意用 {@code created_at} 而不是 {@code expiry_date}：后者是 FEFO/临期的维度，
     * 「清仓」要判的是**货压了多久**（滞销），不是**还有多久过期**。
     */
    @Transactional(readOnly = true)
    public Optional<Instant> oldestSellableLotCreatedAt(String deviceId, String skuId) {
        if (deviceId == null || deviceId.isBlank() || skuId == null || skuId.isBlank()) {
            return Optional.empty();
        }
        String dev = deviceId.trim();
        String sku = skuId.trim();
        if (!deviceUsesLotLedger(dev)) {
            return Optional.empty();
        }
        SkuCatalog catalog = skuCatalogRepository.findById(sku).orElse(null);
        int blockDays = catalog != null ? catalog.getBlockSaleDaysBeforeExpiry() : 0;
        LocalDate minExpiry = LocalDate.now().plusDays(blockDays);
        return lotRepository.findByDeviceIdAndSkuIdOrderByCreatedAtAsc(dev, sku).stream()
                .filter(lot -> lot.getExpiryDate() != null)
                .filter(lot -> isSellable(lot, minExpiry))
                .map(DeviceSkuLot::getCreatedAt)
                .filter(Objects::nonNull)
                .findFirst();
    }

    /**
     * 入库至今的天数（把入库时刻按本地时区折算成日期后与 today 相减）。
     *
     * <p>同日或未来时间（时钟回拨、数据异常）一律返回 0 —— 宁可「不算滞销」，
     * 也不要因负天数把刚补的货误判成滞销清仓。
     */
    public static int stockAgeDays(Instant createdAt, LocalDate today) {
        if (createdAt == null || today == null) {
            return 0;
        }
        LocalDate stockedOn = createdAt.atZone(ScheduleZones.ZONE).toLocalDate();
        long days = ChronoUnit.DAYS.between(stockedOn, today);
        if (days <= 0) {
            return 0;
        }
        return days > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) days;
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
