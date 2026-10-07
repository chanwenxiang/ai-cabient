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

    public InventoryOpsService(InventoryLotService lotService,
                               DeviceValidationService deviceValidationService,
                               SkuCatalogMapper skuCatalogRepository,
                               DeviceSkuInventoryMapper inventoryRepository,
                               InventoryWriteOffMapper writeOffRepository,
                               MerchantOpsPolicyService opsPolicyService,
                               DistributedLockService distributedLockService) {
        this.lotService = lotService;
        this.deviceValidationService = deviceValidationService;
        this.skuCatalogRepository = skuCatalogRepository;
        this.inventoryRepository = inventoryRepository;
        this.writeOffRepository = writeOffRepository;
        this.opsPolicyService = opsPolicyService;
        this.distributedLockService = distributedLockService;
    }

    @Transactional
    public WriteOffDto writeOff(Long operatorId, WriteOffRequest request) {
        return runWithDeviceInventoryLock(request.deviceId(),
                () -> doWriteOff(operatorId, request));
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
        writeOffEntry.setSkuId(request.skuId());
        writeOffEntry.setBatchNo(request.batchNo());
        writeOffEntry.setQuantity(request.quantity());
        writeOffEntry.setReason(reason);
        writeOffEntry.setCostCents(costCents);
        writeOffEntry.setOperatorId(operatorId);

        // ---- V311：责任归属 + 原因分类 ----
        // 🔴 分类**优先用调用方显式传入的**，没传才从 reason 推断。
        //    为什么不直接用 reason 当分类：现有白名单只有 4 个值
        //    （EXPIRED/DAMAGED/THEFT/OTHER），其中 OTHER 是「兜底桶」——
        //    大量损耗都会落进它，**无法区分过期/破损/丢失**，责任判定就废了。
        //    reasonCategory 补的是更细的分类维度（详见 WriteOffReasonCategory）。
        String category = request.reasonCategory() != null && !request.reasonCategory().isBlank()
                ? request.reasonCategory()
                : WriteOffReasonCategory.infer(reason);
        writeOffEntry.setReasonCategory(category);
        writeOffEntry.setResponsibleParty(blankToNull(request.responsibleParty()));
        writeOffEntry.setClaimNo(blankToNull(request.claimNo()));
        // 🔴 索赔额**不得为负**：负数索赔会让供应商对账凭空减少应付。
        if (request.claimAmountCents() != null && request.claimAmountCents() < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "claimAmountCents must be >= 0");
        }
        writeOffEntry.setClaimAmountCents(request.claimAmountCents());

        writeOffEntry = writeOffRepository.save(writeOffEntry);

        return new WriteOffDto(
                writeOffEntry.getWriteOffId(),
                writeOffEntry.getDeviceId(),
                writeOffEntry.getSkuId(),
                writeOffEntry.getBatchNo(),
                writeOffEntry.getQuantity(),
                writeOffEntry.getReason(),
                writeOffEntry.getReasonCategory(),
                writeOffEntry.getResponsibleParty(),
                writeOffEntry.getClaimNo(),
                writeOffEntry.getClaimAmountCents(),
                writeOffEntry.getCostCents(),
                writeOffEntry.getOperatorId(),
                writeOffEntry.getCreatedAt()
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
