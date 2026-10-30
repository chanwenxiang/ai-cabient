package com.aicabinet.trade.service;
import com.aicabinet.common.constants.CabinetConstants;

import com.aicabinet.common.constants.StocktakeDiffReason;
import com.aicabinet.common.dto.*;
import com.aicabinet.trade.domain.*;
import com.aicabinet.trade.mapper.*;
import com.aicabinet.common.util.PhoneMask;
import com.aicabinet.trade.support.ApiMessages;
import com.aicabinet.trade.support.SkuStockLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class WarehouseService {
    private static final Logger log = LoggerFactory.getLogger(WarehouseService.class);
    /** 出库单备注里最多列举的半满足条目数，超出只报计数——避免长清单淹没备注。 */
    private static final int SHORTFALL_NOTE_ITEMS = 10;
    private static final String QUANTITY_MUST_BE_POSITIVE = "quantity must be positive";
    private static final String IN_TRANSIT = "IN_TRANSIT";
    private static final String STATUS_CANCELLED = "CANCELLED";
    private static final String WAREHOUSE = "warehouse";
    private static final String RECEIVED = "RECEIVED";
    private static final String SHIPPED = "SHIPPED";
    private static final String PARTIAL = "PARTIAL";
    private static final String PICKED = "PICKED";
    private static final String READY = "READY";
    /**
     * 负责人变更审计（CB-070 第 4 点，2026-10-27）。targetType 用大写 {@code WAREHOUSE} 而非表名小写，
     * 是为了与通用数据管理通道对齐——那条通道写 {@code table.toUpperCase()}（AdminDataManageService#updateRow），
     * **同一条列**两条通道必须能在审计页里被同一个对象筛子捞出来。
     */
    private static final String AUDIT_ACTION_MANAGER = "WAREHOUSE_MANAGER";
    private static final String AUDIT_TARGET_WAREHOUSE = "WAREHOUSE";


    private final WarehouseMapper warehouseRepository;
    private final WarehouseInventoryMapper inventoryRepository;
    private final WarehouseInboundMapper inboundRepository;
    private final WarehouseInboundLineMapper inboundLineRepository;
    private final WarehouseOutboundMapper outboundRepository;
    private final WarehouseOutboundLineMapper outboundLineRepository;
    private final WarehouseMovementMapper movementRepository;
    private final DeviceSkuInventoryMapper deviceInventoryRepository;
    private final ReplenishmentTaskMapper taskRepository;
    private final ReplenishmentRouteMapper routeRepository;
    private final SkuCatalogMapper skuCatalogRepository;
    private final DeviceSlotService deviceSlotService;
    private final SalesVelocityService salesVelocityService;
    private final InTransitService inTransitService;
    private final InventoryLotService inventoryLotService;
    private final DistributedLockService distributedLockService;
    private final DisplaySnapshotHelper displaySnapshotHelper;
    private final DeviceInfoMapper deviceInfoRepository;
    private final WarehouseSupplierIdService warehouseSupplierIdService;
    private final UserInfoMapper userInfoRepository;
    private final PermissionService permissionService;
    private final MerchantScopeService merchantScopeService;
    private final AdminAuditService auditService;
    private final WarehouseManagerMapper warehouseManagerRepository;
    private final WarehouseService self;

    public WarehouseService(WarehouseMapper warehouseRepository,
                            WarehouseInventoryMapper inventoryRepository,
                            WarehouseInboundMapper inboundRepository,
                            WarehouseInboundLineMapper inboundLineMapper,
                            WarehouseOutboundMapper outboundRepository,
                            WarehouseOutboundLineMapper outboundLineRepository,
                            WarehouseMovementMapper movementRepository,
                            DeviceSkuInventoryMapper deviceInventoryRepository,
                            ReplenishmentTaskMapper taskRepository,
                            ReplenishmentRouteMapper routeRepository,
                            SkuCatalogMapper skuCatalogRepository,
                            DeviceSlotService deviceSlotService,
                            SalesVelocityService salesVelocityService,
                            InTransitService inTransitService,
                            InventoryLotService inventoryLotService,
                            DistributedLockService distributedLockService,
                            DisplaySnapshotHelper displaySnapshotHelper,
                            DeviceInfoMapper deviceInfoRepository,
                            WarehouseSupplierIdService warehouseSupplierIdService,
                            UserInfoMapper userInfoRepository,
                            PermissionService permissionService,
                            MerchantScopeService merchantScopeService,
                            AdminAuditService auditService,
                            WarehouseManagerMapper warehouseManagerRepository,
                            @Lazy WarehouseService self) {
        this.warehouseRepository = warehouseRepository;
        this.inventoryRepository = inventoryRepository;
        this.inboundRepository = inboundRepository;
        this.inboundLineRepository = inboundLineMapper;
        this.outboundRepository = outboundRepository;
        this.outboundLineRepository = outboundLineRepository;
        this.movementRepository = movementRepository;
        this.deviceInventoryRepository = deviceInventoryRepository;
        this.taskRepository = taskRepository;
        this.routeRepository = routeRepository;
        this.skuCatalogRepository = skuCatalogRepository;
        this.deviceSlotService = deviceSlotService;
        this.salesVelocityService = salesVelocityService;
        this.inTransitService = inTransitService;
        this.inventoryLotService = inventoryLotService;
        this.distributedLockService = distributedLockService;
        this.displaySnapshotHelper = displaySnapshotHelper;
        this.deviceInfoRepository = deviceInfoRepository;
        this.warehouseSupplierIdService = warehouseSupplierIdService;
        this.userInfoRepository = userInfoRepository;
        this.permissionService = permissionService;
        this.merchantScopeService = merchantScopeService;
        this.auditService = auditService;
        this.warehouseManagerRepository = warehouseManagerRepository;
        this.self = self;
    }

    @Transactional(readOnly = true)
    public List<WarehouseDto> listWarehouses() {
        return self.listWarehousesPage(null, 0, 500).items();
    }

    @Transactional(readOnly = true)
    public PageResult<WarehouseDto> listWarehousesPage(String keyword, int page, int size) {
        int p = Math.max(page, 0);
        int s = Math.min(Math.max(size, 1), 500);
        var result = warehouseRepository.searchPage(keyword, p, s);
        List<WarehouseDto> items = result.getRecords().stream().map(this::toWarehouseDto).toList();
        return new PageResult<>(items, p, s, result.getTotal());
    }

    @Transactional(readOnly = true)
    public List<WarehouseInventoryDto> listInventory(String warehouseId) {
        String wh = resolveWarehouseId(warehouseId);
        return inventoryRepository.findByWarehouseIdOrderByExpiryDateAsc(wh).stream()
                .filter(i -> i.getQuantity() > 0)
                .map(this::toInventoryDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public PageResult<WarehouseInventoryDto> listInventoryPage(
            String warehouseId, String keyword, int page, int size) {
        String wh = resolveWarehouseId(warehouseId);
        int p = Math.max(page, 0);
        int s = Math.min(Math.max(size, 1), 100);
        var result = inventoryRepository.searchPage(wh, keyword, p, s);
        List<WarehouseInventoryDto> items = result.getRecords().stream()
                .map(this::toInventoryDto)
                .toList();
        return new PageResult<>(items, p, s, result.getTotal());
    }

    /**
     * V320 仓库侧近效期预警（缺口 #6）。
     *
     * <p>🔴 <b>为什么不物化「预警任务表」</b>：设备侧的 `PullOffTask` 需要物化，
     * 是因为它要**驱动「下架」这个动作**；仓库侧只是「提醒哪些批次快到期」，
     * **没有动作要驱动**。物化反而会引入「过期了但任务还开着」的清理问题。
     *
     * <p>✅ 数据与索引都现成：`warehouse_inventory.expiry_date` 有值，
     * 且有索引 {@code idx_wh_inv_expiry (warehouse_id, expiry_date)}。
     *
     * @param daysAhead 提前多少天算近效期；默认 30
     */
    @Transactional(readOnly = true)
    public PageResult<WarehouseExpiryAlertDto> listExpiryAlertsPage(
            String warehouseId, Integer daysAhead, int page, int size) {
        int days = WarehouseExpiryAlert.clampDaysAhead(daysAhead);
        int p = Math.max(page, 0);
        int s = Math.min(Math.max(size, 1), 100);
        var result = inventoryRepository.findExpiringPage(warehouseId, days, p, s);
        java.time.LocalDate today = java.time.LocalDate.now();
        List<WarehouseExpiryAlertDto> items = result.getRecords().stream()
                .map(i -> toExpiryAlertDto(i, today))
                .toList();
        return new PageResult<>(items, p, s, result.getTotal());
    }

    /**
     * 剩余天数与紧急度交给 {@link WarehouseExpiryAlert}——
     * 🔴 刻意不写在这里：写成私有方法的话，测试只能「抄一份」，
     * 而抄一份的测试改实现不会变红（假测试）。
     */
    private WarehouseExpiryAlertDto toExpiryAlertDto(WarehouseInventory i, java.time.LocalDate today) {
        java.time.LocalDate expiry = i.getExpiryDate();
        int days = WarehouseExpiryAlert.daysRemaining(today, expiry);
        return new WarehouseExpiryAlertDto(
                i.getInventoryId(), i.getWarehouseId(), i.getSkuId(), i.getBatchNo(),
                expiry, i.getQuantity(), days,
                WarehouseExpiryAlert.isExpired(days),
                WarehouseExpiryAlert.urgency(days));
    }

    @Transactional(readOnly = true)
    public List<WarehouseMovementDto> listMovements(String warehouseId) {
        String wh = resolveWarehouseId(warehouseId);
        return movementRepository.findTop100ByWarehouseIdOrderByCreatedAtDesc(wh).stream()
                .map(this::toMovementDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public PageResult<WarehouseMovementDto> listMovementsPage(
            String warehouseId, String keyword, int page, int size) {
        String wh = resolveWarehouseId(warehouseId);
        int p = Math.max(page, 0);
        int s = Math.min(Math.max(size, 1), 100);
        var result = movementRepository.searchPage(wh, keyword, p, s);
        List<WarehouseMovementDto> items = result.getRecords().stream()
                .map(this::toMovementDto)
                .toList();
        return new PageResult<>(items, p, s, result.getTotal());
    }

    @Transactional
    public WarehouseInboundRequest inbound(Long operatorId, WarehouseInboundRequest request) {
        String wh = resolveWarehouseId(request.warehouseId());
        warehouseRepository.findById(wh).orElseThrow(() -> notFound(WAREHOUSE));
        // 审计 P2-8（复核修正）：与出库侧/P2-5 同宽严——无论显式指定还是默认兜底，
        // 手工入库一律禁止落无主仓（货进无主仓后 resolveOutboundWarehouseId 拒绝出库，
        // 库存搁浅）。首版只拦「空白兜底」，显式传无主仓仍可入库。
        requireManagedOutboundWarehouse(wh);
        if (request.lines() == null || request.lines().isEmpty()) {
            throw badRequest("lines required");
        }
        WarehouseInbound inbound = new WarehouseInbound();
        inbound.setWarehouseId(wh);
        inbound.setRefNo(request.refNo());
        inbound.setNotes(request.notes());
        inbound.setOperatorId(operatorId);
        inbound = inboundRepository.save(inbound);

        for (WarehouseInboundLineDto dto : request.lines()) {
            validateInboundLine(dto);
            WarehouseInboundLine line = new WarehouseInboundLine();
            line.setInboundId(inbound.getInboundId());
            line.setSkuId(dto.skuId());
            line.setBatchNo(dto.batchNo());
            line.setProductionDate(dto.productionDate());
            line.setExpiryDate(dto.expiryDate());
            line.setQuantity(dto.quantity());
            inboundLineRepository.save(line);
            addWarehouseStock(wh, dto.skuId(), dto.batchNo(), dto.productionDate(), dto.expiryDate(), dto.quantity());
            recordWarehouseMovement(wh, dto.skuId(), dto.batchNo(),
                    new MovementSpec("MANUAL_INBOUND", dto.quantity(), "WAREHOUSE_INBOUND",
                            String.valueOf(inbound.getInboundId()), operatorId));
        }
        return request;
    }

    @Transactional
    public void receivePurchaseStock(PurchaseReceiveCommand command) {
        if (command.qty() <= 0) {
            throw badRequest(QUANTITY_MUST_BE_POSITIVE);
        }
        String wh = resolveWarehouseId(command.warehouseId());
        warehouseRepository.findById(wh).orElseThrow(() -> notFound(WAREHOUSE));
        // 审计批次4：治理收口到库存原语层（上游 ProcurementService 已挡，此处纵深）——
        // 采购入库不允许落无主仓（与 inbound/P2-8 同宽严）。
        requireManagedOutboundWarehouse(wh);
        LotSpec lot = command.lot();
        String refType = command.refType();
        String refId = command.refId();
        WarehouseInbound inbound = new WarehouseInbound();
        inbound.setWarehouseId(wh);
        inbound.setRefNo(refType + "-" + refId);
        inbound.setNotes("purchase receive");
        inbound.setOperatorId(command.operatorId());
        if ("PURCHASE_ORDER".equals(refType)) {
            inbound.setPurchaseOrderId(parseLongOrNull(refId));
        }
        inbound = inboundRepository.save(inbound);

        WarehouseInboundLine line = new WarehouseInboundLine();
        line.setInboundId(inbound.getInboundId());
        line.setSkuId(lot.skuId());
        line.setBatchNo(lot.batchNo());
        line.setProductionDate(lot.productionDate());
        line.setExpiryDate(lot.expiryDate());
        line.setQuantity(command.qty());
        line.setUnitCostCents(command.unitCostCents());
        inboundLineRepository.save(line);

        addWarehouseStock(wh, lot.skuId(), lot.batchNo(), lot.productionDate(), lot.expiryDate(), command.qty());
        recordWarehouseMovement(wh, lot.skuId(), lot.batchNo(),
                new MovementSpec("PURCHASE_RECEIVE", command.qty(), refType, refId, command.operatorId()));
    }

    public record PurchaseReceiveCommand(String warehouseId, LotSpec lot, int qty, int unitCostCents,
                                           Long operatorId, String refType, String refId) {}

    @Transactional
    public void returnPurchaseStock(String warehouseId, String skuId, String batchNo, int qty,
                                    Long operatorId, String refType, String refId) {
        if (qty <= 0) {
            throw badRequest(QUANTITY_MUST_BE_POSITIVE);
        }
        String wh = resolveWarehouseId(warehouseId);
        warehouseRepository.findById(wh).orElseThrow(() -> notFound(WAREHOUSE));
        // 审计批次4：采购退货扣仓同受管仓校验（与 receivePurchaseStock 对称）。
        requireManagedOutboundWarehouse(wh);
        deductWarehouseStock(wh, skuId, batchNo, qty);
        recordWarehouseMovement(wh, skuId, batchNo,
                new MovementSpec("PURCHASE_RETURN", -qty, refType, refId, operatorId));
    }

    /**
     * 盘点差异调整：把账面库存直接对齐到实盘数量，并记录库存流水。
     *
     * <p>差量基准取<strong>过账时刻的当前账面</strong>（行锁内读取），不用建单时的快照
     * {@code bookQty}：建单→过账窗口内的收货/发运若按快照差量二次应用会静默错账，
     * 且 STOCKTAKE 流水恒自洽、「期初+Σ流水=余额」巡检无法发现（审计 P1-3）。</p>
     */
    @Transactional
    public void adjustStocktake(StocktakeAdjustCommand command) {
        String wh = resolveWarehouseId(command.warehouseId());
        warehouseRepository.findById(wh).orElseThrow(() -> notFound(WAREHOUSE));
        LotSpec lot = command.lot();
        runWithStockLock(wh, lot.skuId(), lot.batchNo(), () -> {
            WarehouseInventory inv = inventoryRepository
                    .findByWarehouseIdAndSkuIdAndBatchNoForUpdate(wh, lot.skuId(), lot.batchNo())
                    .orElseGet(() -> {
                        WarehouseInventory n = new WarehouseInventory();
                        n.setWarehouseId(wh);
                        n.setSkuId(lot.skuId());
                        n.setBatchNo(lot.batchNo());
                        n.setProductionDate(lot.productionDate());
                        n.setExpiryDate(lot.expiryDate());
                        return n;
                    });
            int current = Math.max(0, inv.getQuantity());
            int delta = command.countedQty() - current;
            if (delta == 0) {
                return null;
            }
            // 🔴 V312：分类必须与差异方向一致。
            //   把盘盈分类（MISENTRY_GAIN等）填到盘亏行上，统计会把「货多了」
            //   算成「货少了」—— 方向直接反掉，且这种错误在报表上很难看出来。
            //   校验放在改库存**之前**：错了就不该产生任何库存变动。
            if (!StocktakeDiffReason.matchesDirection(delta, command.diffReason())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "盘点差异原因与差异方向不匹配（delta=" + delta
                                + " 表示" + (delta < 0 ? "盘亏" : "盘盈")
                                + "，但分类是 " + command.diffReason() + "）");
            }
            inv.setQuantity(current + delta);
            if (inv.getProductionDate() == null) inv.setProductionDate(lot.productionDate());
            if (inv.getExpiryDate() == null) inv.setExpiryDate(lot.expiryDate());
            inventoryRepository.save(inv);
            recordWarehouseMovement(wh, lot.skuId(), lot.batchNo(),
                    new MovementSpec("STOCKTAKE", delta, "STOCKTAKE", String.valueOf(command.stocktakeId()), command.operatorId()));
            return null;
        });
    }

    public record LotSpec(String skuId, String batchNo, LocalDate productionDate, LocalDate expiryDate) {}

    public record StocktakeAdjustCommand(String warehouseId, LotSpec lot, int countedQty,
                                         Long operatorId, Long stocktakeId,
                                         /** V312盘点差异原因分类；null = 未分类（不强制填）。 */
                                         String diffReason) {}

    /** 货位操作同步仓库总库存：入库/出库调整仓库账面并记录流水；移库 delta=0 仅留痕。 */
    @Transactional
    public void binStockChange(BinStockChangeCommand command) {
        if (command.deltaQty() == 0) {
            return;
        }
        String wh = resolveWarehouseId(command.warehouseId());
        warehouseRepository.findById(wh).orElseThrow(() -> notFound(WAREHOUSE));
        LotSpec lot = command.lot();
        if (command.deltaQty() > 0) {
            addWarehouseStock(wh, lot.skuId(), lot.batchNo(), lot.productionDate(), lot.expiryDate(), command.deltaQty());
        } else {
            deductWarehouseStock(wh, lot.skuId(), lot.batchNo(), -command.deltaQty());
        }
        recordWarehouseMovement(wh, lot.skuId(), lot.batchNo(),
                new MovementSpec("BIN_STOCK", command.deltaQty(), command.refType(), command.refId(), command.operatorId()));
    }

    public record BinStockChangeCommand(String warehouseId, LotSpec lot, int deltaQty,
                                        Long operatorId, String refType, String refId) {}

    @Transactional(readOnly = true)
    public List<ReplenishmentSuggestDto> suggestForDevice(String deviceId) {
        return self.suggestForDevice(deviceId, false);
    }

    /**
     * @param fillToPar true=规划出库：按货道补到 PAR；false=运营建议：仅 minLevel/ROP 触发
     */
    @Transactional(readOnly = true)
    public List<ReplenishmentSuggestDto> suggestForDevice(String deviceId, boolean fillToPar) {
        String dev = deviceId.trim();
        Map<String, Integer> inTransitBySku = inTransitService.qtyBySkuForDevice(dev);
        Map<String, SalesVelocityService.SkuVelocity> velocityBySku = salesVelocityService.velocityBySku(deviceId);
        List<ReplenishmentSuggestDto> fromSlots = buildSuggestionsFromSlots(dev, deviceId, fillToPar, inTransitBySku, velocityBySku);
        if (!fromSlots.isEmpty()) {
            return fromSlots;
        }
        if (fillToPar) {
            // 货道已满 PAR / 在途抵消后，再回退常规低库存/ROP 建议
            return self.suggestForDevice(deviceId, false);
        }
        List<ReplenishmentSuggestDto> lowStock = suggestFromLowStock(dev, inTransitBySku, velocityBySku,
                inventoryLotService.lotLedgerSkuIds(dev));
        if (!lowStock.isEmpty()) {
            return lowStock;
        }
        return suggestFromRop(dev, inTransitBySku, velocityBySku, inventoryLotService.lotLedgerSkuIds(dev));
    }

    private List<ReplenishmentSuggestDto> buildSuggestionsFromSlots(
            String dev, String deviceId, boolean fillToPar,
            Map<String, Integer> inTransitBySku,
            Map<String, SalesVelocityService.SkuVelocity> velocityBySku) {
        Map<String, Integer> qtyBySku = new LinkedHashMap<>();
        Map<String, Integer> bookBySku = new LinkedHashMap<>();
        Map<String, Integer> parBySku = new LinkedHashMap<>();
        Map<String, String> reasonBySku = new LinkedHashMap<>();
        List<SlotReplenishmentSuggestDto> slots = fillToPar
                ? deviceSlotService.suggestSlotsForOutboundFill(deviceId)
                : deviceSlotService.suggestSlotsForDevice(deviceId);
        for (SlotReplenishmentSuggestDto slot : slots) {
            if (slot.suggestQty() <= 0) {
                continue;
            }
            qtyBySku.merge(slot.skuId(), slot.suggestQty(), Integer::sum);
            bookBySku.merge(slot.skuId(), slot.bookQty(), Integer::sum);
            parBySku.merge(slot.skuId(), slot.parLevel(), Integer::sum);
            reasonBySku.putIfAbsent(slot.skuId(), slot.suggestReason());
        }
        if (qtyBySku.isEmpty()) {
            return List.of();
        }
        // 台账对齐 + 容量钳制（2026-10-27 用户实测「目标库存 8 顶穿容量 4」）：
        // currentQty 用与设备详情同一条口径（已建账看账本、未建账回台账——货道账面常为 0，
        // 因为补货落账不绑货道）；建议量一律 ≤ 台账容量 − 台账账面，货道 PAR 填得再大也不越界。
        Map<String, DeviceSkuInventory> invBySku = deviceInventoryRepository.findByIdDeviceId(dev).stream()
                .collect(Collectors.toMap(i -> i.getId().getSkuId(), i -> i, (a, b) -> a));
        java.util.Set<String> lotSkus = inventoryLotService.lotLedgerSkuIds(dev);
        return qtyBySku.entrySet().stream()
                .map(e -> {
                    String skuId = e.getKey();
                    SalesVelocityService.SkuVelocity velocity = velocityBySku.getOrDefault(
                            skuId, new SalesVelocityService.SkuVelocity(0, 0, 0, 0));
                    DeviceSkuInventory inv = invBySku.get(skuId);
                    int book = inv == null
                            ? bookBySku.getOrDefault(skuId, 0)
                            : bookQtyForSuggest(dev, skuId, inv.getQuantity(), lotSkus);
                    int par = parBySku.getOrDefault(skuId, 0);
                    int capacity = inv == null ? 0 : inv.getCapacity();
                    int inTransit = inTransitBySku.getOrDefault(skuId, 0);
                    int rawSuggest = capSuggestByInventoryCapacity(
                            Math.max(0, e.getValue() - inTransit), book, capacity);
                    return new ReplenishmentSuggestDto(
                            dev, skuId, book, capacity > 0 ? capacity : par, 0,
                            rawSuggest, inTransit,
                            velocity.soldQty7d(), velocity.soldQty14d(), velocity.ropPoint(),
                            reasonBySku.getOrDefault(skuId, "PAR"));
                })
                .filter(s -> s.suggestQty() > 0)
                .toList();
    }

    private List<ReplenishmentSuggestDto> suggestFromLowStock(
            String dev, Map<String, Integer> inTransitBySku,
            Map<String, SalesVelocityService.SkuVelocity> velocityBySku,
            java.util.Set<String> lotLedgerSkus) {
        return deviceInventoryRepository.findByIdDeviceId(dev).stream()
                .map(i -> {
                    String skuId = i.getId().getSkuId();
                    int book = bookQtyForSuggest(dev, skuId, i.getQuantity(), lotLedgerSkus);
                    return Map.entry(i, book);
                })
                .filter(e -> SkuStockLevel.of(e.getValue(), e.getKey().getLowThreshold()).warning())
                .map(e -> {
                    DeviceSkuInventory i = e.getKey();
                    int book = e.getValue();
                    String skuId = i.getId().getSkuId();
                    SalesVelocityService.SkuVelocity velocity = velocityBySku.getOrDefault(
                            skuId, new SalesVelocityService.SkuVelocity(0, 0, 0, 0));
                    int inTransit = inTransitBySku.getOrDefault(skuId, 0);
                    int rawSuggest = Math.max(0, i.getCapacity() - book);
                    // 有货道陈列时按可补容量截断，避免出库远超货道
                    rawSuggest = capSuggestBySlotHeadroom(dev, skuId, rawSuggest);
                    return new ReplenishmentSuggestDto(
                            i.getId().getDeviceId(),
                            skuId,
                            book,
                            i.getCapacity(),
                            i.getLowThreshold(),
                            Math.max(0, rawSuggest - inTransit), inTransit,
                            velocity.soldQty7d(), velocity.soldQty14d(), velocity.ropPoint(),
                            "LOW_STOCK");
                })
                .filter(s -> s.suggestQty() > 0)
                .toList();
    }

    private List<ReplenishmentSuggestDto> suggestFromRop(
            String dev, Map<String, Integer> inTransitBySku,
            Map<String, SalesVelocityService.SkuVelocity> velocityBySku,
            java.util.Set<String> lotLedgerSkus) {
        return velocityBySku.entrySet().stream()
                .filter(e -> e.getValue().ropPoint() > 0)
                .map(e -> {
                    String skuId = e.getKey();
                    SalesVelocityService.SkuVelocity velocity = e.getValue();
                    DeviceSkuInventory inv = deviceInventoryRepository.findByIdDeviceId(dev).stream()
                            .filter(i -> skuId.equals(i.getId().getSkuId()))
                            .findFirst().orElse(null);
                    int book = inv == null ? 0
                            : bookQtyForSuggest(dev, skuId, inv.getQuantity(), lotLedgerSkus);
                    if (book > velocity.ropPoint()) {
                        return null;
                    }
                    int target = Math.max(velocity.ropPoint() * 2, book + 1);
                    int inTransit = inTransitBySku.getOrDefault(skuId, 0);
                    int rawSuggest = Math.max(0, target - book);
                    rawSuggest = capSuggestBySlotHeadroom(dev, skuId, rawSuggest);
                    rawSuggest = capSuggestByInventoryCapacity(
                            rawSuggest, book, inv == null ? 0 : inv.getCapacity());
                    if (rawSuggest <= 0) {
                        return null;
                    }
                    return new ReplenishmentSuggestDto(
                            dev, skuId, book, target, velocity.ropPoint(),
                            Math.max(0, rawSuggest - inTransit), inTransit,
                            velocity.soldQty7d(), velocity.soldQty14d(), velocity.ropPoint(),
                            "ROP");
                })
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    /**
     * 建议侧账面口径，与 {@code DeviceSlotService.toInventoryDto} 显示口径同一条规则：
     * 已建批次账的 SKU 以账本可售为准（含卖光/过期=0），未建账的回落 device_sku_inventory 账面。
     * 🔴 不能按设备级 {@code deviceUsesLotLedger} 一刀切——会把「从未进过账本」的 SKU
     * 全读成 0（2026-10-27 实测：雪碧账面 2 被读成 0 ⇒ 建议 4−0=4 顶穿容量 4）。
     */
    private int bookQtyForSuggest(String deviceId, String skuId, int inventoryQty,
                                  java.util.Set<String> lotLedgerSkus) {
        if (lotLedgerSkus != null && lotLedgerSkus.contains(skuId)) {
            return inventoryLotService.sellableQuantity(deviceId, skuId);
        }
        return inventoryQty;
    }

    /**
     * 已绑定货道时，建议量不超过货道可补总容量。
     * 铁律 78：开门柜（判据=将邑绑定）的库存真值只在台账，`device_slot` 对它是死数据——
     * 柜上可能残留历史/演示货道行，此时 hasSkuSlots 仍为真，若不按柜型分流就会拿死数据封顶建议量。
     */
    private int capSuggestBySlotHeadroom(String deviceId, String skuId, int rawSuggest) {
        if (rawSuggest <= 0 || deviceSlotService.isDoorCabinet(deviceId)
                || !deviceSlotService.hasSkuSlots(deviceId, skuId)) {
            return rawSuggest;
        }
        return Math.min(rawSuggest, deviceSlotService.totalHeadroomForSku(deviceId, skuId));
    }

    /**
     * 无货道机型（开门柜）的容量钳制：建议量不得超过 台账容量 − 账面（2026-10-27 用户实测
     * 「已有 2 个、最多放 4 个，建议不该是 4」——销量外推不得顶穿物理容量）。
     * capacity≤0（未设容量）不钳制，维持原值。
     */
    private int capSuggestByInventoryCapacity(int rawSuggest, int book, int capacity) {
        if (capacity <= 0) {
            return rawSuggest;
        }
        return Math.min(rawSuggest, Math.max(0, capacity - book));
    }

    @Transactional
    public WarehouseOutboundDto createOutboundFromLines(Long routeId, String deviceId, Long assigneeUserId,
                                                        java.util.Map<String, Integer> skuQty, String warehouseId) {
        Long outboundId = self.tryCreateOutboundFromLines(routeId, deviceId, assigneeUserId, skuQty, warehouseId);
        if (outboundId == null) {
            throw badRequest("outbound lines required");
        }
        return self.getOutbound(outboundId);
    }

    /**
     * 按 SKU 要货量尝试生成出库单；仓库无可用库存时返回 null（不抛错、不污染外层事务）。
     * 部分库存时尽力分配并保留出库单（requireFull=false），避免 409 导致外层接单事务 rollback-only。
     */
    @Transactional
    public Long tryCreateOutboundFromLines(Long routeId, String deviceId, Long assigneeUserId,
                                           java.util.Map<String, Integer> skuQty, String warehouseId) {
        return tryCreateOutboundFromLines(routeId, deviceId, assigneeUserId, skuQty, warehouseId,
                "merchant replenishment request");
    }

    /** 同上，可指定出库单备注（补货员自主发起补货用，与要货接单区分来源）。 */
    @Transactional
    public Long tryCreateOutboundFromLines(Long routeId, String deviceId, Long assigneeUserId,
                                           java.util.Map<String, Integer> skuQty, String warehouseId,
                                           String notes) {
        if (skuQty == null || skuQty.isEmpty()) {
            return null;
        }
        String wh = resolveOutboundWarehouseId(warehouseId, deviceId, List.of());
        WarehouseOutbound outbound = new WarehouseOutbound();
        outbound.setWarehouseId(wh);
        outbound.setRouteId(routeId);
        outbound.setAssigneeUserId(assigneeUserId);
        outbound.setStatus(CabinetConstants.PROMOTION_STATUS_DRAFT);
        outbound.setNotes(notes != null && !notes.isBlank() ? notes : "merchant replenishment request");
        outbound = outboundRepository.save(outbound);
        int allocatedLines = 0;
        for (var entry : skuQty.entrySet()) {
            if (entry.getValue() == null || entry.getValue() <= 0) {
                continue;
            }
            allocatedLines += allocateFefoToOutbound(
                    outbound.getOutboundId(), wh, deviceId, entry.getKey(), entry.getValue(), false).lines();
        }
        if (allocatedLines <= 0) {
            outboundRepository.deleteById(outbound.getOutboundId());
            return null;
        }
        return outbound.getOutboundId();
    }

    @Transactional
    public WarehouseOutboundDto createOutboundForRoute(Long routeId, String warehouseId, Long assigneeUserId) {
        clearEmptyDraftOutboundForRoute(routeId);
        List<ReplenishmentTask> tasks = taskRepository.findByRouteId(routeId);
        if (tasks.isEmpty()) {
            throw badRequest("route has no tasks");
        }
        String wh = resolveOutboundWarehouseId(warehouseId, null, tasks);

        WarehouseOutbound outbound = new WarehouseOutbound();
        outbound.setWarehouseId(wh);
        outbound.setRouteId(routeId);
        outbound.setAssigneeUserId(assigneeUserId);
        outbound.setStatus(CabinetConstants.PROMOTION_STATUS_DRAFT);
        outbound.setNotes("auto from route " + routeId);
        outbound = outboundRepository.save(outbound);

        RouteOutboundAllocation allocation = allocateRouteOutboundLines(outbound, wh, tasks);
        if (allocation.allocatedLines() <= 0) {
            unlinkTasksFromOutbound(tasks, outbound.getOutboundId());
            outboundRepository.deleteById(outbound.getOutboundId());
            throw badRequest(allocation.hadSuggestQty()
                    ? ApiMessages.REPLENISHMENT_WAREHOUSE_STOCK_INSUFFICIENT
                    : ApiMessages.REPLENISHMENT_NO_GAP);
        }
        annotateHalfFulfilledOutbound(outbound, allocation);
        return self.getOutbound(outbound.getOutboundId());
    }

    private void clearEmptyDraftOutboundForRoute(Long routeId) {
        Optional<WarehouseOutbound> existing = outboundRepository.findByRouteId(routeId);
        if (existing.isEmpty()) {
            return;
        }
        WarehouseOutbound o = existing.get();
        boolean emptyDraft = CabinetConstants.PROMOTION_STATUS_DRAFT.equals(o.getStatus())
                && outboundLineRepository.findByOutboundIdOrderByLineIdAsc(o.getOutboundId()).isEmpty();
        if (!emptyDraft) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "outbound already exists for route");
        }
        for (ReplenishmentTask task : taskRepository.findByRouteId(routeId)) {
            if (o.getOutboundId().equals(task.getOutboundId())) {
                task.setOutboundId(null);
                taskRepository.save(task);
                // M01：updateById 忽略 null 列，outbound_id 清列须 wrapper 显式 set(null)
                taskRepository.update(null, com.baomidou.mybatisplus.core.toolkit.Wrappers.<ReplenishmentTask>lambdaUpdate()
                        .eq(ReplenishmentTask::getTaskId, task.getTaskId())
                        .set(ReplenishmentTask::getOutboundId, null));
            }
        }
        outboundRepository.deleteById(o.getOutboundId());
    }

    private record RouteOutboundAllocation(int allocatedLines, boolean hadSuggestQty, List<String> shortfall) {}

    private RouteOutboundAllocation allocateRouteOutboundLines(WarehouseOutbound outbound, String warehouseId,
                                                               List<ReplenishmentTask> tasks) {
        int allocatedLines = 0;
        boolean hadSuggestQty = false;
        // 同 SKU 可分布在路线多台柜上，按 SKU 累计「建议量 / 实际分配量」再判定半满足
        Map<String, int[]> accBySku = new LinkedHashMap<>();
        for (ReplenishmentTask task : tasks) {
            List<ReplenishmentSuggestDto> suggestions = self.suggestForDevice(task.getDeviceId(), true);
            for (ReplenishmentSuggestDto s : suggestions) {
                if (s.suggestQty() <= 0) {
                    continue;
                }
                hadSuggestQty = true;
                FefoAllocResult alloc = allocateFefoToOutbound(
                        outbound.getOutboundId(), warehouseId, task.getDeviceId(), s.skuId(), s.suggestQty(), false);
                allocatedLines += alloc.lines();
                int[] acc = accBySku.computeIfAbsent(s.skuId(), k -> new int[2]);
                acc[0] += s.suggestQty();
                acc[1] += alloc.qty();
            }
            task.setOutboundId(outbound.getOutboundId());
            taskRepository.save(task);
        }
        List<String> shortfall = accBySku.entrySet().stream()
                .filter(e -> e.getValue()[1] < e.getValue()[0])
                .map(e -> e.getKey() + " 需" + e.getValue()[0] + "/实" + e.getValue()[1])
                .toList();
        return new RouteOutboundAllocation(allocatedLines, hadSuggestQty, shortfall);
    }

    /**
     * FS-G7-3：库存只够覆盖部分建议量时，旧行为是「有行即成功」，缺口对运营完全静默。
     * 这里只把缺口显性化（出库单备注 + 日志），不改 FEFO 分配、不阻断建单。
     */
    private void annotateHalfFulfilledOutbound(WarehouseOutbound outbound, RouteOutboundAllocation allocation) {
        if (allocation.shortfall().isEmpty()) {
            return;
        }
        log.warn("[replenishment] route={} outbound={} 补货建议半满足 {} 项：{}",
                outbound.getRouteId(), outbound.getOutboundId(), allocation.shortfall().size(),
                String.join("；", allocation.shortfall()));
        outbound.setNotes(outbound.getNotes() + "；" + formatShortfall(allocation.shortfall()));
        outboundRepository.save(outbound);
    }

    static String formatShortfall(List<String> shortfall) {
        String listed = shortfall.stream().limit(SHORTFALL_NOTE_ITEMS).collect(Collectors.joining("；"));
        int omitted = shortfall.size() - SHORTFALL_NOTE_ITEMS;
        return "半满足 " + shortfall.size() + " 项：" + listed
                + (omitted > 0 ? "（余 " + omitted + " 项见服务日志）" : "");
    }

    private void unlinkTasksFromOutbound(List<ReplenishmentTask> tasks, Long outboundId) {
        for (ReplenishmentTask task : tasks) {
            if (outboundId.equals(task.getOutboundId())) {
                task.setOutboundId(null);
                taskRepository.save(task);
                // M01：updateById 忽略 null 列，outbound_id 清列须 wrapper 显式 set(null)
                taskRepository.update(null, com.baomidou.mybatisplus.core.toolkit.Wrappers.<ReplenishmentTask>lambdaUpdate()
                        .eq(ReplenishmentTask::getTaskId, task.getTaskId())
                        .set(ReplenishmentTask::getOutboundId, null));
            }
        }
    }

    @Transactional(readOnly = true)
    public List<WarehouseOutboundDto> listOutbounds() {
        return listOutbounds(null, null);
    }

    /** D28-B3：服务端 CSV 导出按仓/状态过滤（null＝不过滤）；行组装沿用 getOutbound（含明细）。 */
    @Transactional(readOnly = true)
    public List<WarehouseOutboundDto> listOutbounds(String warehouseId, String status) {
        return outboundRepository.findAllFiltered(warehouseId, status).stream()
                .map(o -> self.getOutbound(o.getOutboundId()))
                .toList();
    }

    @Transactional(readOnly = true)
    public PageResult<WarehouseOutboundDto> listOutboundsPage(String keyword, String warehouseId, int page, int size) {
        return listOutboundsPage(keyword, warehouseId, null, page, size);
    }

    /** D28-B5：status 可选（运营台出库页签状态筛选走服务端）。 */
    @Transactional(readOnly = true)
    public PageResult<WarehouseOutboundDto> listOutboundsPage(
            String keyword, String warehouseId, String status, int page, int size) {
        int p = Math.max(page, 0);
        int s = Math.min(Math.max(size, 1), 100);
        var result = outboundRepository.searchPage(keyword, warehouseId, status, p, s);
        List<WarehouseOutboundDto> items = result.getRecords().stream()
                .map(o -> self.getOutbound(o.getOutboundId()))
                .toList();
        return new PageResult<>(items, p, s, result.getTotal());
    }

    @Transactional(readOnly = true)
    public List<WarehouseOutboundLine> outboundLinesForDevice(Long outboundId, String deviceId) {
        return outboundLineRepository.findByOutboundIdAndDeviceIdOrderByLineIdAsc(outboundId, deviceId);
    }

    @Transactional(readOnly = true)
    public WarehouseOutboundDto getOutbound(Long outboundId) {
        WarehouseOutbound outbound = outboundRepository.findById(outboundId)
                .orElseThrow(() -> notFound("outbound"));
        List<WarehouseOutboundLineDto> lines = outboundLineRepository.findByOutboundIdOrderByLineIdAsc(outboundId)
                .stream().map(this::toOutboundLineDto).toList();
        return new WarehouseOutboundDto(
                outbound.getOutboundId(), outbound.getWarehouseId(), outbound.getRouteId(),
                outbound.getStatus(), outbound.getAssigneeUserId(), outbound.getNotes(),
                outbound.getCreatedAt(), outbound.getShippedAt(), lines
        );
    }

    @Transactional
    public WarehouseOutboundDto markPicked(Long outboundId) {
        return runWithOutboundLock(outboundId, () -> doMarkPicked(outboundId));
    }

    private WarehouseOutboundDto doMarkPicked(Long outboundId) {
        WarehouseOutbound outbound = requireOutboundForUpdate(outboundId);
        if (SHIPPED.equals(outbound.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "already shipped");
        }
        if (STATUS_CANCELLED.equals(outbound.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "出库单已作废，无法拣货");
        }
        List<WarehouseOutboundLine> lines = outboundLineRepository.findByOutboundIdOrderByLineIdAsc(outboundId);
        if (lines.isEmpty()) {
            throw badRequest("出库单无明细，无法拣货（可能库存不足未生成行项）");
        }
        // OBS-017：拣货时即按货道余量截断；全部无余量则拒绝，避免落入 PICKED 后发运 400
        lines = clampLinesToSlotHeadroom(lines);
        if (lines.isEmpty()) {
            throw badRequest("货道已满，无可拣货数量；请先腾出货道或作废出库单");
        }
        lines.forEach(line -> {
                    line.setPicked(true);
                    line.setHandoverStatus(READY);
                    outboundLineRepository.save(line);
                });
        outbound.setStatus(PICKED);
        outbound.setHandoverStatus(READY);
        outboundRepository.save(outbound);
        return self.getOutbound(outboundId);
    }

    @Transactional
    public WarehouseOutboundDto shipOutbound(Long operatorId, Long outboundId) {
        return runWithOutboundLock(outboundId, () -> doShipOutbound(operatorId, outboundId));
    }

    private WarehouseOutboundDto doShipOutbound(Long operatorId, Long outboundId) {
        WarehouseOutbound outbound = requireOutboundForUpdate(outboundId);
        if (SHIPPED.equals(outbound.getStatus())) {
            return self.getOutbound(outboundId);
        }
        if (STATUS_CANCELLED.equals(outbound.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "出库单已作废，无法领货出库");
        }
        List<WarehouseOutboundLine> lines = outboundLineRepository.findByOutboundIdOrderByLineIdAsc(outboundId);
        if (lines.isEmpty()) {
            throw badRequest("出库单无明细，无法领货出库（可能库存不足未生成行项）");
        }
        if (lines.stream().allMatch(line -> STATUS_CANCELLED.equals(line.getHandoverStatus()))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "出库单行项已全部作废，无法领货出库");
        }
        // 已作废行不参与发运扣库
        lines = lines.stream()
                .filter(line -> !STATUS_CANCELLED.equals(line.getHandoverStatus()))
                .toList();
        if (lines.stream().anyMatch(line -> !line.isPicked())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "outbound must be picked before ship");
        }
        // 发运前再按当前货道余量截断，避免规划后柜机库存变化导致超发
        lines = clampLinesToSlotHeadroom(lines);
        if (lines.isEmpty()) {
            throw badRequest("货道已满，无可领货数量");
        }
        // 审计 P2-7：扣库前按 (skuId,batchNo) 全局排序——两个发运单行序相反且共用批次时，
        // 行级锁按同一顺序获取，消除 PG 死锁窗口（被 PG 中止的一方 500）
        lines = lines.stream()
                .sorted(java.util.Comparator.comparing(WarehouseOutboundLine::getSkuId)
                        .thenComparing(WarehouseOutboundLine::getBatchNo,
                                java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())))
                .toList();
        for (WarehouseOutboundLine line : lines) {
            deductWarehouseStock(outbound.getWarehouseId(), line.getSkuId(), line.getBatchNo(), line.getQuantity());
            recordWarehouseMovement(outbound.getWarehouseId(), line.getSkuId(), line.getBatchNo(),
                    new MovementSpec("OUTBOUND_SHIP", -line.getQuantity(), "WAREHOUSE_OUTBOUND",
                            String.valueOf(outboundId), operatorId));
            line.setHandoverStatus(IN_TRANSIT);
            outboundLineRepository.save(line);
        }
        outbound.setStatus(SHIPPED);
        outbound.setShippedAt(Instant.now());
        outbound.setHandoverStatus(IN_TRANSIT);
        outbound.setHandoverOperatorId(operatorId);
        outbound.setHandedOverAt(Instant.now());
        outboundRepository.save(outbound);
        inTransitService.recordFromOutbound(outboundId, lines);
        return self.getOutbound(outboundId);
    }

    @Transactional(readOnly = true)
    public boolean hasOutboundLinesForDevice(Long outboundId, String deviceId) {
        if (outboundId == null || deviceId == null || deviceId.isBlank()) {
            return false;
        }
        return !outboundLineRepository
                .findByOutboundIdAndDeviceIdOrderByLineIdAsc(outboundId, deviceId.trim())
                .isEmpty();
    }

    /**
     * 整单作废未签收出库：DRAFT/PICKED 直接作废；SHIPPED 未交接则回仓并取消在途。
     * 任一设备行已 RECEIVED/PARTIAL 则拒绝（避免半签收脏账）。
     */
    @Transactional
    public WarehouseOutboundDto cancelUnreceivedOutbound(Long outboundId, Long operatorId) {
        return runWithOutboundLock(outboundId, () -> doCancelUnreceivedOutbound(outboundId, operatorId));
    }

    private WarehouseOutboundDto doCancelUnreceivedOutbound(Long outboundId, Long operatorId) {
        WarehouseOutbound outbound = requireOutboundForUpdate(outboundId);
        if (STATUS_CANCELLED.equals(outbound.getStatus())) {
            return self.getOutbound(outboundId);
        }
        List<WarehouseOutboundLine> allLines =
                outboundLineRepository.findByOutboundIdOrderByLineIdAsc(outboundId);
        if (hasHandedOverOutboundLines(allLines)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ApiMessages.WAREHOUSE_OUTBOUND_CANCEL_BLOCKED);
        }
        java.util.LinkedHashSet<String> devices = distinctOutboundDevices(allLines);
        if (devices.isEmpty()) {
            outbound.setStatus(STATUS_CANCELLED);
            outbound.setHandoverStatus(STATUS_CANCELLED);
            outboundRepository.save(outbound);
            return self.getOutbound(outboundId);
        }
        for (String device : devices) {
            doCancelUnreceivedOutboundForDevice(outboundId, device, operatorId);
        }
        return self.getOutbound(outboundId);
    }

    /**
     * 取消未签收出库占用：DRAFT/PICKED 直接作废；SHIPPED 未交接则回仓并取消在途。
     * 已 RECEIVED/PARTIAL 交接的设备行拒绝取消。
     */
    @Transactional
    public void cancelUnreceivedOutboundForDevice(Long outboundId, String deviceId, Long operatorId) {
        if (outboundId == null || deviceId == null || deviceId.isBlank()) {
            return;
        }
        runWithOutboundLock(outboundId, () -> {
            doCancelUnreceivedOutboundForDevice(outboundId, deviceId, operatorId);
            return null;
        });
    }

    private void doCancelUnreceivedOutboundForDevice(Long outboundId, String deviceId, Long operatorId) {
        WarehouseOutbound outbound = requireOutboundForUpdate(outboundId);
        if (STATUS_CANCELLED.equals(outbound.getStatus())) {
            return;
        }
        String device = deviceId.trim();
        List<WarehouseOutboundLine> deviceLines =
                outboundLineRepository.findByOutboundIdAndDeviceIdOrderByLineIdAsc(outboundId, device);
        assertDeviceLinesNotHandedOver(deviceLines);
        if (SHIPPED.equals(outbound.getStatus())) {
            cancelShippedDeviceLines(outbound, deviceLines, device, outboundId, operatorId);
        } else {
            markDeviceLinesCancelled(deviceLines);
        }
        maybeFinalizeOutboundCancellation(outbound, outboundId, deviceLines);
    }

    private static void assertDeviceLinesNotHandedOver(List<WarehouseOutboundLine> deviceLines) {
        boolean handedOver = deviceLines.stream().anyMatch(line -> {
            String hs = line.getHandoverStatus();
            return RECEIVED.equals(hs) || PARTIAL.equals(hs);
        });
        if (handedOver) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ApiMessages.REPLENISHMENT_CANCEL_NOT_EMPTY);
        }
    }

    private void cancelShippedDeviceLines(WarehouseOutbound outbound, List<WarehouseOutboundLine> deviceLines,
                                          String device, Long outboundId, Long operatorId) {
        // 审计批次4：与发运侧（doShipOutbound）同构——回仓加库存前按 (skuId,batchNo) 全局
        // 排序，两个取消单行序相反且共用批次时按同序取锁，消除 PG 死锁窗口。
        List<WarehouseOutboundLine> orderedLines = deviceLines.stream()
                .sorted(java.util.Comparator.comparing(WarehouseOutboundLine::getSkuId)
                        .thenComparing(WarehouseOutboundLine::getBatchNo,
                                java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())))
                .toList();
        for (WarehouseOutboundLine line : orderedLines) {
            if (line.getQuantity() > 0) {
                addWarehouseStock(outbound.getWarehouseId(), line.getSkuId(), line.getBatchNo(),
                        null, line.getExpiryDate(), line.getQuantity());
                recordWarehouseMovement(outbound.getWarehouseId(), line.getSkuId(), line.getBatchNo(),
                        new MovementSpec("OUTBOUND_CANCEL", line.getQuantity(), "WAREHOUSE_OUTBOUND",
                                String.valueOf(outboundId), operatorId));
            }
            line.setHandoverStatus(STATUS_CANCELLED);
            outboundLineRepository.save(line);
        }
        inTransitService.cancelOpenForDevice(outboundId, device);
    }

    private void markDeviceLinesCancelled(List<WarehouseOutboundLine> deviceLines) {
        for (WarehouseOutboundLine line : deviceLines) {
            line.setHandoverStatus(STATUS_CANCELLED);
            // 作废行同时清除拣货标记，避免 ship 前置校验被已作废行的 picked 状态放行
            line.setPicked(false);
            outboundLineRepository.save(line);
        }
    }

    private void maybeFinalizeOutboundCancellation(WarehouseOutbound outbound, Long outboundId,
                                                   List<WarehouseOutboundLine> deviceLines) {
        List<WarehouseOutboundLine> allLines = outboundLineRepository.findByOutboundIdOrderByLineIdAsc(outboundId);
        boolean allDeviceCancelled = allLines.stream().allMatch(line ->
                STATUS_CANCELLED.equals(line.getHandoverStatus())
                        || line.getDeviceId() == null
                        || line.getDeviceId().isBlank());
        boolean noOpenHandover = allLines.stream().noneMatch(line -> {
            String hs = line.getHandoverStatus();
            return hs == null || READY.equals(hs) || IN_TRANSIT.equals(hs);
        });
        if (allDeviceCancelled || noOpenHandover || deviceLines.size() == allLines.size()) {
            outbound.setStatus(STATUS_CANCELLED);
            outbound.setHandoverStatus(STATUS_CANCELLED);
            outboundRepository.save(outbound);
        }
    }

    /**
     * 批量安全收口历史脏出库（不硬删）：
     * <ul>
     *   <li>空 DRAFT/PICKED（无明细）</li>
     *   <li>终态路线上的未发运草稿/已拣货（含有明细）</li>
     *   <li>终态路线上的 SHIPPED 且未签收，且无 COMPLETED 关联任务（回仓 + 取消在途）</li>
     *   <li>PLANNED 路线上无任何任务挂靠的未发运草稿/已拣货（2026-10-29 补「PLANNED+无任务」判据）</li>
     * </ul>
     * COMPLETED 路线仍 SHIPPED 时：仅当关联任务全非 COMPLETED（多为已取消未签收）才收口，
     * 避免「任务已完成但交接态未回写」场景误回仓造成假满货。
     *
     * <p>本方法刻意<strong>不开外层事务</strong>：单条取消经 {@code self} 代理以 REQUIRED 自建事务，
     * skip（拿不到锁/竞态撞 Cancel-BLOCKED）只影响当条；若套外层事务，内层抛 RSE 会把共享事务
     * 标记 rollback-only，一条 skip 即令整批已成功的清理全部回滚并 500（审计 P1-4）。</p>
     */
    public WarehouseStaleCleanupResultDto cleanupStaleOutbounds(Long operatorId) {
        Map<Long, String> routeStatusById = routeRepository.findAllByOrderByRouteIdAsc().stream()
                .filter(r -> r.getRouteId() != null)
                .collect(Collectors.toMap(ReplenishmentRoute::getRouteId, ReplenishmentRoute::getStatus, (a, b) -> a));
        int cancelledEmptyDrafts = 0;
        int cancelledTerminalDrafts = 0;
        int cancelledOrphanShipped = 0;
        int cancelledPlannedNoTask = 0;
        int skipped = 0;
        List<Long> cancelledIds = new ArrayList<>();
        for (WarehouseOutbound outbound : outboundRepository.findAllByOrderByCreatedAtDesc()) {
            if (!STATUS_CANCELLED.equals(outbound.getStatus())) {
                String bucket = classifyStaleOutbound(outbound, routeStatusById);
                if (bucket != null) {
                    try {
                        self.cancelUnreceivedOutbound(outbound.getOutboundId(), operatorId);
                        cancelledIds.add(outbound.getOutboundId());
                        switch (bucket) {
                            case "empty" -> cancelledEmptyDrafts++;
                            case "terminal-draft" -> cancelledTerminalDrafts++;
                            case "orphan-shipped" -> cancelledOrphanShipped++;
                            case "planned-no-task" -> cancelledPlannedNoTask++;
                            default -> {
                                // classifyStaleOutbound only returns empty/terminal-draft/orphan-shipped/planned-no-task.
                            }
                        }
                    } catch (ResponseStatusException ex) {
                        skipped++;
                    }
                } else {
                    skipped++;
                }
            }
        }
        return new WarehouseStaleCleanupResultDto(
                cancelledEmptyDrafts, cancelledTerminalDrafts, cancelledOrphanShipped,
                cancelledPlannedNoTask, skipped, cancelledIds);
    }

    private String classifyStaleOutbound(WarehouseOutbound outbound, Map<Long, String> routeStatusById) {
        Long outboundId = outbound.getOutboundId();
        List<WarehouseOutboundLine> lines =
                outboundLineRepository.findByOutboundIdOrderByLineIdAsc(outboundId);
        boolean handedOver = lines.stream().anyMatch(line -> {
            String hs = line.getHandoverStatus();
            return RECEIVED.equals(hs) || PARTIAL.equals(hs);
        });
        if (handedOver) {
            return null;
        }
        String status = outbound.getStatus();
        boolean empty = lines.isEmpty();
        String routeStatus = outbound.getRouteId() == null ? null : routeStatusById.get(outbound.getRouteId());
        boolean terminalRoute = STATUS_CANCELLED.equals(routeStatus) || "COMPLETED".equals(routeStatus);
        if ((CabinetConstants.PROMOTION_STATUS_DRAFT.equals(status) || PICKED.equals(status)) && empty) {
            return "empty";
        }
        if ((CabinetConstants.PROMOTION_STATUS_DRAFT.equals(status) || PICKED.equals(status)) && terminalRoute) {
            return "terminal-draft";
        }
        if (SHIPPED.equals(status) && terminalRoute && !hasCompletedTaskLinked(outboundId)) {
            return "orphan-shipped";
        }
        // 「PLANNED+无任务」孤儿（2026-10-29 历史待定收口）：路线仍 PLANNED（未发运未终态，
        // 现有 terminal-draft/orphan-shipped 均只覆盖终态路线）但无任何任务挂靠。活跃链路
        // createOutboundForRoute 同事务校验 route has no tasks 并回写 task.outbound_id，
        // 此形态只能来自历史取消链路未级联的遗留；未发运未交接（前置 handedOver 已挡），
        // 可安全收口。routeStatus=PLANNED ⇒ routeId 非 null（map 只收非 null 路线），findByRouteId 安全。
        if ((CabinetConstants.PROMOTION_STATUS_DRAFT.equals(status) || PICKED.equals(status))
                && "PLANNED".equals(routeStatus)
                && taskRepository.findByOutboundId(outboundId).isEmpty()
                && taskRepository.findByRouteId(outbound.getRouteId()).isEmpty()) {
            return "planned-no-task";
        }
        return null;
    }

    /** 出库是否仍有已完成补货任务（已上架场景禁止自动回仓）。 */
    private boolean hasCompletedTaskLinked(Long outboundId) {
        if (outboundId == null) {
            return false;
        }
        return taskRepository.findByOutboundId(outboundId).stream()
                .anyMatch(task -> "COMPLETED".equals(task.getStatus()));
    }

    /**
     * 整单全量签收：现场没有逐项明细时的收口（如「建单即完成」直接按出库行入柜）。
     * 与旧实现的区别只在命名——不再用 {@code Integer.MAX_VALUE} 这种「假装无穷」的写法，
     * 因为一旦调用方手里其实有实收明细，走错这个方法就会把差额洗成 0（CB-074）。
     */
    @Transactional
    public void markDeviceHandoverReceivedFull(Long outboundId, String deviceId) {
        if (outboundId == null || deviceId == null || deviceId.isBlank()) {
            return;
        }
        runWithOutboundLock(outboundId, () -> {
            applyLineReceipt(outboundId, deviceId.trim(), null);
            return null;
        });
    }

    /**
     * 按 SKU 逐项签收（CB-074，2026-10-29）：实收量来自现场确认的补货行，不再拿领货量冒充。
     *
     * <p>每行实收 = {@code min(出库量, 该 SKU 尚未分配完的实收)}；差额写入
     * {@code warehouse_outbound_line.remainder_qty}（领而未上），行状态
     * 满量 {@code RECEIVED}、未满 {@code PARTIAL}。
     *
     * <p>🔴 差额**只记单据事实，不动任何库存**：出库流水已按领货量扣过仓（铁律 79），
     * 写回分仓等于凭空造库存，补进柜机违反铁律 78；退回分仓是第二张单据，须人拍板。
     *
     * @param receivedQtyBySku 本设备本次各 SKU 实收件数；出库行上没有的 SKU 不参与分摊
     */
    @Transactional
    public void markDeviceHandoverReceived(Long outboundId, String deviceId, Map<String, Integer> receivedQtyBySku) {
        if (outboundId == null || deviceId == null || deviceId.isBlank() || receivedQtyBySku == null) {
            return;
        }
        runWithOutboundLock(outboundId, () -> {
            applyLineReceipt(outboundId, deviceId.trim(), receivedQtyBySku);
            return null;
        });
    }

    /**
     * @param receivedQtyBySku null=全量签收；非 null=按 SKU 分摊实收，未覆盖的部分即差额
     */
    private void applyLineReceipt(Long outboundId, String deviceId, Map<String, Integer> receivedQtyBySku) {
        List<WarehouseOutboundLine> deviceLines =
                outboundLineRepository.findByOutboundIdAndDeviceIdOrderByLineIdAsc(outboundId, deviceId);
        if (deviceLines.isEmpty()) {
            return;
        }
        boolean full = receivedQtyBySku == null;
        Map<String, Integer> unallocated = new HashMap<>();
        if (!full) {
            for (Map.Entry<String, Integer> entry : receivedQtyBySku.entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null || entry.getValue() <= 0) {
                    continue;
                }
                unallocated.merge(entry.getKey().trim(), entry.getValue(), Integer::sum);
            }
        }
        for (WarehouseOutboundLine line : deviceLines) {
            int shipped = Math.max(line.getQuantity(), 0);
            int received = shipped;
            if (!full) {
                int left = unallocated.getOrDefault(skuKey(line.getSkuId()), 0);
                received = Math.min(left, shipped);
                unallocated.put(skuKey(line.getSkuId()), left - received);
            }
            line.setRemainderQty(shipped - received);
            line.setHandoverStatus(received >= shipped ? RECEIVED : PARTIAL);
            outboundLineRepository.save(line);
        }
        WarehouseOutbound outbound = requireOutboundForUpdate(outboundId);
        if (outbound == null) {
            return;
        }
        List<WarehouseOutboundLine> allLines = outboundLineRepository.findByOutboundIdOrderByLineIdAsc(outboundId);
        boolean allReceived = allLines.stream().allMatch(line -> RECEIVED.equals(line.getHandoverStatus()));
        if (allReceived) {
            outbound.setHandoverStatus(RECEIVED);
        } else {
            outbound.setHandoverStatus(PARTIAL);
        }
        outboundRepository.save(outbound);
    }

    private static String skuKey(String skuId) {
        return skuId == null ? "" : skuId.trim();
    }

    /**
     * @param requireFull true 时库存不足抛冲突；false 时尽力分配
     * @return 本次写入的出库行数与实际分配件数（qty 小于 needQty 即为半满足，由调用方决定是否显性化）
     */
    private FefoAllocResult allocateFefoToOutbound(Long outboundId, String warehouseId, String deviceId,
                                                   String skuId, int needQty, boolean requireFull) {
        int cappedNeed = needQty;
        // 铁律 78：开门柜不按 device_slot 封顶、也不逐货道拆行（残留货道是死数据），
        // 走 SKU 级单行分配（slotCode=null），与补货行恒无货道的口径一致。
        boolean boundSlots = deviceId != null && !deviceId.isBlank()
                && !deviceSlotService.isDoorCabinet(deviceId)
                && deviceSlotService.hasSkuSlots(deviceId, skuId);
        if (boundSlots) {
            int headroom = deviceSlotService.totalHeadroomForSku(deviceId, skuId);
            if (headroom <= 0) {
                return NO_ALLOC;
            }
            cappedNeed = Math.min(needQty, headroom);
        }
        if (cappedNeed <= 0) {
            return NO_ALLOC;
        }

        List<DeviceSlotService.SlotRestockAllocation> slotAllocs = boundSlots
                ? deviceSlotService.allocateRestockQuantity(deviceId, skuId, cappedNeed)
                : List.of();
        if (boundSlots && slotAllocs.isEmpty()) {
            return NO_ALLOC;
        }
        if (slotAllocs.isEmpty()) {
            return allocateFefoLots(outboundId, warehouseId, deviceId, skuId, cappedNeed, null, requireFull);
        }

        int lines = 0;
        int fulfilled = 0;
        for (DeviceSlotService.SlotRestockAllocation alloc : slotAllocs) {
            FefoAllocResult result = allocateFefoLots(
                    outboundId, warehouseId, deviceId, skuId, alloc.quantity(), alloc.slotCode(), false);
            lines += result.lines();
            fulfilled += result.qty();
            if (result.qty() < alloc.quantity()) {
                break;
            }
        }
        if (requireFull && fulfilled < cappedNeed) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "warehouse stock insufficient for outbound sku=" + skuId
                            + " need=" + needQty + " remaining=" + (needQty - fulfilled));
        }
        return new FefoAllocResult(lines, fulfilled);
    }

    private record FefoAllocResult(int lines, int qty) {}

    private static final FefoAllocResult NO_ALLOC = new FefoAllocResult(0, 0);

    /**
     * FEFO 写入出库行（可选货道）。
     */
    private FefoAllocResult allocateFefoLots(Long outboundId, String warehouseId, String deviceId,
                                             String skuId, int needQty, String slotId, boolean requireFull) {
        String lockKey = "warehouse:alloc:" + warehouseId + ":" + skuId;
        if (!distributedLockService.tryLock(lockKey, 60, 5)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "仓库分配繁忙，请稍后重试");
        }
        try {
            return doAllocateFefoLots(outboundId, warehouseId, deviceId, skuId, needQty, slotId, requireFull);
        } finally {
            distributedLockService.unlock(lockKey);
        }
    }

    private FefoAllocResult doAllocateFefoLots(Long outboundId, String warehouseId, String deviceId,
                                               String skuId, int needQty, String slotId, boolean requireFull) {
        int remaining = needQty;
        int lines = 0;
        List<WarehouseInventory> lots = inventoryRepository
                .findByWarehouseIdAndSkuIdOrderByExpiryDateAsc(warehouseId, skuId);
        for (WarehouseInventory lot : lots) {
            if (remaining <= 0) {
                break;
            }
            FefoLotTake take = tryTakeFromWarehouseLot(
                    outboundId, warehouseId, deviceId, skuId, slotId, lot, remaining);
            if (take != null) {
                remaining -= take.qty();
                lines += take.lines();
            }
        }
        if (remaining > 0 && requireFull) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "warehouse stock insufficient for outbound sku=" + skuId + " need=" + needQty + " remaining=" + remaining);
        }
        return new FefoAllocResult(lines, needQty - remaining);
    }

    private record FefoLotTake(int qty, int lines) {}

    private FefoLotTake tryTakeFromWarehouseLot(Long outboundId, String warehouseId, String deviceId,
                                                String skuId, String slotId, WarehouseInventory lot,
                                                int remaining) {
        if (lot.getQuantity() <= 0 || lot.getExpiryDate().isBefore(LocalDate.now())) {
            return null;
        }
        WarehouseInventory lockedLot = inventoryRepository
                .findByWarehouseIdAndSkuIdAndBatchNoForUpdate(warehouseId, skuId, lot.getBatchNo())
                .orElse(lot);
        int allocated = outboundLineRepository.sumAllocatedQty(warehouseId, skuId, lockedLot.getBatchNo());
        int available = Math.max(0, lockedLot.getQuantity() - allocated);
        if (available <= 0) {
            return null;
        }
        int take = Math.min(available, remaining);
        WarehouseOutboundLine line = new WarehouseOutboundLine();
        line.setOutboundId(outboundId);
        line.setDeviceId(deviceId);
        line.setSkuId(skuId);
        line.setBatchNo(lockedLot.getBatchNo());
        line.setExpiryDate(lockedLot.getExpiryDate());
        line.setQuantity(take);
        line.setPicked(false);
        if (slotId != null && !slotId.isBlank()) {
            line.setSlotId(slotId.trim().toUpperCase());
        }
        outboundLineRepository.save(line);
        return new FefoLotTake(take, 1);
    }

    /** 发运前按货道余量截断出库行；数量归零的行删除。 */
    private List<WarehouseOutboundLine> clampLinesToSlotHeadroom(List<WarehouseOutboundLine> lines) {
        Map<String, Integer> takenBySlot = new LinkedHashMap<>();
        Map<String, Integer> takenBySku = new LinkedHashMap<>();
        List<WarehouseOutboundLine> kept = new java.util.ArrayList<>();
        for (WarehouseOutboundLine line : lines) {
            if (!shouldClampLineToHeadroom(line)) {
                kept.add(line);
                continue;
            }
            int allow = computeLineAllowance(line, takenBySlot, takenBySku);
            WarehouseOutboundLine keptLine = applyAllowanceToLine(line, allow, takenBySlot, takenBySku);
            if (keptLine != null) {
                kept.add(keptLine);
            }
        }
        return kept;
    }

    private boolean shouldClampLineToHeadroom(WarehouseOutboundLine line) {
        String deviceId = line.getDeviceId();
        String skuId = line.getSkuId();
        // 铁律 78：开门柜出库行不得被残留货道截断（截断会静默减量甚至删行，台账与仓账就此错位）
        return deviceId != null && !deviceId.isBlank() && skuId != null
                && !deviceSlotService.isDoorCabinet(deviceId)
                && deviceSlotService.hasSkuSlots(deviceId, skuId);
    }

    private int computeLineAllowance(WarehouseOutboundLine line,
                                     Map<String, Integer> takenBySlot,
                                     Map<String, Integer> takenBySku) {
        int qty = line.getQuantity();
        if (qty <= 0) {
            return 0;
        }
        String slotId = line.getSlotId();
        if (slotId != null && !slotId.isBlank()) {
            String key = line.getDeviceId() + "|" + slotId.trim().toUpperCase();
            int headroom = deviceSlotService.headroomForSlot(line.getDeviceId(), slotId);
            int already = takenBySlot.getOrDefault(key, 0);
            return Math.max(0, headroom - already);
        }
        String key = line.getDeviceId() + "|" + line.getSkuId();
        int headroom = deviceSlotService.totalHeadroomForSku(line.getDeviceId(), line.getSkuId());
        int already = takenBySku.getOrDefault(key, 0);
        return Math.max(0, headroom - already);
    }

    private WarehouseOutboundLine applyAllowanceToLine(WarehouseOutboundLine line, int allow,
                                                       Map<String, Integer> takenBySlot,
                                                       Map<String, Integer> takenBySku) {
        if (line.getQuantity() <= 0) {
            outboundLineRepository.deleteById(line.getLineId());
            return null;
        }
        if (allow <= 0) {
            outboundLineRepository.deleteById(line.getLineId());
            return null;
        }
        reserveHeadroom(line, allow, takenBySlot, takenBySku);
        if (allow < line.getQuantity()) {
            line.setQuantity(allow);
            outboundLineRepository.save(line);
        }
        return line;
    }

    private void reserveHeadroom(WarehouseOutboundLine line, int allow,
                                 Map<String, Integer> takenBySlot, Map<String, Integer> takenBySku) {
        int qty = Math.min(line.getQuantity(), allow);
        String slotId = line.getSlotId();
        if (slotId != null && !slotId.isBlank()) {
            String key = line.getDeviceId() + "|" + slotId.trim().toUpperCase();
            takenBySlot.put(key, takenBySlot.getOrDefault(key, 0) + qty);
            return;
        }
        String key = line.getDeviceId() + "|" + line.getSkuId();
        takenBySku.put(key, takenBySku.getOrDefault(key, 0) + qty);
    }

    private void addWarehouseStock(String warehouseId, String skuId, String batchNo,
                                   LocalDate productionDate, LocalDate expiryDate, int qty) {
        runWithStockLock(warehouseId, skuId, batchNo, () -> {
            WarehouseInventory inv = inventoryRepository.findByWarehouseIdAndSkuIdAndBatchNoForUpdate(warehouseId, skuId, batchNo)
                    .orElseGet(() -> {
                        WarehouseInventory n = new WarehouseInventory();
                        n.setWarehouseId(warehouseId);
                        n.setSkuId(skuId);
                        n.setBatchNo(batchNo);
                        n.setProductionDate(productionDate);
                        n.setExpiryDate(expiryDate);
                        return n;
                    });
            inv.setQuantity(inv.getQuantity() + qty);
            if (productionDate != null) inv.setProductionDate(productionDate);
            if (expiryDate != null) inv.setExpiryDate(expiryDate);
            inventoryRepository.save(inv);
            return null;
        });
    }

    private void deductWarehouseStock(String warehouseId, String skuId, String batchNo, int qty) {
        runWithStockLock(warehouseId, skuId, batchNo, () -> {
            WarehouseInventory inv = inventoryRepository.findByWarehouseIdAndSkuIdAndBatchNoForUpdate(warehouseId, skuId, batchNo)
                    .orElseThrow(() -> badRequest("warehouse stock insufficient: " + skuId + "/" + batchNo));
            if (inv.getQuantity() < qty) {
                throw badRequest("warehouse stock insufficient: " + skuId + "/" + batchNo);
            }
            inv.setQuantity(inv.getQuantity() - qty);
            inventoryRepository.save(inv);
            return null;
        });
    }

    private void recordWarehouseMovement(String warehouseId, String skuId, String batchNo, MovementSpec movement) {
        WarehouseMovement entity = new WarehouseMovement();
        entity.setWarehouseId(warehouseId);
        entity.setSkuId(skuId);
        entity.setBatchNo(batchNo);
        entity.setMovementType(movement.movementType());
        entity.setDeltaQty(movement.deltaQty());
        entity.setRefType(movement.refType());
        entity.setRefId(movement.refId());
        entity.setOperatorId(movement.operatorId());
        movementRepository.save(entity);
    }

    private record MovementSpec(String movementType, int deltaQty, String refType, String refId, Long operatorId) {}

    private void validateInboundLine(WarehouseInboundLineDto dto) {
        if (dto.skuId() == null || dto.skuId().isBlank()) throw badRequest("skuId required");
        if (dto.batchNo() == null || dto.batchNo().isBlank()) throw badRequest("batchNo required");
        if (dto.expiryDate() == null) throw badRequest("expiryDate required");
        if (dto.quantity() <= 0) throw badRequest(QUANTITY_MUST_BE_POSITIVE);
        if (!skuCatalogRepository.existsById(dto.skuId())) {
            throw badRequest("sku not found: " + dto.skuId());
        }
    }

    /**
     * 日常出库必须从柜机所属仓扣库存：指定仓须已绑负责人；未指定则取柜机 home_warehouse_id。
     * 禁止再落到无主默认仓。
     */
    String resolveOutboundWarehouseId(String requested, String deviceId, List<ReplenishmentTask> tasks) {
        if (requested != null && !requested.isBlank()) {
            return requireManagedOutboundWarehouse(requested.trim());
        }
        Set<String> homes = new LinkedHashSet<>();
        List<String> deviceIds = new ArrayList<>();
        if (deviceId != null && !deviceId.isBlank()) {
            deviceIds.add(deviceId.trim());
        }
        if (tasks != null) {
            for (ReplenishmentTask task : tasks) {
                if (task.getDeviceId() != null && !task.getDeviceId().isBlank()) {
                    deviceIds.add(task.getDeviceId().trim());
                }
            }
        }
        if (deviceInfoRepository == null) {
            throw badRequest(ApiMessages.REPLENISHMENT_HOME_WAREHOUSE_REQUIRED);
        }
        for (String id : deviceIds) {
            DeviceInfo device = deviceInfoRepository.findById(id)
                    .orElseThrow(() -> badRequest(ApiMessages.DEVICE_NOT_FOUND));
            if (device.getHomeWarehouseId() == null || device.getHomeWarehouseId().isBlank()) {
                throw badRequest(ApiMessages.REPLENISHMENT_HOME_WAREHOUSE_REQUIRED);
            }
            homes.add(device.getHomeWarehouseId().trim());
        }
        if (homes.isEmpty()) {
            throw badRequest(ApiMessages.REPLENISHMENT_HOME_WAREHOUSE_REQUIRED);
        }
        if (homes.size() > 1) {
            throw badRequest(ApiMessages.REPLENISHMENT_HOME_WAREHOUSE_MIXED);
        }
        return requireManagedOutboundWarehouse(homes.iterator().next());
    }

    private String requireManagedOutboundWarehouse(String warehouseId) {
        Warehouse warehouse = warehouseRepository.findById(warehouseId)
                .orElseThrow(() -> badRequest("仓库不存在"));
        // V412：负责人是集合（warehouse_manager），不再看单值派生列
        if (warehouseManagerRepository.listByWarehouseId(warehouse.getWarehouseId()).isEmpty()) {
            throw badRequest(ApiMessages.REPLENISHMENT_WAREHOUSE_UNMANAGED);
        }
        return warehouse.getWarehouseId();
    }

    /** 分仓存在性闸门：仓号写错/被删时给出可定位的成因，而不是让下游 NPE。 */
    public Warehouse requireWarehouseExists(String warehouseId) {
        return warehouseRepository.findById(warehouseId)
                .orElseThrow(() -> badRequest("仓库不存在：" + warehouseId));
    }

    /**
     * 校验仓库存在且由指定用户负责（多人中的任意一位即通过）。
     * 🔴 CB-068 A 档（2026-10-27）后**自主发起补货不再走这里**（判据=柜机归该仓 + 柜机在本人数据范围内）；
     * 本闸门留给分仓盘点——盘点直接改写账面数量，仍按「负责人集合」收紧（V412 起集合可多人）。
     * 拒绝必须自带成因（CB-070）：报出仓号与当前负责人，别让商户猜「是不是权限没配」。
     */
    public Warehouse requireWarehouseManagedBy(String warehouseId, Long userId) {
        Warehouse warehouse = requireWarehouseExists(warehouseId);
        if (warehouseManagerRepository.isManager(warehouse.getWarehouseId(), userId)) {
            return warehouse;
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "你不是分仓 " + warehouse.getWarehouseId() + " 的负责人（当前负责人："
                        + describeManagerUsers(warehouse.getWarehouseId())
                        + "），请联系运营在「仓库概览」调整");
    }

    /** 该仓负责人集合的可读串（姓名 + 掩码手机号，顿号分隔）；无负责人回「未指定」。 */
    public String describeManagerUsers(String warehouseId) {
        List<WarehouseManagerDto> managers = managerDtosOf(warehouseId);
        if (managers.isEmpty()) {
            return "未指定";
        }
        return managers.stream()
                .map(m -> m.phoneNumber() == null || m.phoneNumber().isBlank()
                        ? m.name()
                        : m.name() + " " + m.phoneNumber())
                .collect(Collectors.joining("、"));
    }

    /** 该仓名下柜机所属商户（仓表无 merchant_id，只能经 device_info.home_warehouse_id 反推）。 */
    private Set<String> warehouseMerchantIdsOf(String warehouseId) {
        return deviceInfoRepository.findAllOrderByDeviceIdAsc().stream()
                .filter(d -> warehouseId.equals(
                        d.getHomeWarehouseId() == null ? null : d.getHomeWarehouseId().trim()))
                .map(DeviceInfo::getMerchantId)
                .filter(m -> m != null && !m.isBlank())
                .collect(Collectors.toSet());
    }

    /**
     * 负责人写入校验（CB-070，只在 `managerUserId` **变化**时调用；清空不校验）。
     * 三条判据各报各的，避免「保存失败」三无提示：
     *   1) 账号存在且未停用；
     *   2) 🔴 自加门（不抄竞品）：候选人与该仓至少一台柜机同商户——仓表无 merchant_id，
     *      只能经 device_info.home_warehouse_id 反推；该仓暂无柜机时放行并提示，不阻断建档。
     *   3) 拥有补货发起权限（角色里有 merchant:replenishment:request）。
     */
    void validateWarehouseManagerCandidate(String warehouseId, Long managerUserId) {
        UserInfo candidate = userInfoRepository.selectById(managerUserId);
        if (candidate == null) {
            throw badRequest("分仓负责人用户编号 " + managerUserId + " 不存在，请到「运营账号管理」核对后重填");
        }
        if (candidate.getStatus() != null && !"ACTIVE".equalsIgnoreCase(candidate.getStatus())) {
            throw badRequest("分仓负责人「" + describeManagerUser(managerUserId)
                    + "」账号已停用，请先启用或改指他人");
        }
        Set<String> warehouseMerchantIds = warehouseMerchantIdsOf(warehouseId);
        if (!warehouseMerchantIds.isEmpty()) {
            Set<String> candidateMerchants = merchantScopeService.allowedMerchantIds(managerUserId);
            if (warehouseMerchantIds.stream().noneMatch(candidateMerchants::contains)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "分仓负责人「" + describeManagerUser(managerUserId) + "」不属于分仓 " + warehouseId
                                + " 内柜机的商户，请先为该账号绑定商户或改用同商户账号");
            }
        }
        if (!permissionService.hasPermission(managerUserId, "merchant:replenishment:request")) {
            throw badRequest("分仓负责人「" + describeManagerUser(managerUserId)
                    + "」缺少「发起补货」权限，请先在「角色管理」为其分配补货员/店长类角色");
        }
    }

    /** 负责人可读标识：姓名 + 掩码手机号；查不到账号时退化为编号，绝不回空（提示要能用）。 */
    private String describeManagerUser(Long managerUserId) {
        if (managerUserId == null) {
            return "未指定";
        }
        UserInfo user = userInfoRepository.selectById(managerUserId);
        if (user == null) {
            return "账号 " + managerUserId + "（已不存在）";
        }
        String name = user.getName() == null || user.getName().isBlank() ? "未命名" : user.getName().trim();
        String phone = user.getPhoneNumber() == null || user.getPhoneNumber().isBlank()
                ? ""
                : " " + PhoneMask.mask(user.getPhoneNumber());
        return name + phone;
    }

    private String resolveWarehouseId(String warehouseId) {
        if (warehouseId != null && !warehouseId.isBlank()) {
            return warehouseId.trim();
        }
        return resolveDefaultWarehouseId();
    }

    /** 空白仓 ID：取库内 ACTIVE 仓（标准 12 位优先），禁止回退写死 WH-DEMO-001。 */
    public String resolveDefaultWarehouseId() {
        return warehouseRepository.findAll().stream()
                .filter(w -> w.getWarehouseId() != null && !w.getWarehouseId().isBlank())
                .filter(w -> w.getStatus() == null || "ACTIVE".equalsIgnoreCase(w.getStatus()))
                .map(Warehouse::getWarehouseId)
                .min((a, b) -> {
                    int std = Boolean.compare(
                            WarehouseSupplierIdService.isStandardId(b),
                            WarehouseSupplierIdService.isStandardId(a));
                    return std != 0 ? std : a.compareTo(b);
                })
                .orElseThrow(() -> badRequest("未配置仓库，请先新建仓库"));
    }

    static String outboundLockKey(Long outboundId) {
        return "warehouse:outbound:" + outboundId;
    }

    static String stockLockKey(String warehouseId, String skuId, String batchNo) {
        return "warehouse:stock:" + warehouseId + ":" + skuId + ":" + batchNo;
    }

    private <T> T runWithStockLock(String warehouseId, String skuId, String batchNo,
                                   java.util.function.Supplier<T> action) {
        if (!distributedLockService.tryLock(stockLockKey(warehouseId, skuId, batchNo), 60, 5)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "仓库库存处理中，请稍后重试");
        }
        try {
            return action.get();
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage(), e);
        } finally {
            distributedLockService.unlock(stockLockKey(warehouseId, skuId, batchNo));
        }
    }

    private WarehouseOutbound requireOutboundForUpdate(Long outboundId) {
        return outboundRepository.findByIdForUpdate(outboundId).orElseThrow(() -> notFound("outbound"));
    }

    private static boolean hasHandedOverOutboundLines(List<WarehouseOutboundLine> lines) {
        return lines.stream().anyMatch(line -> {
            String hs = line.getHandoverStatus();
            return RECEIVED.equals(hs) || PARTIAL.equals(hs);
        });
    }

    private static java.util.LinkedHashSet<String> distinctOutboundDevices(List<WarehouseOutboundLine> lines) {
        java.util.LinkedHashSet<String> devices = new java.util.LinkedHashSet<>();
        for (WarehouseOutboundLine line : lines) {
            if (line.getDeviceId() != null && !line.getDeviceId().isBlank()) {
                devices.add(line.getDeviceId().trim());
            }
        }
        return devices;
    }

    private <T> T runWithOutboundLock(Long outboundId, java.util.function.Supplier<T> action) {
        if (!distributedLockService.tryLock(outboundLockKey(outboundId), 60, 5)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "出库单处理中，请稍后重试");
        }
        try {
            return action.get();
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage(), e);
        } finally {
            distributedLockService.unlock(outboundLockKey(outboundId));
        }
    }

    private WarehouseDto toWarehouseDto(Warehouse w) {
        return new WarehouseDto(
                w.getWarehouseId(),
                w.getWarehouseName(),
                w.getAddress(),
                w.getStatus(),
                w.getCreatedAt(),
                w.getManagerUserId(),
                managerDtosOf(w.getWarehouseId()));
    }

    /**
     * 某人是否该仓负责人（多人集合中的任意一位）。
     *
     * <p>🔴 唯一的「我是不是这个仓的负责人」判据入口：各处不要再读
     * {@code warehouse.manager_user_id}（那是主责人派生列，一仓两人时第二位会被漏掉）。</p>
     */
    public boolean isManagedBy(Long userId, String warehouseId) {
        if (userId == null || warehouseId == null || warehouseId.isBlank()) {
            return false;
        }
        return warehouseManagerRepository.isManager(warehouseId, userId);
    }

    /**
     * 该仓是否已有负责人（任意一位即可）。
     *
     * <p>只判存在性、不拼 DTO：采购入库等热路径拿它当闸门，走 {@link #managerDtosOf}
     * 会白白带上逐人 {@code user_info} 查询。判据同样是 {@code warehouse_manager} 集合。</p>
     */
    public boolean hasAnyManager(String warehouseId) {
        if (warehouseId == null || warehouseId.isBlank()) {
            return false;
        }
        return !warehouseManagerRepository.listByWarehouseId(warehouseId).isEmpty();
    }

    /**
     * 该仓负责人全集（按绑定先后，第一位即主责人）。
     *
     * <p>姓名空回落「未命名」、手机号**掩码**——这两个值会原样进后台列表，
     * 回 null 会让运营看到空白并误判「没人负责」。</p>
     *
     * <p>仓表是配置表（个位数行），每仓 1~3 人，故逐人查 user_info 的 N+1 可接受；
     * 真要批量化应改成 IN 查询 + group by，而不是在这里堆缓存。</p>
     */
    public List<WarehouseManagerDto> managerDtosOf(String warehouseId) {
        if (warehouseId == null || warehouseId.isBlank()) {
            return List.of();
        }
        return warehouseManagerRepository.listByWarehouseId(warehouseId).stream()
                .map(m -> {
                    UserInfo u = userInfoRepository.selectById(m.getUserId());
                    if (u == null) {
                        // 账号被硬删后 FK 会级联清行，走不到这里；留着只为「历史脏数据不炸列表」
                        return new WarehouseManagerDto(m.getUserId(), "账号 " + m.getUserId() + "（已不存在）", "");
                    }
                    String name = u.getName() == null || u.getName().isBlank() ? "未命名" : u.getName().trim();
                    String phone = u.getPhoneNumber() == null || u.getPhoneNumber().isBlank()
                            ? ""
                            : PhoneMask.mask(u.getPhoneNumber());
                    return new WarehouseManagerDto(m.getUserId(), name, phone);
                })
                .toList();
    }

    private WarehouseInventoryDto toInventoryDto(WarehouseInventory i) {
        return new WarehouseInventoryDto(
                i.getInventoryId(), i.getWarehouseId(), i.getSkuId(), i.getBatchNo(),
                i.getProductionDate(), i.getExpiryDate(), i.getQuantity()
        );
    }

    private WarehouseOutboundLineDto toOutboundLineDto(WarehouseOutboundLine line) {
        return new WarehouseOutboundLineDto(
                line.getLineId(), line.getDeviceId(), line.getSkuId(), line.getBatchNo(),
                line.getExpiryDate(), line.getQuantity(), line.getRemainderQty(), line.isPicked(),
                line.getHandoverStatus() == null ? "PENDING" : line.getHandoverStatus(),
                line.getSlotId(),
                displaySnapshotHelper.resolveDeviceName(line.getDeviceId())
        );
    }

    /**
     * @param operatorId     操作人编号，仅用于负责人变更审计（CB-070 第 4 点）；不影响任何业务判据
     * @param managerUserIds 🔴 V412 起负责人是**集合**：
     *                       {@code null} = 不改负责人（CSV 导入 / 只改名称地址的旧客户端可省略）；
     *                       空列表 = 清空；顺序有意义——**第一位即主责人**，
     *                       同步写入派生列 {@code warehouse.manager_user_id}（月结/外部导入仍读它）。
     */
    @Transactional
    public WarehouseDto upsertWarehouse(
            Long operatorId,
            String warehouseId,
            String warehouseName,
            String address,
            String status,
            List<Long> managerUserIds) {
        if (warehouseName == null || warehouseName.isBlank()) {
            throw badRequest("warehouseName required");
        }
        String raw = warehouseId != null ? warehouseId.trim() : "";
        boolean createToken = raw.isEmpty() || "new".equalsIgnoreCase(raw) || "_".equals(raw);
        boolean exists = !createToken && warehouseRepository.findById(raw).isPresent();
        final String id;
        if (exists) {
            id = raw;
        } else {
            id = warehouseSupplierIdService.resolveWarehouseIdForCreate(createToken ? null : raw);
        }
        Warehouse warehouse = warehouseRepository.findById(id).orElseGet(Warehouse::new);
        boolean creating = warehouse.getWarehouseId() == null || warehouse.getWarehouseId().isBlank();
        List<Long> previousManagers = warehouseManagerRepository.listByWarehouseId(id).stream()
                .map(WarehouseManager::getUserId)
                .toList();
        warehouse.setWarehouseId(id);
        warehouse.setWarehouseName(warehouseName.trim());
        // 地址由运营填写（AddressPicker）；禁止种子写死城市
        warehouse.setAddress(address == null || address.isBlank() ? null : address.trim());
        String st = status == null || status.isBlank() ? "ACTIVE" : status.trim().toUpperCase();
        if (!"ACTIVE".equals(st) && !"INACTIVE".equals(st)) {
            throw badRequest("status must be ACTIVE or INACTIVE");
        }
        warehouse.setStatus(st);
        List<Long> nextManagers = managerUserIds == null ? null : normalizeManagerIds(managerUserIds);
        if (nextManagers != null) {
            // CB-070：只校验**本次新增**的人。重复保存同一人（改名称/地址）不该被
            // 「账号后来被停用」误伤；移除负责人是撤权、不是授权，同样不校验。
            boolean sawCandidate = false;
            for (Long next : nextManagers) {
                if (!previousManagers.contains(next)) {
                    validateWarehouseManagerCandidate(id, next);
                    sawCandidate = true;
                }
            }
            if (sawCandidate && warehouseMerchantIdsOf(id).isEmpty()) {
                log.info("分仓 {} 尚无柜机，负责人「同商户」判据本次跳过（建档期放行）", id);
            }
            // 主责人派生列：列表第一位。权限判据一律读 warehouse_manager，不读这一列。
            warehouse.setManagerUserId(nextManagers.isEmpty() ? null : nextManagers.get(0));
        }
        if (creating || warehouse.getCreatedAt() == null) {
            warehouse.setCreatedAt(Instant.now());
        }
        warehouseRepository.save(warehouse);
        // 负责人集合落库必须在 warehouse 保存之后（warehouse_manager.warehouse_id 有外键）
        if (nextManagers != null && !nextManagers.equals(previousManagers)) {
            syncManagers(id, nextManagers);
            // 前后值只在**真的变人**时记（含清空）：形态沿用 CB-021 的 `col=old->new`，
            // 值里带编号是为了可还原，括号里的人名是给人看的——🔴 必须是掩码手机号，
            // 因为审计页把 detail 原样吐给 ops:audit:list 持有者，读侧只掩码操作人手机、不再洗 detail。
            auditService.appendLog(
                    operatorId,
                    AUDIT_ACTION_MANAGER,
                    AUDIT_TARGET_WAREHOUSE,
                    id,
                    "manager_user_id=" + managerAuditValue(previousManagers)
                            + "->" + managerAuditValue(nextManagers));
        }
        return toWarehouseDto(warehouse);
    }

    /** 去空、去零、去重并保序（顺序决定主责人，故用 LinkedHashSet 不用 Set）。 */
    private static List<Long> normalizeManagerIds(List<Long> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<Long> uniq = new LinkedHashSet<>();
        for (Long v : raw) {
            if (v != null && v > 0L) {
                uniq.add(v);
            }
        }
        return List.copyOf(uniq);
    }

    /**
     * 整表重写该仓负责人。
     *
     * <p>为什么整表重写而不是差量增删：顺序即主责人，差量无法表达「把第二位提为主责人」。
     * 同一事务内批量重写行数极小（1~3 行），代价可忽略。</p>
     */
    private void syncManagers(String warehouseId, List<Long> managerUserIds) {
        warehouseManagerRepository.deleteByWarehouseId(warehouseId);
        Instant boundAt = Instant.now();
        for (Long userId : managerUserIds) {
            WarehouseManager row = new WarehouseManager();
            row.setWarehouseId(warehouseId);
            row.setUserId(userId);
            row.setCreatedAt(boundAt);
            warehouseManagerRepository.insert(row);
        }
    }

    /** 审计值形态：{@code []}（无人）或 {@code [42(张三 138****6077),43(...)]}。 */
    private String managerAuditValue(List<Long> managerUserIds) {
        if (managerUserIds == null || managerUserIds.isEmpty()) {
            return "[]";
        }
        return "[" + managerUserIds.stream()
                .map(uid -> uid + "(" + describeManagerUser(uid) + ")")
                .collect(Collectors.joining(",")) + "]";
    }

    private WarehouseMovementDto toMovementDto(WarehouseMovement movement) {
        return new WarehouseMovementDto(
                movement.getMovementId(),
                movement.getWarehouseId(),
                movement.getSkuId(),
                movement.getBatchNo(),
                movement.getMovementType(),
                movement.getDeltaQty(),
                movement.getRefType(),
                movement.getRefId(),
                movement.getOperatorId(),
                movement.getCreatedAt()
        );
    }

    private static ResponseStatusException notFound(String what) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, what + " not found");
    }

    private static ResponseStatusException badRequest(String msg) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
    }

    private static Long parseLongOrNull(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
