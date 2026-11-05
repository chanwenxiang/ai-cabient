package com.aicabinet.trade.service;

import com.aicabinet.common.dto.*;
import com.aicabinet.trade.domain.*;
import com.aicabinet.trade.mapper.*;
import com.aicabinet.trade.support.SkuOnShelf;
import com.aicabinet.trade.support.SkuStockLevel;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class DeviceSlotService {

    private static final Logger log = LoggerFactory.getLogger(DeviceSlotService.class);

    private final DeviceSlotMapper slotRepository;
    private final DeviceSkuLotMapper lotRepository;
    private final DeviceInfoMapper deviceRepository;
    private final DeviceSkuInventoryMapper inventoryRepository;
    private final SkuCatalogMapper skuCatalogRepository;
    private final MerchantMapper merchantRepository;
    private final ReplenishmentTaskMapper taskRepository;
    private final MerchantScopeService merchantScopeService;
    private final SalesVelocityService salesVelocityService;
    private final RefundPolicyService refundPolicyService;
    private final InventoryLotService inventoryLotService;
    private final MerchantOpsPolicyService opsPolicyService;
    private final DistributedLockService distributedLockService;
    private final ShoppingSessionMapper shoppingSessionMapper;
    private final CabinetOrderLineMapper orderLineRepository;
    /** CB-050 扩展：盘点写 AISLE_AUDIT 运维事件（商户端理货记录数据源） */
    private final DeviceOpsEventMapper opsEventRepository;
    /** 设备 DTO 回填将邑厂商 SN（将邑柜无 IMEI，只能靠 SN 识别） */
    private final JiangyiDeviceDirectory jiangyiDeviceDirectory;
    /** 经 Spring 代理调用本类 @Transactional 方法，避免自调用失效。 */
    private final DeviceSlotService self;

    public DeviceSlotService(DeviceSlotMapper slotRepository,
                             DeviceSkuLotMapper lotRepository,
                             DeviceInfoMapper deviceRepository,
                             DeviceSkuInventoryMapper inventoryRepository,
                             SkuCatalogMapper skuCatalogRepository,
                             MerchantMapper merchantRepository,
                             ReplenishmentTaskMapper taskRepository,
                             MerchantScopeService merchantScopeService,
                             SalesVelocityService salesVelocityService,
                             RefundPolicyService refundPolicyService,
                             InventoryLotService inventoryLotService,
                             MerchantOpsPolicyService opsPolicyService,
                             DistributedLockService distributedLockService,
                             ShoppingSessionMapper shoppingSessionMapper,
                             CabinetOrderLineMapper orderLineRepository,
                             DeviceOpsEventMapper opsEventRepository,
                             JiangyiDeviceDirectory jiangyiDeviceDirectory,
                             @Lazy DeviceSlotService self) {
        this.slotRepository = slotRepository;
        this.lotRepository = lotRepository;
        this.deviceRepository = deviceRepository;
        this.inventoryRepository = inventoryRepository;
        this.skuCatalogRepository = skuCatalogRepository;
        this.merchantRepository = merchantRepository;
        this.taskRepository = taskRepository;
        this.merchantScopeService = merchantScopeService;
        this.salesVelocityService = salesVelocityService;
        this.refundPolicyService = refundPolicyService;
        this.inventoryLotService = inventoryLotService;
        this.opsPolicyService = opsPolicyService;
        this.distributedLockService = distributedLockService;
        this.shoppingSessionMapper = shoppingSessionMapper;
        this.orderLineRepository = orderLineRepository;
        this.opsEventRepository = opsEventRepository;
        this.jiangyiDeviceDirectory = jiangyiDeviceDirectory;
        this.self = self;
    }

    @Transactional(readOnly = true)
    public DeviceDetailDto getDeviceDetail(Long operatorId, String deviceId) {
        merchantScopeService.requireDeviceAccess(operatorId, deviceId);
        DeviceInfo device = requireDevice(deviceId);
        // 批 3b 第十四刀 B：指标不再分柜型——台账/SKU 口径是唯一来源（开门柜早已走它）
        DeviceOpsMetricsDto metrics = ledgerStockMetrics(device);
        // 「该柜启用批次账本」和「本 SKU 进过批次账本」是两个粒度。只判前者，会把柜里
        // 从未建账的 SKU 一律读成 0——账面明明有货却恒显示缺货，且「编辑库存」保存后数字不动
        // （2026-10-26 雪碧实测：device_sku_inventory=2、device_sku_lot 零行、流水零条）。
        Set<String> lotLedgerSkus = inventoryLotService.lotLedgerSkuIds(deviceId);
        Map<String, Integer> sellableBySku = lotLedgerSkus.isEmpty()
                ? null
                : inventoryLotService.sellableQtyBySku(deviceId);
        // 商品管理下架（sku_catalog.status≠ACTIVE）只改商品档案，不级联清柜×商品台账行；
        // 设备详情「已上架商品」若不过滤会把已下架 SKU 仍显示为在架（2026-10-22 用户反馈）。
        // 口径与消费者目录一致（DeviceCatalogService 只认 ACTIVE）。
        List<DeviceSkuInventory> inventoryRows = inventoryRepository.findByIdDeviceId(deviceId);
        Set<String> inventorySkuIds = inventoryRows.stream()
                .map(DeviceSkuInventory::getSkuId)
                .collect(Collectors.toSet());
        // 同一查询顺带取名字：零货道机型补货明细以 skuInventory.skuName 作标题，
        // 缺了只能退回 SKU 编号（2026-10-27 用户反馈「给商品的名称，别用编号」）。
        // 手工 putAll 容忍 null 名（toMap 遇 null value 会 NPE）——显示层另有编号兜底。
        List<SkuCatalog> catalogRows = inventorySkuIds.isEmpty() ? List.of()
                : skuCatalogRepository.selectList(
                                Wrappers.<SkuCatalog>lambdaQuery().in(SkuCatalog::getSkuId, inventorySkuIds));
        Map<String, String> skuNameById = new java.util.HashMap<>();
        Map<String, String> skuImageById = new java.util.HashMap<>();
        for (SkuCatalog s : catalogRows) {
            if (s.getSkuName() != null) {
                skuNameById.putIfAbsent(s.getSkuId(), s.getSkuName());
            }
            if (s.getImageUrl() != null) {
                skuImageById.putIfAbsent(s.getSkuId(), s.getImageUrl());
            }
        }
        Set<String> activeSkuIds = catalogRows.stream()
                .filter(SkuOnShelf::isOnShelf)
                .map(SkuCatalog::getSkuId)
                .collect(Collectors.toSet());
        List<DeviceInventoryDto> skuInventory = inventoryRows.stream()
                .filter(inv -> activeSkuIds.contains(inv.getSkuId()))
                .map(inv -> toInventoryDto(inv, sellableBySku, lotLedgerSkus,
                        skuNameById.get(inv.getSkuId()), skuImageById.get(inv.getSkuId())))
                .toList();
        // CB-080（2026-10-31 拍板）：下架**不**自动清柜×商品台账行（货可能还在柜里，清了重新上架
        // 会账面凭空 0 ⇒ 过量补货；竞品口径下架只收敛补货面、库存真相靠盘点纠偏）。残留行此前
        // 在详情页完全不可见，死账只能 SQL 手删——单独出一份给 admin 端可见可清，小程序端忽略。
        List<DeviceInventoryDto> delistedInventory = inventoryRows.stream()
                .filter(inv -> !activeSkuIds.contains(inv.getSkuId()))
                .map(inv -> toInventoryDto(inv, sellableBySku, lotLedgerSkus,
                        skuNameById.get(inv.getSkuId()), skuImageById.get(inv.getSkuId())))
                .toList();
        // 「最近会话」语义=当前进行中的会话，没有则回落最近一次（含已完结），
        // 否则详情页在有历史会话时也显示「无」（2026-09-30 用户实测反馈）。
        var lastSession = shoppingSessionMapper.findFirstByDeviceIdOrderByCreatedAtDesc(deviceId).orElse(null);
        AdminDeviceDto deviceDto = toAdminDeviceDto(device, lastSession);
        return new DeviceDetailDto(deviceDto, metrics, skuInventory, delistedInventory);
    }

    /**
     * 柜机缺货/低库存计数。供商户柜机列表默认排序（收藏柜 → 缺货最多）与卡片标签使用。
     * 口径＝在架 SKU 台账（与设备详情 KPI 同一个 {@link #doorCabinetStockStats}，第十四刀 B 起**全柜型单源**）；
     * DTO 字段名保留 *Slot 但语义是「商品」个数；无实盘故 lastCheckAt 恒空。
     *   🔴 此前开门柜恒返回 EMPTY ⇒ 商户卡片恒「0 缺货」，与后台设备详情 KPI 对不上。
     */
    @Transactional(readOnly = true)
    public SlotStockCounts countSlotStock(String deviceId) {
        if (deviceId == null || deviceId.isBlank()) {
            return SlotStockCounts.EMPTY;
        }
        // 批 3b 第十四刀 B：卡片与详情同一口径（在架 SKU 台账），不再按柜型分流
        DoorCabinetStockStats doorStats = doorCabinetStockStats(deviceId.trim());
        if (doorStats.equals(DoorCabinetStockStats.EMPTY)) {
            return SlotStockCounts.EMPTY;
        }
        return new SlotStockCounts(doorStats.oosCount(), doorStats.lowCount(), doorStats.fillRatePct(),
                null, taskRepository.findLastCompletedAtByDeviceId(deviceId.trim()).orElse(null));
    }

    /** 缺货/低库存个数（台账口径下是商品数；字段名兼容保留 *Slot）。 */
    public record SlotStockCounts(int oosSlotCount, int lowStockSlotCount, Integer fillRatePct,
                                  Instant lastCheckAt, Instant lastRestockAt) {
        public static final SlotStockCounts EMPTY = new SlotStockCounts(0, 0, null, null, null);
    }

    /**
     * 统一缺货口径（P1-3，2026-10-15）：启用且绑定了 SKU 的货道，
     * 账面 ≤ minLevel（含 OOS）**或** 账面 < 容量×商户阈值%（默认 50，对齐叮当小柜
     * 「剩余库存低于货柜容量的 X% 即缺货」）。缺货看板/工作台/推送/补货建议共用此判定，
     * 预警说缺的货建议页一定有。
     */
    public static boolean isSlotShortage(DeviceSlot slot, int bookQty, int thresholdPct) {
        if (!slot.isEnabled() || slot.getAssignedSkuId() == null || slot.getAssignedSkuId().isBlank()) {
            return false;
        }
        if (bookQty <= slot.getMinLevel()) {
            return true;
        }
        int cap = slot.getMaxLevel() > 0 ? slot.getMaxLevel() : slot.getParLevel();
        return cap > 0 && bookQty * 100L < cap * (long) thresholdPct;
    }

    /** 设备维度缺货货道清单（统一口径），供工作台/推送消费。 */
    @Transactional(readOnly = true)
    public List<SlotShortageItem> listShortageSlots(String deviceId) {
        String dev = deviceId == null ? "" : deviceId.trim();
        if (dev.isEmpty()) {
            return List.of();
        }
        int thresholdPct = resolveStockoutThresholdPct(dev);
        Map<String, Integer> bookBySlot = self.loadBookQtyBySlot(dev);
        List<SlotShortageItem> out = new ArrayList<>();
        for (DeviceSlot slot : slotRepository.findByIdDeviceIdOrderByRowNoAscColNoAsc(dev)) {
            int book = bookBySlot.getOrDefault(slot.getId().getSlotCode(), 0);
            if (isSlotShortage(slot, book, thresholdPct)) {
                out.add(new SlotShortageItem(dev, slot.getId().getSlotCode(), slot.getAssignedSkuId(),
                        book, slot.getMinLevel(),
                        slot.getMaxLevel() > 0 ? slot.getMaxLevel() : slot.getParLevel(),
                        slot.getParLevel(), thresholdPct));
            }
        }
        return out;
    }

    /** 统一缺货口径条目（device_sku_lot 账面维度；库存数=货道账面，非 SKU 冗余列）。 */
    public record SlotShortageItem(String deviceId, String slotCode, String skuId,
                                   int bookQty, int minLevel, int capacity, int parLevel,
                                   int thresholdPct) {}

    /** 销售扣减后同步货道实测数量（仅更新已有实测值的货道）。 */
    @Transactional(readOnly = true)
    public Map<String, Integer> loadBookQtyBySlot(String deviceId) {
        return lotRepository.sumBookQtyBySlot(deviceId).stream()
                .collect(Collectors.toMap(
                        row -> (String) row[0],
                        row -> ((Number) row[1]).intValue(),
                        Integer::sum
                ));
    }

    public List<SkuQuantityDto> inventorySnapshot(String deviceId) {
        requireDevice(deviceId);
        if (inventoryLotService.deviceUsesLotLedger(deviceId)) {
            return inventoryLotService.sellableQtyBySku(deviceId).entrySet().stream()
                    .filter(e -> e.getValue() != null && e.getValue() > 0)
                    .map(e -> new SkuQuantityDto(e.getKey(), e.getValue()))
                    .toList();
        }
        return inventoryRepository.findByIdDeviceId(deviceId).stream()
                .filter(inv -> inv.getQuantity() > 0)
                .map(inv -> new SkuQuantityDto(inv.getId().getSkuId(), inv.getQuantity()))
                .toList();
    }

    public record SlotBookView(String slotCode, int parLevel) {}

    public record SlotRestockAllocation(String slotCode, int quantity) {}

    /**
     * 多货道同 SKU：优先填入账面最低、尚有容量的货道。
     */
    @Transactional(readOnly = true)
    public List<SlotRestockAllocation> allocateRestockQuantity(String deviceId, String skuId, int totalQty) {
        if (totalQty <= 0 || skuId == null || skuId.isBlank()) {
            return List.of();
        }
        Map<String, Integer> bookBySlot = self.loadBookQtyBySlot(deviceId);
        record Candidate(String slotCode, int book, int headroom) {}
        List<Candidate> candidates = slotRepository.findByIdDeviceIdOrderByRowNoAscColNoAsc(deviceId).stream()
                .filter(s -> s.isEnabled() && skuId.equals(s.getAssignedSkuId()))
                .map(s -> {
                    String code = s.getId().getSlotCode();
                    int book = bookBySlot.getOrDefault(code, 0);
                    int cap = slotCapacity(s, Integer.MAX_VALUE);
                    return new Candidate(code, book, Math.max(0, cap - book));
                })
                .filter(c -> c.headroom > 0)
                .sorted(java.util.Comparator.comparingInt(Candidate::book)
                        .thenComparing(Candidate::slotCode))
                .toList();
        if (candidates.isEmpty()) {
            log.warn("allocateRestockQuantity: no slot headroom device={} sku={} qty={}",
                    deviceId, skuId, totalQty);
            return List.of();
        }
        int remaining = totalQty;
        List<SlotRestockAllocation> result = new java.util.ArrayList<>();
        for (Candidate c : candidates) {
            if (remaining <= 0) {
                break;
            }
            int take = Math.min(remaining, c.headroom);
            if (take > 0) {
                result.add(new SlotRestockAllocation(c.slotCode(), take));
                remaining -= take;
            }
        }
        if (remaining > 0) {
            // 不把溢出硬塞进末货道，避免账面库存超过 maxLevel（如 16/6）
            log.warn("allocateRestockQuantity: {} units unallocated (over capacity) device={} sku={}",
                    remaining, deviceId, skuId);
        }
        return result;
    }

    /** SKU 是否已绑定启用货道。 */
    @Transactional(readOnly = true)
    public boolean hasSkuSlots(String deviceId, String skuId) {
        if (deviceId == null || skuId == null || skuId.isBlank()) {
            return false;
        }
        return slotRepository.findByIdDeviceIdOrderByRowNoAscColNoAsc(deviceId).stream()
                .anyMatch(s -> s.isEnabled() && skuId.equals(s.getAssignedSkuId()));
    }

    /** 该 SKU 在柜机上的可补总容量（各货道 headroom 之和）。 */
    @Transactional(readOnly = true)
    public int totalHeadroomForSku(String deviceId, String skuId) {
        return self.allocateRestockQuantity(deviceId, skuId, Integer.MAX_VALUE / 4).stream()
                .mapToInt(SlotRestockAllocation::quantity)
                .sum();
    }

    /** 单货道可补容量（max/par − 账面）；货道不存在或未启用返回 0。 */
    @Transactional(readOnly = true)
    public int headroomForSlot(String deviceId, String slotCode) {
        if (deviceId == null || slotCode == null || slotCode.isBlank()) {
            return 0;
        }
        String normalized = slotCode.trim().toUpperCase();
        DeviceSlot slot = slotRepository.findById(new DeviceSlotId(deviceId, normalized)).orElse(null);
        if (slot == null || !slot.isEnabled()) {
            return 0;
        }
        int cap = slotCapacity(slot, 0);
        if (cap <= 0) {
            return Integer.MAX_VALUE / 4;
        }
        int book = self.loadBookQtyBySlot(deviceId).getOrDefault(normalized, 0);
        return Math.max(0, cap - book);
    }

    /** 按货道陈列图 + 动销 ROP 计算补货建议（仅低于 minLevel / ROP 时触发）。 */
    @Transactional(readOnly = true)
    public List<SlotReplenishmentSuggestDto> suggestSlotsForDevice(String deviceId) {
        requireDevice(deviceId);
        Map<String, Integer> bookBySlot = self.loadBookQtyBySlot(deviceId);
        Map<String, SalesVelocityService.SkuVelocity> velocityBySku = salesVelocityService.velocityBySku(deviceId);
        return slotRepository.findByIdDeviceIdOrderByRowNoAscColNoAsc(deviceId).stream()
                .filter(DeviceSlot::isEnabled)
                .filter(s -> s.getAssignedSkuId() != null && !s.getAssignedSkuId().isBlank())
                .filter(s -> s.getParLevel() > 0)
                .map(s -> buildSlotSuggest(deviceId, s, bookBySlot, velocityBySku, false))
                .filter(s -> s.suggestQty() > 0)
                .toList();
    }

    /**
     * 规划出库用：账面低于 PAR 即补到 PAR（不必跌破 minLevel），避免路线出库单无明细。
     */
    @Transactional(readOnly = true)
    public List<SlotReplenishmentSuggestDto> suggestSlotsForOutboundFill(String deviceId) {
        return suggestSlotsForOutboundFill(deviceId, false);
    }

    /**
     * 全量视图（2026-10-30 用户拍板）：不筛 {@code suggestQty>0}——「生成备货单」要列出
     * 柜内所有在架商品，已满的行也要显示现状。其它调用方一律走上面两个触发型方法。
     */
    @Transactional(readOnly = true)
    public List<SlotReplenishmentSuggestDto> suggestSlotsForOutboundFillAll(String deviceId) {
        return suggestSlotsForOutboundFill(deviceId, true);
    }

    private List<SlotReplenishmentSuggestDto> suggestSlotsForOutboundFill(String deviceId,
                                                                         boolean includeZero) {
        requireDevice(deviceId);
        Map<String, Integer> bookBySlot = self.loadBookQtyBySlot(deviceId);
        Map<String, SalesVelocityService.SkuVelocity> velocityBySku = salesVelocityService.velocityBySku(deviceId);
        return slotRepository.findByIdDeviceIdOrderByRowNoAscColNoAsc(deviceId).stream()
                .filter(DeviceSlot::isEnabled)
                .filter(s -> s.getAssignedSkuId() != null && !s.getAssignedSkuId().isBlank())
                .filter(s -> s.getParLevel() > 0)
                .map(s -> buildSlotSuggest(deviceId, s, bookBySlot, velocityBySku, true))
                .filter(s -> includeZero || s.suggestQty() > 0)
                .toList();
    }

    private SlotReplenishmentSuggestDto buildSlotSuggest(String deviceId, DeviceSlot s,
                                                           Map<String, Integer> bookBySlot,
                                                           Map<String, SalesVelocityService.SkuVelocity> velocityBySku,
                                                           boolean fillToPar) {
        String slotCode = s.getId().getSlotCode();
        String skuId = s.getAssignedSkuId();
        int bookQty = bookBySlot.getOrDefault(slotCode, 0);
        SalesVelocityService.SkuVelocity velocity = velocityBySku.getOrDefault(
                skuId, new SalesVelocityService.SkuVelocity(0, 0, 0, 0));
        int target = s.getParLevel();
        int cap = s.getMaxLevel() > 0 ? s.getMaxLevel() : target;
        int headroom = Math.max(0, Math.min(target, cap) - bookQty);
        int suggestQty = 0;
        String reason = "PAR";
        // P1-3 统一口径：阈值% 触发并入补货建议（预警了建议页一定有）；reason 仍按 PAR/ROP 标注
        boolean parLow = bookQty <= s.getMinLevel()
                || (cap > 0 && bookQty * 100L < cap * (long) resolveStockoutThresholdPct(deviceId));
        boolean ropLow = velocity.ropPoint() > 0 && bookQty <= velocity.ropPoint();
        if (fillToPar) {
            suggestQty = headroom;
            if (ropLow && parLow) {
                reason = "PAR+ROP";
            } else if (ropLow) {
                reason = "ROP";
            }
        } else if (parLow || ropLow) {
            suggestQty = headroom;
            if (ropLow && !parLow) {
                reason = "ROP";
            } else if (ropLow) {
                // 走到此处时 parLow 恒为 true（见上方分支）
                reason = "PAR+ROP";
            }
        }
        return new SlotReplenishmentSuggestDto(
                deviceId, slotCode, skuId,
                bookQty, s.getParLevel(), s.getMinLevel(), s.getMaxLevel(),
                suggestQty, resolveStockStatus(s, bookQty),
                velocity.soldQty7d(), velocity.ropPoint(), reason);
    }

    /**
     * 开门柜判据（2026-10-28 用户拍板：<b>固定为「将邑绑定」</b>）。
     * 全系统唯一口径——货道读面归零、写面 409、补货行不绑货道都以本方法为准；
     * 不要再按 device_type 另立判据（该字典当前只有 AI_CABINET_V1 一项，无法区分柜型）。
     */
    public boolean isDoorCabinet(String deviceId) {
        return deviceId != null && !deviceId.isBlank()
                && jiangyiDeviceDirectory.isJiangyi(deviceId.trim());
    }

    /** 补货完成时刷新通道快照。 */
    @Transactional
    public void recordRestock(String deviceId, String slotCode) {
        recordRestock(deviceId, slotCode, false);
    }

    /**
     * 补货完成刷新通道快照。
     *
     * <p>P0-2 修复（2026-10-15）：{@code preservePhysical=true}（本次补货会话有真实重力实测）
     * 时只刷新 {@code lastRestockAt}，**不覆盖关门快照写入的实测**——少上架/错上架留在
     * qtyDiff/hasDiscrepancy 列里可追（原 SLOT_DISCREPANCY 告警面已随货道退场，批 3b 第十一刀）。
     * 无重力数据的柜机保持原行为（实盘=回写后账面）。</p>
     */
    @Transactional
    public void recordRestock(String deviceId, String slotCode, boolean preservePhysical) {
        if (slotCode == null || slotCode.isBlank()) {
            return;
        }
        runWithDeviceSlotLock(deviceId, () -> {
            doRecordRestock(deviceId, slotCode, preservePhysical);
            return null;
        });
    }

    private void doRecordRestock(String deviceId, String slotCode, boolean preservePhysical) {
        String normalized = slotCode.trim().toUpperCase();
        slotRepository.findById(new DeviceSlotId(deviceId, normalized)).ifPresent(slot -> {
            int bookQty = self.loadBookQtyBySlot(deviceId).getOrDefault(normalized, 0);
            slot.setLastRestockAt(Instant.now());
            if (!preservePhysical) {
                slot.setLastPhysicalQty(bookQty);
                slot.setLastPhysicalAt(Instant.now());
            }
            slotRepository.save(slot);
        });
    }

    /** RESTOCK 行校验：货道必填、SKU 匹配、不超货道容量。 */
    @Transactional(readOnly = true)
    public void validateRestockLine(String deviceId, String slotId, String skuId, int quantity) {
        if (slotId == null || slotId.isBlank()) {
            throw badRequest("补货明细必须填写货道");
        }
        if (quantity <= 0) {
            throw badRequest("补货数量必须大于 0");
        }
        String slotCode = slotId.trim().toUpperCase();
        DeviceSlot slot = slotRepository.findById(new DeviceSlotId(deviceId, slotCode))
                .orElseThrow(() -> badRequest("货道不存在：" + slotCode));
        if (!slot.isEnabled()) {
            throw badRequest("货道已停用：" + slotCode);
        }
        if (slot.getAssignedSkuId() != null && skuId != null
                && !slot.getAssignedSkuId().equals(skuId.trim())) {
            throw badRequest("货道 " + slotCode + " 已绑定 "
                    + slot.getAssignedSkuId() + "，与明细商品 " + skuId + " 不一致");
        }
        int cap = slotCapacity(slot, 0);
        if (cap > 0) {
            int book = self.loadBookQtyBySlot(deviceId).getOrDefault(slotCode, 0);
            if (book + quantity > cap) {
                throw badRequest(String.format(
                        com.aicabinet.trade.support.ApiMessages.REPLENISHMENT_SLOT_CAPACITY,
                        slotCode, cap, book, quantity));
            }
        }
    }

    /** 设备级告警联系人为空时回落绑定商户联系人/电话（运营后台设备概览用，两条柜型共用）。 */
    private String[] resolveMerchantContacts(DeviceInfo device) {
        var boundMerchant = device.getMerchantId() == null || device.getMerchantId().isBlank()
                ? java.util.Optional.<Merchant>empty()
                : merchantRepository.findById(device.getMerchantId());
        return new String[]{
                boundMerchant.map(Merchant::getAlertContactName).orElse(null),
                boundMerchant.map(Merchant::getContactPhone).orElse(null)
        };
    }

    /**
     * 设备概览指标：在架 SKU 台账口径（**全柜型单源**；货道口径已在第十四刀 B 退场）。
     * 在架 = device_sku_inventory 有行且 sku_catalog.status=ACTIVE（与详情页「已上架商品数」、
     * 消费者目录同一过滤，已下架 SKU 的库存残留不参与）。填充率 = Σmin(库存,容量)×100/Σ容量；
     * 缺货商品 = 库存≤0；低库存商品 = 0<库存≤low_threshold（逐 SKU 阈值，与补货建议
     * suggestFromLowStock / 阈值自动任务 / 上架商品页缺货 tag 同源）。判定与数量源统一走
     * {@link SkuStockLevel}（CB-076：缺货/低库存两档单源，建账 SKU 取批次合计）。
     * DTO 字段名保留 *SlotCount（兼容不动 API），开门柜下语义为「商品」个数。
     */
    private DeviceOpsMetricsDto ledgerStockMetrics(DeviceInfo device) {
        String deviceId = device.getDeviceId();
        DoorCabinetStockStats stats = doorCabinetStockStats(deviceId);
        int fillRate = stats.fillRatePct() == null ? 0 : stats.fillRatePct();
        // 货道维度的 lastRestockAt 对开门柜恒空，回落最近完成补货任务（与有货道路径同款兜底）
        Instant lastRestock = taskRepository.findLastCompletedAtByDeviceId(deviceId).orElse(null);
        String[] merchantContacts = resolveMerchantContacts(device);
        return new DeviceOpsMetricsDto(
                deviceId,
                0,
                0,
                fillRate,
                stats.oosRatePct(),
                stats.oosCount(),
                stats.lowCount(),
                stats.totalBook(),
                stats.totalCap(),
                lastRestock,
                device.getAddress(),
                device.getCurrentTempC(),
                device.getTargetTempC(),
                device.getTempReportedAt(),
                device.salesLockedEnabled(),
                device.getAppVersion(),
                device.getFirmwareVersion(),
                device.getAlertContactName(),
                device.getAlertContactPhone(),
                countNearExpiryLots(deviceId),
                merchantContacts[0],
                merchantContacts[1],
                monthRevenueCents(deviceId)
        );
    }

    /**
     * 开门柜台账口径统计（单源）：在架（sku_catalog.status=ACTIVE）SKU 的缺货/低库存件数与填充量。
     * 判定与数量源统一走 {@link SkuStockLevel}（缺货=可售≤0；低库存=0<可售≤low_threshold；
     * 建过批次账本的 SKU 取账本可售）。设备详情 KPI 与商户柜机卡片都从这里取，避免两套数。
     */
    private DoorCabinetStockStats doorCabinetStockStats(String deviceId) {
        List<DeviceSkuInventory> rows = inventoryRepository.findByIdDeviceId(deviceId);
        if (rows.isEmpty()) {
            return DoorCabinetStockStats.EMPTY;
        }
        Set<String> activeSkuIds = skuCatalogRepository.selectList(
                        Wrappers.<SkuCatalog>lambdaQuery()
                                .in(SkuCatalog::getSkuId, rows.stream()
                                        .map(DeviceSkuInventory::getSkuId)
                                        .toList()))
                .stream()
                .filter(SkuOnShelf::isOnShelf)
                .map(SkuCatalog::getSkuId)
                .collect(Collectors.toSet());
        // 数量源与详情页卡片同源（CB-076）：按「柜 + SKU」粒度判是否建账——整柜判会把
        // 从未进过账本的 SKU 读成 0（2026-10-28 实测 EG943322621391）。
        Set<String> lotLedgerSkus = inventoryLotService.lotLedgerSkuIds(deviceId);
        Map<String, Integer> sellableBySku = lotLedgerSkus.isEmpty()
                ? null
                : inventoryLotService.sellableQtyBySku(deviceId);
        int totalCap = 0;
        int totalBook = 0;
        int oosCount = 0;
        int lowCount = 0;
        int listedCount = 0;
        for (DeviceSkuInventory inv : rows) {
            if (!activeSkuIds.contains(inv.getSkuId())) {
                continue;
            }
            listedCount++;
            int qty = Math.max(SkuStockLevel.sellableQty(inv, lotLedgerSkus, sellableBySku), 0);
            int cap = Math.max(inv.getCapacity(), 0);
            totalBook += Math.min(qty, cap);
            totalCap += cap;
            SkuStockLevel level = SkuStockLevel.of(qty, inv.getLowThreshold());
            if (level == SkuStockLevel.OOS) {
                oosCount++;
            } else if (level == SkuStockLevel.LOW) {
                lowCount++;
            }
        }
        return new DoorCabinetStockStats(listedCount, oosCount, lowCount, totalBook, totalCap);
    }

    private record DoorCabinetStockStats(int listedCount, int oosCount, int lowCount,
                                         int totalBook, int totalCap) {
        static final DoorCabinetStockStats EMPTY = new DoorCabinetStockStats(0, 0, 0, 0, 0);

        Integer fillRatePct() {
            return totalCap > 0 ? totalBook * 100 / totalCap : null;
        }

        int oosRatePct() {
            return listedCount > 0 ? oosCount * 100 / listedCount : 0;
        }
    }

    /**
     * 本月销售额（图2 对照「本月销售额」，2026-10-15）：设备维度当月成交行金额合计。
     * 状态≠FAILED 全算（与账单/销售报表口径一致）；退款不在此扣减（毛成交口径）。
     */
    private long monthRevenueCents(String deviceId) {
        YearMonth ym = YearMonth.now(ZoneId.systemDefault());
        Instant start = ym.atDay(1).atStartOfDay(ZoneId.systemDefault()).toInstant();
        Instant end = ym.plusMonths(1).atDay(1).atStartOfDay(ZoneId.systemDefault()).toInstant();
        return orderLineRepository.sumRevenueByDeviceIdsBetween(java.util.List.of(deviceId), start, end);
    }

    private int countNearExpiryLots(String deviceId) {
        LocalDate nearCutoff = LocalDate.now().plusDays(7);
        return (int) lotRepository.findByDeviceId(deviceId).stream()
                .filter(l -> l.getQuantity() > 0)
                .filter(l -> "NEAR_EXPIRY".equals(l.getStatus())
                        || (l.getExpiryDate() != null && !l.getExpiryDate().isAfter(nearCutoff)
                        && l.getExpiryDate().isAfter(LocalDate.now().minusDays(1))))
                .count();
    }

    /** 商户级缺货阈值（merchant_ops_config.stockout_threshold_pct，默认 50）；配置缺失时回落默认。 */
    private int resolveStockoutThresholdPct(String deviceId) {
        try {
            int pct = opsPolicyService.loadConfig(deviceId).getStockoutThresholdPct();
            return pct > 0 ? Math.min(100, pct) : 50;
        } catch (Exception e) {
            log.warn("resolve stockout threshold fallback device={} err={}", deviceId, e.getMessage());
            return 50;
        }
    }

    private static String resolveStockStatus(DeviceSlot slot, int bookQty) {
        if (!slot.isEnabled() || slot.getParLevel() <= 0) {
            return "DISABLED";
        }
        int cap = slot.getMaxLevel() > 0 ? slot.getMaxLevel() : slot.getParLevel();
        if (cap > 0 && bookQty > cap) {
            return "OVER";
        }
        if (bookQty <= 0) {
            return "OOS";
        }
        if (bookQty <= slot.getMinLevel()) {
            return "LOW";
        }
        if (bookQty >= slot.getParLevel()) {
            return "FULL";
        }
        return "OK";
    }

    private DeviceInventoryDto toInventoryDto(DeviceSkuInventory inv, Map<String, Integer> sellableBySku,
                                              Set<String> lotLedgerSkus, String skuName, String imageUrl) {
        String deviceId = inv.getId().getDeviceId();
        String skuId = inv.getId().getSkuId();
        // 数量源唯一出处（CB-076）：已建账的 SKU 以账本为准（含卖光/过期=0），未建账回落账面，
        // 否则分叉行永远显示 0，而保存路径比的却是账面值——数字看着「改了没反应」。
        int qty = sellableBySku == null && lotLedgerSkus != null && lotLedgerSkus.contains(skuId)
                ? inventoryLotService.sellableQuantity(deviceId, skuId)
                : SkuStockLevel.sellableQty(inv, lotLedgerSkus, sellableBySku);
        return new DeviceInventoryDto(
                deviceId,
                skuId,
                qty,
                inv.getCapacity(),
                inv.getLowThreshold(),
                inv.getUpdatedAt(),
                inv.getVersion(),
                inv.getLastRestockQty(),
                skuName,
                imageUrl
        );
    }

    private AdminDeviceDto toAdminDeviceDto(DeviceInfo d) {
        return toAdminDeviceDto(d, null);
    }

    private AdminDeviceDto toAdminDeviceDto(DeviceInfo d, ShoppingSession lastSession) {
        String merchantName = d.getMerchantId() == null ? null
                : merchantRepository.findById(d.getMerchantId())
                .map(Merchant::getMerchantName).orElse(null);
        String effective = refundPolicyService != null
                ? refundPolicyService.resolveForDevice(d.getDeviceId()).name()
                : null;
        JiangyiDevice jiangyi = jiangyiDeviceDirectory == null ? null : jiangyiDeviceDirectory.findDevice(d.getDeviceId());
        return new AdminDeviceDto(
                d.getDeviceId(), d.getDeviceName(), d.getDeviceType(), d.getSceneType(),
                d.getOnlineStatus(),
                d.getMerchantId(), merchantName,
                lastSession == null ? null : lastSession.getSessionId(),
                lastSession == null ? null : lastSession.getState().name(),
                d.getUpdatedAt(),
                d.getOnlineSince(), d.getLastOfflineAt(), false,
                d.getRefundPolicy(), effective, d.salesLockedEnabled(),
                DeviceAssetService.normalizeLifecycle(d.getLifecycleStatus()),
                d.getImei(), d.getAssetOwner(), d.getCoopMode(),
                d.getDepositCents(), d.getDataFeeCents(),
                d.getOpsTags(), d.getRouteCode(), d.getDeployedAt(), d.getLifecycleRemark(),
                d.getLatitude(), d.getLongitude(), d.getAddress(),
                d.getId(), d.getCurrentTempC(), d.getTargetTempC(), d.getFirmwareVersion(),
                d.getSalesLockReason(), d.getSignalStrength(), d.getHomeWarehouseId(),
                jiangyi == null ? null : jiangyi.getDeviceSn()
        );
    }

    private DeviceInfo requireDevice(String deviceId) {
        return deviceRepository.findById(deviceId.trim())
                .orElseThrow(() -> notFound("device"));
    }

    private static ResponseStatusException notFound(String entity) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, entity + " not found");
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    static String deviceSlotLockKey(String deviceId) {
        return "device:slot:" + deviceId;
    }

    private <T> T runWithDeviceSlotLock(String deviceId, java.util.function.Supplier<T> action) {
        if (!distributedLockService.tryLock(deviceSlotLockKey(deviceId), 60, 5)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "货道处理中，请稍后重试");
        }
        try {
            return action.get();
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage(), e);
        } finally {
            distributedLockService.unlock(deviceSlotLockKey(deviceId));
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
