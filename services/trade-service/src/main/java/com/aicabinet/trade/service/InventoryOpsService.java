package com.aicabinet.trade.service;

import com.aicabinet.common.dto.StocktakeAdjustRequest;
import com.aicabinet.common.dto.WriteOffDto;
import com.aicabinet.common.constants.WriteOffReasonCategory;
import com.aicabinet.common.dto.WriteOffRequest;
import com.aicabinet.trade.domain.DeviceSkuInventory;
import com.aicabinet.trade.domain.DeviceSkuInventoryId;
import com.aicabinet.trade.domain.InventoryWriteOff;
import com.aicabinet.trade.domain.SkuCatalog;
import com.aicabinet.trade.mapper.DeviceSkuInventoryMapper;
import com.aicabinet.trade.mapper.InventoryWriteOffMapper;
import com.aicabinet.trade.mapper.SkuCatalogMapper;
import com.aicabinet.trade.support.OptimisticLocking;
import com.aicabinet.trade.util.BizIds;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

@Service
public class InventoryOpsService {

    private static final Set<String> WRITE_OFF_REASONS = Set.of(
            "EXPIRED", "DAMAGED", "THEFT", "OTHER");

    private final InventoryLotService lotService;
    private final DeviceValidationService deviceValidationService;
    private final SkuCatalogMapper skuCatalogRepository;
    private final DeviceSkuInventoryMapper inventoryRepository;
    private final InventoryWriteOffMapper writeOffRepository;
    private final MerchantOpsPolicyService opsPolicyService;
    private final DistributedLockService distributedLockService;
    /** V313：仓库侧报损复用它扣减库存+ 留流水（内含防负库存校验，不重复实现）。 */
    private final WarehouseService warehouseService;

    public InventoryOpsService(InventoryLotService lotService,
                               DeviceValidationService deviceValidationService,
                               SkuCatalogMapper skuCatalogRepository,
                               DeviceSkuInventoryMapper inventoryRepository,
                               InventoryWriteOffMapper writeOffRepository,
                               MerchantOpsPolicyService opsPolicyService,
                               DistributedLockService distributedLockService,
                               WarehouseService warehouseService) {
        this.lotService = lotService;
        this.deviceValidationService = deviceValidationService;
        this.skuCatalogRepository = skuCatalogRepository;
        this.inventoryRepository = inventoryRepository;
        this.writeOffRepository = writeOffRepository;
        this.opsPolicyService = opsPolicyService;
        this.distributedLockService = distributedLockService;
        this.warehouseService = warehouseService;
    }

    /**
     * 报损入口（**位置无关**，V313）。
     *
     * <p>🔴 V313 前这里只有设备侧（`requireDevice(request.deviceId())`），
     * 仓库里的破损/过期/丢失**没有核销入口** —— 仓库侧盘点差异只能改账、
     * 无法记录「为什么损」。
     *
     * <p>**位置由 {@code request} 自带，二者必须恰好一个非空**：
     * <ul>
     *   <li>{@code deviceId != null} → 设备侧，走设备库存锁 + 批次核销（原行为）；</li>
     *   <li>{@code warehouseId != null} → 仓库侧，走仓库库存扣减 + 流水（新能力）。</li>
     * </ul>
     * 两边都空 = 不知道报损发生在哪（数据质量问题）；两边都有 = 同一笔损耗
     * 同时挂设备与仓库（重复记账）⇒ 两者都拒。
     * DB侧另有 {@code ck_write_off_location} 兜底（防绕过本服务直接写库）。
     */
    @Transactional
    public WriteOffDto writeOff(Long operatorId, WriteOffRequest request) {
        boolean deviceSide = isNotBlank(request.deviceId());
        boolean warehouseSide = isNotBlank(request.warehouseId());
        if (deviceSide == warehouseSide) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "deviceId 与 warehouseId 恰好填一个（当前 deviceId="
                            + request.deviceId() + ", warehouseId=" + request.warehouseId() + "）");
        }
        return deviceSide
                ? runWithDeviceInventoryLock(request.deviceId(), () -> doWriteOff(operatorId, request))
                : doWarehouseWriteOff(operatorId, request);
    }

    /**
     * 仓库侧报损（V313）。
     *
     * <p>复用 {@code WarehouseService.binStockChange} 的扣减与流水 ——
     * 它已有 {@code deductWarehouseStock} 内部的<b>防负库存</b>校验，
     * 自己再写一套只会引入第二份「扣减但不校验」的逻辑。
     */
    private WriteOffDto doWarehouseWriteOff(Long operatorId, WriteOffRequest request) {
        String warehouseId = request.warehouseId().trim();
        String skuId = request.skuId().trim();
        skuCatalogRepository.findById(skuId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "sku not found"));
        String reason = request.reason().trim().toUpperCase();
        if (!WRITE_OFF_REASONS.contains(reason)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid write-off reason");
        }
        Integer unitCost = skuCatalogRepository.findById(skuId)
                .map(SkuCatalog::getPurchaseCostCents)
                .orElse(null);
        int costCents = unitCost != null ? unitCost * request.quantity() : 0;

        String refId = BizIds.nextNumeric();
        // 扣减仓库库存（内部已防负库存），并留流水 refType=WRITE_OFF 便于追溯
        warehouseService.binStockChange(new WarehouseService.BinStockChangeCommand(
                warehouseId,
                new WarehouseService.LotSpec(skuId, request.batchNo(), null, null),
                -Math.abs(request.quantity()),
                operatorId,
                "WRITE_OFF", refId));

        InventoryWriteOff entry = new InventoryWriteOff();
        entry.setDeviceId(null); // 仓库侧无设备
        entry.setWarehouseId(warehouseId);
        entry.setSkuId(skuId);
        entry.setBatchNo(request.batchNo());
        entry.setQuantity(request.quantity());
        entry.setReason(reason);
        entry.setCostCents(costCents);
        entry.setOperatorId(operatorId);
        applyV311LiabilityFields(entry, request, reason);
        entry = writeOffRepository.save(entry);
        return toDto(entry);
    }

    private static boolean isNotBlank(String s) {
        return s != null && !s.isBlank();
    }

    private WriteOffDto doWriteOff(Long operatorId, WriteOffRequest request) {
        deviceValidationService.requireDevice(request.deviceId());
        skuCatalogRepository.findById(request.skuId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "sku not found"));
        String reason = request.reason().trim().toUpperCase();
        if (!WRITE_OFF_REASONS.contains(reason)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid write-off reason");
        }

        String refId = BizIds.nextNumeric();
        lotService.writeOffLots(request.deviceId(), request.skuId(), request.batchNo(),
                request.quantity(), operatorId, refId);

        SkuCatalog sku = skuCatalogRepository.findById(request.skuId()).orElseThrow();
        Integer unitCost = sku.getPurchaseCostCents();
        int costCents = unitCost != null ? unitCost * request.quantity() : 0;

        InventoryWriteOff writeOffEntry = new InventoryWriteOff();
        writeOffEntry.setDeviceId(request.deviceId());
        writeOffEntry.setWarehouseId(null); // 设备侧无仓库（ck_write_off_location 要求恰好一边非空）
        writeOffEntry.setSkuId(request.skuId());
        writeOffEntry.setBatchNo(request.batchNo());
        writeOffEntry.setQuantity(request.quantity());
        writeOffEntry.setReason(reason);
        writeOffEntry.setCostCents(costCents);
        writeOffEntry.setOperatorId(operatorId);

        applyV311LiabilityFields(writeOffEntry, request, reason);
        writeOffEntry = writeOffRepository.save(writeOffEntry);
        return toDto(writeOffEntry);
    }

    /**
     * V311 责任归属字段落库（设备侧与仓库侧**共用**）。
     *
     * <p>🔴 抽成共用方法是刻意的：报损的两条链路（设备/仓库）**必须落一样的归因字段**。
     * 若各自写一份，将来改「分类推断规则」只改了一边，
     * 就会出现「设备侧有分类、仓库侧没有」的数据分裂 ——
     * 正是本项目反复出现过的「同一能力只补一半」。
     */
    private void applyV311LiabilityFields(InventoryWriteOff entry, WriteOffRequest request, String reason) {
        // 分类**优先用调用方显式传入的**，没传才从 reason 推断。
        // 为什么不直接用 reason 当分类：现有白名单只有 4 个值
        // （EXPIRED/DAMAGED/THEFT/OTHER），其中 OTHER 是「兜底桶」——
        // 大量损耗都会落进它，**无法区分过期/破损/丢失**，责任判定就废了。
        // reasonCategory 补的是更细的分类维度（详见 WriteOffReasonCategory）。
        String category = isNotBlank(request.reasonCategory())
                ? request.reasonCategory()
                : WriteOffReasonCategory.infer(reason);
        entry.setReasonCategory(category);
        entry.setResponsibleParty(blankToNull(request.responsibleParty()));
        entry.setClaimNo(blankToNull(request.claimNo()));
        // 🔴 索赔额**不得为负**：负数索赔会让供应商对账凭空减少应付。
        if (request.claimAmountCents() != null && request.claimAmountCents() < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "claimAmountCents must be >= 0");
        }
        entry.setClaimAmountCents(request.claimAmountCents());
    }

    private WriteOffDto toDto(InventoryWriteOff entry) {
        return new WriteOffDto(
                entry.getWriteOffId(),
                entry.getDeviceId(),
                entry.getWarehouseId(),
                entry.getSkuId(),
                entry.getBatchNo(),
                entry.getQuantity(),
                entry.getReason(),
                entry.getReasonCategory(),
                entry.getResponsibleParty(),
                entry.getClaimNo(),
                entry.getClaimAmountCents(),
                entry.getCostCents(),
                entry.getOperatorId(),
                entry.getCreatedAt()
        );
    }

    /** 空白串归一为 {@code null} —— 空串不是「值」，是「没填」。 */
    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    @Transactional
    public DeviceSkuInventory stocktakeAdjust(Long operatorId, StocktakeAdjustRequest request) {
        return runWithDeviceInventoryLock(request.deviceId(),
                () -> doStocktakeAdjust(operatorId, request));
    }

    private DeviceSkuInventory doStocktakeAdjust(Long operatorId, StocktakeAdjustRequest request) {
        deviceValidationService.requireDevice(request.deviceId());
        // P1-2：lot 账本设备禁止整机盲调——直写汇总表会被下次 syncAggregateInventory
        // 按 lot 汇总冲掉（静默丢账），且绕过货道真源；统一走货道盘点
        // POST /devices/{id}/slots/stocktake（货道矩阵）。非 lot 设备保留旧路径兼容。
        if (lotService.deviceUsesLotLedger(request.deviceId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "该柜机已启用批次账本，整机盘点已停用；请使用货道盘点");
        }
        opsPolicyService.requirePhotoEvidence(request.deviceId(), true, request.photoEvidenceUrl());
        skuCatalogRepository.findById(request.skuId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "sku not found"));

        DeviceSkuInventoryId id = new DeviceSkuInventoryId(request.deviceId(), request.skuId());
        Optional<DeviceSkuInventory> existing = inventoryRepository.findById(id);
        DeviceSkuInventory inv = existing.orElseGet(() -> {
            DeviceSkuInventory created = new DeviceSkuInventory();
            created.setId(id);
            created.setCapacity(20);
            created.setLowThreshold(2);
            created.setQuantity(0);
            return created;
        });
        if (existing.isPresent()) {
            OptimisticLocking.requireMatchingExpectedVersion(request.expectedVersion(), inv.getVersion());
        }

        // 有批次账本时以可售为基准；即使 delta=0 也 sync，避免汇总表虚库存残留
        int current = lotService.deviceUsesLotLedger(request.deviceId())
                ? lotService.sellableQuantity(request.deviceId(), request.skuId())
                : inv.getQuantity();
        int delta = request.countedQuantity() - current;
        if (delta == 0) {
            if (lotService.deviceUsesLotLedger(request.deviceId())) {
                lotService.syncAggregateInventory(request.deviceId(), request.skuId());
                return inventoryRepository.findById(id).orElse(inv);
            }
            return inv;
        }

        String refId = BizIds.nextNumeric();
        lotService.stocktakeAdjust(request.deviceId(), request.skuId(),
                request.countedQuantity(), operatorId, refId);

        return inventoryRepository.findById(id).orElseThrow();
    }

    private <T> T runWithDeviceInventoryLock(String deviceId, Supplier<T> action) {
        String lockKey = InventoryService.deviceLockKey(deviceId);
        if (!distributedLockService.tryLock(lockKey, 60, 5)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "库存处理中，请稍后重试");
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
}
