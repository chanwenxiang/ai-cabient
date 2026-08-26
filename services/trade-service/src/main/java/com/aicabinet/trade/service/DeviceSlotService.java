package com.aicabinet.trade.service;

import com.aicabinet.common.dto.*;
import com.aicabinet.trade.domain.*;
import com.aicabinet.trade.mapper.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDate;
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
        this.self = self;
    }

    @Transactional(readOnly = true)
    public DeviceDetailDto getDeviceDetail(Long operatorId, String deviceId) {
        merchantScopeService.requireDeviceAccess(operatorId, deviceId);
        DeviceInfo device = requireDevice(deviceId);
        Map<String, Integer> bookBySlot = self.loadBookQtyBySlot(deviceId);
        List<DeviceSlot> slots = slotRepository.findByIdDeviceIdOrderByRowNoAscColNoAsc(deviceId);
        List<DeviceSlotDto> slotDtos = slots.stream()
                .map(s -> toSlotDto(s, bookBySlot.getOrDefault(s.getId().getSlotCode(), 0)))
                .toList();
        DeviceOpsMetricsDto metrics = computeMetrics(device, slots, bookBySlot);
        Map<String, Integer> sellableBySku = inventoryLotService.deviceUsesLotLedger(deviceId)
                ? inventoryLotService.sellableQtyBySku(deviceId)
                : null;
        List<DeviceInventoryDto> skuInventory = inventoryRepository.findByIdDeviceId(deviceId).stream()
                .map(inv -> toInventoryDto(inv, sellableBySku))
                .toList();
        return new DeviceDetailDto(toAdminDeviceDto(device), metrics, slotDtos, skuInventory);
    }

    @Transactional(readOnly = true)
    public List<DeviceSlotDto> listSlots(Long operatorId, String deviceId) {
        merchantScopeService.requireDeviceAccess(operatorId, deviceId);
        requireDevice(deviceId);
        Map<String, Integer> bookBySlot = self.loadBookQtyBySlot(deviceId);
        return slotRepository.findByIdDeviceIdOrderByRowNoAscColNoAsc(deviceId).stream()
                .map(s -> toSlotDto(s, bookBySlot.getOrDefault(s.getId().getSlotCode(), 0)))
                .toList();
    }

    @Transactional
    public void deleteSlot(Long operatorId, String deviceId, String slotCode) {
        merchantScopeService.requireDeviceAccess(operatorId, deviceId);
        runWithDeviceSlotLock(deviceId, () -> {
            doDeleteSlot(deviceId, slotCode);
            return null;
        });
    }

    private void doDeleteSlot(String deviceId, String slotCode) {
        requireDevice(deviceId);
        String normalized = slotCode.trim().toUpperCase();
        DeviceSlot slot = slotRepository.findById(new DeviceSlotId(deviceId, normalized))
                .orElseThrow(() -> notFound("slot"));
        int bookQty = self.loadBookQtyBySlot(deviceId).getOrDefault(normalized, 0);
        if (bookQty > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "slot has book inventory, disable instead of delete");
        }
        slotRepository.delete(slot);
    }

    @Transactional(readOnly = true)
    public long countDiscrepancies(Long operatorId) {
        return self.listDiscrepancyAlerts(operatorId, null).size();
    }

    @Transactional(readOnly = true)
    public List<SlotDiscrepancyAlertDto> listDiscrepancyAlerts(Long operatorId, String deviceIdFilter) {
        Set<String> allowed = merchantScopeService.allowedDeviceIds(operatorId);
        if (allowed != null && allowed.isEmpty()) {
            return List.of();
        }
        if (deviceIdFilter != null && !deviceIdFilter.isBlank()) {
            String dev = deviceIdFilter.trim();
            merchantScopeService.requireDeviceAccess(operatorId, dev);
            return buildDiscrepanciesForDevice(dev);
        }
        List<DeviceSlot> candidates = slotRepository.findByEnabledTrueAndLastPhysicalQtyIsNotNull();
        Map<String, String> deviceNames = new HashMap<>();
        List<SlotDiscrepancyAlertDto> alerts = new ArrayList<>();
        for (DeviceSlot slot : candidates) {
            String deviceId = slot.getId().getDeviceId();
            if (allowed != null && !allowed.contains(deviceId)) {
                continue;
            }
            if (!deviceRepository.existsById(deviceId)) {
                continue;
            }
            alerts.addAll(buildDiscrepancyForSlot(slot, self.loadBookQtyBySlot(deviceId), deviceNames));
        }
        alerts.sort(Comparator
                .comparing(SlotDiscrepancyAlertDto::deviceId)
                .thenComparing(SlotDiscrepancyAlertDto::slotCode));
        return alerts;
    }

    private List<SlotDiscrepancyAlertDto> buildDiscrepanciesForDevice(String deviceId) {
        requireDevice(deviceId);
        Map<String, Integer> bookBySlot = self.loadBookQtyBySlot(deviceId);
        Map<String, String> deviceNames = new HashMap<>();
        return slotRepository.findByIdDeviceId(deviceId).stream()
                .flatMap(slot -> buildDiscrepancyForSlot(slot, bookBySlot, deviceNames).stream())
                .sorted(Comparator.comparing(SlotDiscrepancyAlertDto::slotCode))
                .toList();
    }

    private List<SlotDiscrepancyAlertDto> buildDiscrepancyForSlot(DeviceSlot slot,
                                                                    Map<String, Integer> bookBySlot,
                                                                    Map<String, String> deviceNames) {
        if (!slot.isEnabled() || slot.getLastPhysicalQty() == null) {
            return List.of();
        }
        String deviceId = slot.getId().getDeviceId();
        String slotCode = slot.getId().getSlotCode();
        int bookQty = bookBySlot.getOrDefault(slotCode, 0);
        int physicalQty = slot.getLastPhysicalQty();
        if (bookQty == physicalQty) {
            return List.of();
        }
        String deviceName = deviceNames.computeIfAbsent(deviceId, id ->
                deviceRepository.findById(id).map(DeviceInfo::getDeviceName).orElse(id));
        String skuId = slot.getAssignedSkuId();
        String skuName = skuId == null ? null : skuCatalogRepository.findById(skuId)
                .map(SkuCatalog::getSkuName).orElse(null);
        return List.of(new SlotDiscrepancyAlertDto(
                deviceId, deviceName, slotCode, skuId, skuName,
                bookQty, physicalQty, bookQty - physicalQty, slot.getLastPhysicalAt()
        ));
    }

    /** 补货关门快照：批量回写货道实测数量。 */
    @Transactional
    public int applyPhysicalSnapshot(String deviceId, Map<String, Integer> slotPhysical,
                                     String source, String refId) {
        return runWithDeviceSlotLock(deviceId,
                () -> doApplyPhysicalSnapshot(deviceId, slotPhysical, source, refId));
    }

    private int doApplyPhysicalSnapshot(String deviceId, Map<String, Integer> slotPhysical,
                                        String source, String refId) {
        requireDevice(deviceId);
        int updated = 0;
        Instant now = Instant.now();
        for (Map.Entry<String, Integer> entry : slotPhysical.entrySet()) {
            String slotCode = entry.getKey().trim().toUpperCase();
            DeviceSlot slot = slotRepository.findById(new DeviceSlotId(deviceId, slotCode)).orElse(null);
            if (slot == null || !slot.isEnabled()) {
                continue;
            }
            slot.setLastPhysicalQty(Math.max(0, entry.getValue()));
            slot.setLastPhysicalAt(now);
            slotRepository.save(slot);
            updated++;
        }
        if (updated > 0) {
            log.info("physical snapshot device={} source={} ref={} slots={}", deviceId, source, refId, updated);
        }
        return updated;
    }

    /** 无传感数据时：用账面数量刷新实测（降低误报）。 */
    @Transactional
    public int syncPhysicalFromBook(String deviceId, String refId) {
        return runWithDeviceSlotLock(deviceId, () -> {
            Map<String, Integer> bookBySlot = self.loadBookQtyBySlot(deviceId);
            if (bookBySlot.isEmpty()) {
                return 0;
            }
            return doApplyPhysicalSnapshot(deviceId, bookBySlot, "BOOK_SYNC", refId);
        });
    }

    /** 销售扣减后同步货道实测数量（仅更新已有实测值的货道）。 */
    @Transactional
    public void applyPhysicalAfterSale(String deviceId, Map<String, Integer> slotQtySold, String refId) {
        if (slotQtySold == null || slotQtySold.isEmpty()) {
            return;
        }
        Instant now = Instant.now();
        int updated = 0;
        for (Map.Entry<String, Integer> entry : slotQtySold.entrySet()) {
            int sold = entry.getValue() != null ? entry.getValue() : 0;
            if (sold <= 0) {
                continue;
            }
            String slotCode = entry.getKey().trim().toUpperCase();
            DeviceSlot slot = slotRepository.findById(new DeviceSlotId(deviceId, slotCode)).orElse(null);
            if (slot == null || slot.getLastPhysicalQty() == null) {
                continue;
            }
            slot.setLastPhysicalQty(Math.max(0, slot.getLastPhysicalQty() - sold));
            slot.setLastPhysicalAt(now);
            slotRepository.save(slot);
            updated++;
        }
        if (updated > 0) {
            log.info("physical after sale device={} ref={} slots={}", deviceId, refId, updated);
        }
    }

    /**
     * 批次无 slotId 时：按 SKU 绑定货道分摊销售件数并扣减实测。
     * 多货道时优先从账面较高的货道扣。
     */
    @Transactional
    public void applyPhysicalAfterSkuSale(String deviceId, Map<String, Integer> skuQtySold, String refId) {
        if (skuQtySold == null || skuQtySold.isEmpty()) {
            return;
        }
        Map<String, Integer> bookBySlot = self.loadBookQtyBySlot(deviceId);
        Map<String, Integer> slotQtySold = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : skuQtySold.entrySet()) {
            int remaining = entry.getValue() != null ? entry.getValue() : 0;
            if (remaining <= 0 || entry.getKey() == null || entry.getKey().isBlank()) {
                continue;
            }
            List<DeviceSlot> slots = slotRepository.findByIdDeviceIdOrderByRowNoAscColNoAsc(deviceId).stream()
                    .filter(s -> s.isEnabled() && entry.getKey().equals(s.getAssignedSkuId()))
                    .sorted((a, b) -> Integer.compare(
                            bookBySlot.getOrDefault(b.getId().getSlotCode(), 0),
                            bookBySlot.getOrDefault(a.getId().getSlotCode(), 0)))
                    .toList();
            if (slots.isEmpty()) {
                continue;
            }
            for (DeviceSlot slot : slots) {
                if (remaining <= 0) {
                    break;
                }
                String code = slot.getId().getSlotCode();
                int book = bookBySlot.getOrDefault(code, 0);
                int take = (slots.size() == 1 || book <= 0) ? remaining : Math.min(book, remaining);
                slotQtySold.merge(code, take, Integer::sum);
                remaining -= take;
            }
            if (remaining > 0) {
                slotQtySold.merge(slots.get(0).getId().getSlotCode(), remaining, Integer::sum);
            }
        }
        self.applyPhysicalAfterSale(deviceId, slotQtySold, refId);
    }

    /** 退货/免单回库后同步货道实测（已有实测值则加回）。 */
    @Transactional
    public void applyPhysicalAfterRestore(String deviceId, Map<String, Integer> slotQtyRestored, String refId) {
        if (slotQtyRestored == null || slotQtyRestored.isEmpty()) {
            return;
        }
        Instant now = Instant.now();
        int updated = 0;
        for (Map.Entry<String, Integer> entry : slotQtyRestored.entrySet()) {
            int qty = entry.getValue() != null ? entry.getValue() : 0;
            if (qty <= 0 || entry.getKey() == null || entry.getKey().isBlank()) {
                continue;
            }
            String slotCode = entry.getKey().trim().toUpperCase();
            DeviceSlot slot = slotRepository.findById(new DeviceSlotId(deviceId, slotCode)).orElse(null);
            if (slot == null || slot.getLastPhysicalQty() == null) {
                continue;
            }
            slot.setLastPhysicalQty(slot.getLastPhysicalQty() + qty);
            slot.setLastPhysicalAt(now);
            slotRepository.save(slot);
            updated++;
        }
        if (updated > 0) {
            log.info("physical after restore device={} ref={} slots={}", deviceId, refId, updated);
        }
    }

    /**
     * 退货批次无 slotId 时：按 SKU 绑定货道分摊回加实测。
     */
    @Transactional
    public void applyPhysicalAfterSkuRestore(String deviceId, Map<String, Integer> skuQtyRestored, String refId) {
        if (skuQtyRestored == null || skuQtyRestored.isEmpty()) {
            return;
        }
        Map<String, Integer> bookBySlot = self.loadBookQtyBySlot(deviceId);
        Map<String, Integer> slotQty = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : skuQtyRestored.entrySet()) {
            int remaining = entry.getValue() != null ? entry.getValue() : 0;
            if (remaining <= 0 || entry.getKey() == null || entry.getKey().isBlank()) {
                continue;
            }
            List<DeviceSlot> slots = slotRepository.findByIdDeviceIdOrderByRowNoAscColNoAsc(deviceId).stream()
                    .filter(s -> s.isEnabled() && entry.getKey().equals(s.getAssignedSkuId()))
                    .sorted((a, b) -> Integer.compare(
                            bookBySlot.getOrDefault(b.getId().getSlotCode(), 0),
                            bookBySlot.getOrDefault(a.getId().getSlotCode(), 0)))
                    .toList();
            if (slots.isEmpty()) {
                continue;
            }
            String code = slots.get(0).getId().getSlotCode();
            slotQty.merge(code, remaining, Integer::sum);
        }
        self.applyPhysicalAfterRestore(deviceId, slotQty, refId);
    }

    /** 视觉 SKU 总量按货道账面比例分摊到各货道实测。 */
    @Transactional
    public int allocateSkuCountsToSlots(String deviceId, Map<String, Integer> skuTotals,
                                        String source, String refId) {
        return runWithDeviceSlotLock(deviceId, () -> doAllocateSkuCountsToSlots(deviceId, skuTotals, source, refId));
    }

    private int doAllocateSkuCountsToSlots(String deviceId, Map<String, Integer> skuTotals,
                                           String source, String refId) {
        requireDevice(deviceId);
        Map<String, Integer> bookBySlot = self.loadBookQtyBySlot(deviceId);
        Map<String, Integer> physical = new HashMap<>();
        for (Map.Entry<String, Integer> skuEntry : skuTotals.entrySet()) {
            String skuId = skuEntry.getKey();
            int skuTotal = skuEntry.getValue();
            List<SlotBookView> slots = self.listEnabledSlotsForSku(deviceId, skuId);
            if (slots.isEmpty()) {
                continue;
            }
            int totalBook = slots.stream().mapToInt(s -> bookBySlot.getOrDefault(s.slotCode(), 0)).sum();
            if (totalBook <= 0) {
                physical.put(slots.get(0).slotCode(), skuTotal);
                continue;
            }
            int remaining = skuTotal;
            for (int i = 0; i < slots.size(); i++) {
                SlotBookView slot = slots.get(i);
                int book = bookBySlot.getOrDefault(slot.slotCode(), 0);
                int alloc = (i == slots.size() - 1)
                        ? remaining
                        : Math.min(remaining, (skuTotal * book) / totalBook);
                physical.put(slot.slotCode(), alloc);
                remaining -= alloc;
            }
        }
        return doApplyPhysicalSnapshot(deviceId, physical, source, refId);
    }

    @Transactional(readOnly = true)
    public Map<String, Integer> loadBookQtyBySlot(String deviceId) {
        return lotRepository.sumBookQtyBySlot(deviceId).stream()
                .collect(Collectors.toMap(
                        row -> (String) row[0],
                        row -> ((Number) row[1]).intValue(),
                        Integer::sum
                ));
    }

    @Transactional(readOnly = true)
    public List<SlotBookView> listEnabledSlotsForSku(String deviceId, String skuId) {
        return slotRepository.findByIdDeviceIdOrderByRowNoAscColNoAsc(deviceId).stream()
                .filter(s -> s.isEnabled() && skuId.equals(s.getAssignedSkuId()))
                .map(s -> new SlotBookView(s.getId().getSlotCode(), s.getParLevel()))
                .toList();
    }

    @Transactional(readOnly = true)
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
                    int cap = s.getMaxLevel() > 0 ? s.getMaxLevel()
                            : (s.getParLevel() > 0 ? s.getParLevel() : Integer.MAX_VALUE);
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
        int cap = slot.getMaxLevel() > 0 ? slot.getMaxLevel()
                : (slot.getParLevel() > 0 ? slot.getParLevel() : 0);
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
        requireDevice(deviceId);
        Map<String, Integer> bookBySlot = self.loadBookQtyBySlot(deviceId);
        Map<String, SalesVelocityService.SkuVelocity> velocityBySku = salesVelocityService.velocityBySku(deviceId);
        return slotRepository.findByIdDeviceIdOrderByRowNoAscColNoAsc(deviceId).stream()
                .filter(DeviceSlot::isEnabled)
                .filter(s -> s.getAssignedSkuId() != null && !s.getAssignedSkuId().isBlank())
                .filter(s -> s.getParLevel() > 0)
                .map(s -> buildSlotSuggest(deviceId, s, bookBySlot, velocityBySku, true))
                .filter(s -> s.suggestQty() > 0)
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
        boolean parLow = bookQty <= s.getMinLevel();
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
            } else if (ropLow && parLow) {
                reason = "PAR+ROP";
            }
        }
        return new SlotReplenishmentSuggestDto(
                deviceId, slotCode, skuId,
                bookQty, s.getParLevel(), s.getMinLevel(), s.getMaxLevel(),
                suggestQty, resolveStockStatus(s, bookQty),
                velocity.soldQty7d(), velocity.ropPoint(), reason);
    }

    @Transactional
    public List<DeviceSlotDto> upsertSlots(Long operatorId, String deviceId, List<UpsertDeviceSlotRequest> requests) {
        merchantScopeService.requireDeviceAccess(operatorId, deviceId);
        return runWithDeviceSlotLock(deviceId, () -> doUpsertSlots(operatorId, deviceId, requests));
    }

    private List<DeviceSlotDto> doUpsertSlots(Long operatorId, String deviceId, List<UpsertDeviceSlotRequest> requests) {
        requireDevice(deviceId);
        if (requests == null || requests.isEmpty()) {
            throw badRequest("slots required");
        }
        for (UpsertDeviceSlotRequest req : requests) {
            validateUpsert(req);
            String slotCode = req.slotCode().trim().toUpperCase();
            DeviceSlotId id = new DeviceSlotId(deviceId, slotCode);
            DeviceSlot slot = slotRepository.findById(id).orElseGet(() -> {
                DeviceSlot created = new DeviceSlot();
                created.setId(id);
                return created;
            });
            if (req.rowNo() != null) slot.setRowNo(req.rowNo());
            if (req.colNo() != null) slot.setColNo(req.colNo());
            if (req.slotType() != null && !req.slotType().isBlank()) {
                slot.setSlotType(req.slotType().trim().toUpperCase());
            }
            if (req.assignedSkuId() != null) {
                String sku = req.assignedSkuId().isBlank() ? null : req.assignedSkuId().trim();
                if (sku != null) {
                    skuCatalogRepository.findById(sku)
                            .orElseThrow(() -> badRequest("sku not found: " + sku));
                }
                slot.setAssignedSkuId(sku);
            }
            if (req.parLevel() != null) slot.setParLevel(req.parLevel());
            if (req.minLevel() != null) slot.setMinLevel(req.minLevel());
            if (req.maxLevel() != null) slot.setMaxLevel(req.maxLevel());
            if (req.enabled() != null) slot.setEnabled(req.enabled());
            if (slot.getMaxLevel() <= 0 && slot.getParLevel() > 0) {
                slot.setMaxLevel(slot.getParLevel());
            }
            slotRepository.save(slot);
        }
        return self.listSlots(operatorId, deviceId);
    }

    @Transactional
    public DeviceSlotDto stocktakeSlot(Long operatorId, String deviceId, SlotStocktakeRequest request) {
        merchantScopeService.requireDeviceAccess(operatorId, deviceId);
        return runWithDeviceInventoryLock(deviceId, () -> doStocktakeSlot(operatorId, deviceId, request));
    }

    private DeviceSlotDto doStocktakeSlot(Long operatorId, String deviceId, SlotStocktakeRequest request) {
        requireDevice(deviceId);
        opsPolicyService.requirePhotoEvidence(deviceId, true, request.photoEvidenceUrl());
        String slotCode = request.slotCode().trim().toUpperCase();
        DeviceSlot slot = slotRepository.findById(new DeviceSlotId(deviceId, slotCode))
                .orElseThrow(() -> notFound("slot"));
        assertQuantityWithinSlotCapacity(slot, slotCode, request.physicalQty());
        slot.setLastPhysicalQty(request.physicalQty());
        slot.setLastPhysicalAt(Instant.now());
        slotRepository.save(slot);

        if (Boolean.TRUE.equals(request.adjustBookQty())) {
            String skuId = slot.getAssignedSkuId();
            if (skuId == null || skuId.isBlank()) {
                throw badRequest("货道未绑定商品，无法按实盘调账面");
            }
            String refId = "ST-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
            inventoryLotService.stocktakeAdjustForSlot(
                    deviceId, skuId.trim(), slotCode, request.physicalQty(), operatorId, refId);
            log.info("slot stocktake adjusted device={} slot={} sku={} physical={} ref={}",
                    deviceId, slotCode, skuId, request.physicalQty(), refId);
        }

        int bookQty = self.loadBookQtyBySlot(deviceId).getOrDefault(slotCode, 0);
        return toSlotDto(slot, bookQty);
    }

    /** 补货完成时刷新通道快照。 */
    @Transactional
    public void recordRestock(String deviceId, String slotCode) {
        if (slotCode == null || slotCode.isBlank()) {
            return;
        }
        runWithDeviceSlotLock(deviceId, () -> {
            doRecordRestock(deviceId, slotCode);
            return null;
        });
    }

    /**
     * 校正账面/实盘：超 max_level 的货道优先挪到同 SKU 空位，仍放不下则盘亏到上限。
     */
    @Transactional
    public int clampDeviceOverCapacity(String deviceId) {
        return runWithDeviceSlotLock(deviceId, () -> doClampDeviceOverCapacity(deviceId));
    }

    private int doClampDeviceOverCapacity(String deviceId) {
        requireDevice(deviceId);
        int slotsFixed = 0;
        String refId = "CLAMP-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        for (DeviceSlot slot : slotRepository.findByIdDeviceIdOrderByRowNoAscColNoAsc(deviceId)) {
            if (!slot.isEnabled()) {
                continue;
            }
            String slotCode = slot.getId().getSlotCode();
            int cap = resolveSlotCapacity(slot);
            if (cap <= 0) {
                continue;
            }
            String skuId = slot.getAssignedSkuId();
            if (skuId == null || skuId.isBlank()) {
                continue;
            }
            int book = self.loadBookQtyBySlot(deviceId).getOrDefault(slotCode, 0);
            if (book <= cap) {
                continue;
            }
            int surplus = book - cap;
            for (SlotRestockAllocation alloc : self.allocateRestockQuantity(deviceId, skuId, surplus)) {
                inventoryLotService.transferBetweenSlots(
                        deviceId, skuId, slotCode, alloc.slotCode(), alloc.quantity(), null, refId);
                surplus -= alloc.quantity();
            }
            book = self.loadBookQtyBySlot(deviceId).getOrDefault(slotCode, 0);
            if (book > cap) {
                inventoryLotService.stocktakeAdjustForSlot(deviceId, skuId, slotCode, cap, null, refId);
            }
            slotsFixed++;
            log.warn("clamp over-capacity slot device={} slot={} cap={} ref={}", deviceId, slotCode, cap, refId);
        }
        if (slotsFixed > 0) {
            self.syncPhysicalFromBook(deviceId, refId);
        }
        return slotsFixed;
    }

    private static int resolveSlotCapacity(DeviceSlot slot) {
        if (slot.getMaxLevel() > 0) {
            return slot.getMaxLevel();
        }
        return slot.getParLevel() > 0 ? slot.getParLevel() : 0;
    }

    private static void assertQuantityWithinSlotCapacity(DeviceSlot slot, String slotCode, int qty) {
        int cap = resolveSlotCapacity(slot);
        if (cap > 0 && qty > cap) {
            throw badRequest(String.format(
                    com.aicabinet.trade.support.ApiMessages.SLOT_QTY_OVER_CAPACITY, slotCode, cap, qty));
        }
    }

    private void doRecordRestock(String deviceId, String slotCode) {
        String normalized = slotCode.trim().toUpperCase();
        slotRepository.findById(new DeviceSlotId(deviceId, normalized)).ifPresent(slot -> {
            int bookQty = self.loadBookQtyBySlot(deviceId).getOrDefault(normalized, 0);
            slot.setLastRestockAt(Instant.now());
            slot.setLastPhysicalQty(bookQty);
            slot.setLastPhysicalAt(Instant.now());
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
        int cap = slot.getMaxLevel() > 0 ? slot.getMaxLevel()
                : (slot.getParLevel() > 0 ? slot.getParLevel() : 0);
        if (cap > 0) {
            int book = self.loadBookQtyBySlot(deviceId).getOrDefault(slotCode, 0);
            if (book + quantity > cap) {
                throw badRequest(String.format(
                        com.aicabinet.trade.support.ApiMessages.REPLENISHMENT_SLOT_CAPACITY,
                        slotCode, cap, book, quantity));
            }
        }
    }

    @Transactional
    public void ensureDefaultSlots(String deviceId) {
        self.ensureDefaultSlots(deviceId, PlanogramTemplateService.DEFAULT_DEVICE_TYPE);
    }

    /** 新设备无货道时按设备类型套用默认 planogram。 */
    @Transactional
    public void ensureDefaultSlots(String deviceId, String deviceType) {
        runWithDeviceSlotLock(deviceId, () -> {
            doEnsureDefaultSlots(deviceId, deviceType);
            return null;
        });
    }

    private void doEnsureDefaultSlots(String deviceId, String deviceType) {
        if (slotRepository.countByIdDeviceIdAndEnabledTrue(deviceId) > 0) {
            return;
        }
        doApplyPlanogramTemplate(deviceId, deviceType);
    }

    /** 强制套用模板（仅填充缺失货道，不覆盖已有）。 */
    @Transactional
    public int applyPlanogramTemplate(String deviceId, String deviceType) {
        return runWithDeviceSlotLock(deviceId, () -> doApplyPlanogramTemplate(deviceId, deviceType));
    }

    private int doApplyPlanogramTemplate(String deviceId, String deviceType) {
        requireDevice(deviceId);
        int created = 0;
        for (UpsertDeviceSlotRequest req : PlanogramTemplateService.templateFor(deviceType)) {
            DeviceSlotId id = new DeviceSlotId(deviceId, req.slotCode());
            if (slotRepository.existsById(id)) {
                continue;
            }
            DeviceSlot slot = new DeviceSlot();
            slot.setId(id);
            slot.setRowNo(req.rowNo());
            slot.setColNo(req.colNo());
            slot.setAssignedSkuId(req.assignedSkuId());
            slot.setParLevel(req.parLevel());
            slot.setMinLevel(req.minLevel());
            slot.setMaxLevel(req.maxLevel() != null && req.maxLevel() > 0 ? req.maxLevel() : req.parLevel());
            slot.setEnabled(req.enabled() == null || req.enabled());
            slotRepository.save(slot);
            created++;
        }
        if (created > 0) {
            log.info("planogram template applied device={} type={} slots={}", deviceId, deviceType, created);
        }
        return created;
    }

    /** 运营端：按设备类型套用 planogram（仅填充缺失货道）。 */
    @Transactional
    public int applyPlanogramTemplate(Long operatorId, String deviceId) {
        merchantScopeService.requireDeviceAccess(operatorId, deviceId);
        DeviceInfo device = requireDevice(deviceId);
        return runWithDeviceSlotLock(deviceId, () -> doApplyPlanogramTemplate(deviceId, device.getDeviceType()));
    }

    private DeviceOpsMetricsDto computeMetrics(DeviceInfo device, List<DeviceSlot> slots,
                                               Map<String, Integer> bookBySlot) {
        String deviceId = device.getDeviceId();
        List<DeviceSlot> active = slots.stream()
                .filter(s -> s.isEnabled() && s.getParLevel() > 0)
                .toList();
        int totalPar = 0;
        int totalBook = 0;
        int oosCount = 0;
        int lowCount = 0;
        int accuracySum = 0;
        int accuracySlots = 0;

        for (DeviceSlot slot : active) {
            String code = slot.getId().getSlotCode();
            int par = slot.getParLevel();
            int book = bookBySlot.getOrDefault(code, 0);
            totalPar += par;
            totalBook += Math.min(book, par);
            if (book <= 0) {
                oosCount++;
            } else if (book <= slot.getMinLevel()) {
                lowCount++;
            }
            if (slot.getLastPhysicalQty() != null) {
                accuracySlots++;
                if (book == 0 && slot.getLastPhysicalQty() == 0) {
                    accuracySum += 100;
                } else if (book > 0) {
                    int diff = Math.abs(book - slot.getLastPhysicalQty());
                    accuracySum += Math.max(0, 100 - (diff * 100 / book));
                }
            }
        }

        int fillRate = totalPar > 0 ? (totalBook * 100 / totalPar) : 0;
        int oosRate = active.isEmpty() ? 0 : (oosCount * 100 / active.size());
        int accuracy = accuracySlots > 0 ? (accuracySum / accuracySlots) : 100;

        Instant lastRestock = slots.stream()
                .map(DeviceSlot::getLastRestockAt)
                .filter(Objects::nonNull)
                .max(Instant::compareTo)
                .orElseGet(() -> taskRepository.findLastCompletedAtByDeviceId(deviceId).orElse(null));

        LocalDate nearCutoff = LocalDate.now().plusDays(7);
        int nearExpiryLotCount = (int) lotRepository.findByDeviceId(deviceId).stream()
                .filter(l -> l.getQuantity() > 0)
                .filter(l -> "NEAR_EXPIRY".equals(l.getStatus())
                        || (l.getExpiryDate() != null && !l.getExpiryDate().isAfter(nearCutoff)
                        && l.getExpiryDate().isAfter(LocalDate.now().minusDays(1))))
                .count();

        return new DeviceOpsMetricsDto(
                deviceId,
                slots.size(),
                active.size(),
                fillRate,
                oosRate,
                oosCount,
                lowCount,
                bookBySlot.values().stream().mapToInt(Integer::intValue).sum(),
                totalPar,
                lastRestock,
                accuracy,
                device.getAddress(),
                device.getCurrentTempC(),
                device.getTargetTempC(),
                device.getTempReportedAt(),
                device.salesLockedEnabled(),
                device.getAppVersion(),
                device.getFirmwareVersion(),
                device.getAlertContactName(),
                device.getAlertContactPhone(),
                nearExpiryLotCount
        );
    }

    private DeviceSlotDto toSlotDto(DeviceSlot slot, int bookQty) {
        String deviceId = slot.getId().getDeviceId();
        String slotCode = slot.getId().getSlotCode();
        String skuId = slot.getAssignedSkuId();
        String skuName = skuId == null ? null : skuCatalogRepository.findById(skuId)
                .map(SkuCatalog::getSkuName).orElse(null);
        int par = slot.getParLevel();
        int fillRate = par > 0 ? (Math.min(bookQty, par) * 100 / par) : 0;
        String stockStatus = resolveStockStatus(slot, bookQty);
        Integer physical = slot.getLastPhysicalQty();
        int qtyDiff = physical != null ? bookQty - physical : 0;
        boolean hasDiscrepancy = physical != null && physical != bookQty;
        return new DeviceSlotDto(
                deviceId, slotCode, slot.getRowNo(), slot.getColNo(), slot.getSlotType(),
                skuId, skuName, par, slot.getMinLevel(), slot.getMaxLevel(), slot.isEnabled(),
                bookQty, physical, slot.getLastPhysicalAt(), slot.getLastRestockAt(),
                fillRate, stockStatus, qtyDiff, hasDiscrepancy
        );
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

    private DeviceInventoryDto toInventoryDto(DeviceSkuInventory inv, Map<String, Integer> sellableBySku) {
        String deviceId = inv.getId().getDeviceId();
        String skuId = inv.getId().getSkuId();
        int qty = inv.getQuantity();
        if (sellableBySku != null) {
            qty = sellableBySku.getOrDefault(skuId, 0);
        } else if (inventoryLotService.deviceUsesLotLedger(deviceId)) {
            qty = inventoryLotService.sellableQuantity(deviceId, skuId);
        }
        return new DeviceInventoryDto(
                deviceId,
                skuId,
                qty,
                inv.getCapacity(),
                inv.getLowThreshold(),
                inv.getUpdatedAt()
        );
    }

    private AdminDeviceDto toAdminDeviceDto(DeviceInfo d) {
        String merchantName = d.getMerchantId() == null ? null
                : merchantRepository.findById(d.getMerchantId())
                .map(Merchant::getMerchantName).orElse(null);
        String effective = refundPolicyService != null
                ? refundPolicyService.resolveForDevice(d.getDeviceId()).name()
                : null;
        return new AdminDeviceDto(
                d.getDeviceId(), d.getDeviceName(), d.getDeviceType(), d.getOnlineStatus(),
                d.getMerchantId(), merchantName, null, null, d.getUpdatedAt(), false,
                d.getRefundPolicy(), effective, d.salesLockedEnabled()
        );
    }

    private DeviceInfo requireDevice(String deviceId) {
        return deviceRepository.findById(deviceId.trim())
                .orElseThrow(() -> notFound("device"));
    }

    private static void validateUpsert(UpsertDeviceSlotRequest req) {
        if (req.slotCode() == null || req.slotCode().isBlank()) {
            throw badRequest("slotCode required");
        }
        if (req.parLevel() != null && req.parLevel() < 0) {
            throw badRequest("parLevel must be >= 0");
        }
        if (req.minLevel() != null && req.minLevel() < 0) {
            throw badRequest("minLevel must be >= 0");
        }
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

    private <T> T runWithDeviceInventoryLock(String deviceId, java.util.function.Supplier<T> action) {
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
}
