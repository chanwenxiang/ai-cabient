package com.aicabinet.trade.service;

import com.aicabinet.common.dto.DeviceProductDto;
import com.aicabinet.common.dto.NearbyDeviceDto;
import com.aicabinet.trade.domain.DeviceSkuInventory;
import com.aicabinet.trade.domain.SkuCatalog;
import com.aicabinet.trade.mapper.DeviceSkuInventoryMapper;
import com.aicabinet.trade.mapper.DeviceSkuLotMapper;
import com.aicabinet.trade.mapper.SkuCatalogMapper;
import com.aicabinet.trade.support.SkuOnShelf;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class DeviceCatalogService {

    private final DeviceSkuInventoryMapper inventoryRepository;
    private final DeviceSkuLotMapper lotRepository;
    private final SkuCatalogMapper skuCatalogRepository;
    private final MerchantSkuPricingService skuPricingService;
    private final SysDictService sysDictService;
    private final PromotionPricingService promotionPricingService;
    /** 会员价与活动价**不叠加、取优**，与结算链 {@code SettlementOrderSupport} 同一套判据，否则「显示价≠实付」。 */
    private final MemberService memberService;

    public DeviceCatalogService(DeviceSkuInventoryMapper inventoryRepository,
                                DeviceSkuLotMapper lotRepository,
                                SkuCatalogMapper skuCatalogRepository,
                                MerchantSkuPricingService skuPricingService,
                                SysDictService sysDictService,
                                PromotionPricingService promotionPricingService,
                                MemberService memberService) {
        this.inventoryRepository = inventoryRepository;
        this.lotRepository = lotRepository;
        this.skuCatalogRepository = skuCatalogRepository;
        this.skuPricingService = skuPricingService;
        this.sysDictService = sysDictService;
        this.promotionPricingService = promotionPricingService;
        this.memberService = memberService;
    }

    /** 无登录上下文（附近柜机预览等）：活动价按不限人次口径展示，结算仍以服务端命中为准。 */
    public List<DeviceProductDto> listProducts(String deviceId) {
        return listProducts(deviceId, null);
    }

    /**
     * 消费者可见商品库存：与运营后台货道「账面」同源（可售批次 ON_SALE/NEAR_EXPIRY），
     * 但**只统计启用货道** —— 运营在设备详情里关掉的货道，其商品不再对消费者可见。
     * 若柜机尚无批次账本，则回退到 device_sku_inventory（旧数据兼容）。
     */
    @Transactional(readOnly = true)
    public List<DeviceProductDto> listProducts(String deviceId, Long userId) {
        String dev = deviceId != null ? deviceId.trim() : "";
        if (dev.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "deviceId required");
        }

        Map<String, Integer> qtyBySku = sellableQtyBySkuOnEnabledSlots(dev);
        boolean useLots = !lotRepository.findByDeviceId(dev).isEmpty();
        if (!useLots) {
            qtyBySku = inventoryQtyBySku(dev);
        }

        List<String> skuIds = qtyBySku.entrySet().stream()
                .filter(e -> e.getValue() != null && e.getValue() > 0)
                .map(Map.Entry::getKey)
                .toList();
        if (skuIds.isEmpty()) {
            return List.of();
        }

        Map<String, SkuCatalog> skuMap = skuCatalogRepository.findAllById(skuIds).stream()
                .filter(SkuOnShelf::isOnShelf)
                .collect(Collectors.toMap(SkuCatalog::getSkuId, s -> s));

        List<DeviceProductDto> result = new ArrayList<>();
        // 促销策略快照读一次、循环内复用：配置是直读 DB 无缓存的，逐 SKU 读会放大成 N×4 次查库。
        PricingPromoPolicy promoPolicy = skuPricingService.loadPromoPolicy();
        // 分类字典映射一次取全（2026-10-22）：sku_catalog.category 存的是 category_code 字典值（如 1），
        // 消费者端直接显示会露出「1」这种字典码；此处统一翻译成中文标签（如「饮料」）。
        // 字典没配的值（历史自由文本如「零食」）原样返回，不吞掉。
        Map<String, String> categoryLabels = sysDictService.labelMapOf("category_code");
        // CB-082：活动定价的设备→商户只解析一次，逐 SKU 只查候选活动
        String merchantId = promotionPricingService.resolveMerchantId(dev);
        for (String skuId : skuIds) {
            SkuCatalog sku = skuMap.get(skuId);
            int qty = qtyBySku.getOrDefault(skuId, 0);
            if (sku != null && qty > 0) {
                int basePrice = skuPricingService.resolveUnitPriceCents(dev, sku, promoPolicy);
                // 会员价与活动价**不叠加、取优**（2026-11-12 拍板，与结算链
                // SettlementOrderSupport.applyItemsToOrder 同口径）：活动价按目录价算，再与会员价
                // 取更低者；活动价不优于会员价时 activityPriceCents 下发 null ⇒ 卡片只显示会员价。
                int memberPrice = memberService.applyMemberPriceDiscount(userId, basePrice);
                var applied = promotionPricingService.resolveBest(userId, dev, merchantId, skuId,
                        basePrice, memberPrice);
                result.add(new DeviceProductDto(
                    sku.getSkuId(),
                    sku.getSkuName(),
                    sku.getSpec(),
                    sku.getUnit(),
                    // MA-023：priceCents = **本用户无活动时的应付单价**（会员价，无会员折扣时即目录价），
                    // 与结算侧 `SettlementOrderSupport:90` 取的会员价同值。此前这里下发目录价，
                    // 而会员实际按 98 折结算 ⇒ 商品卡「原价 3.50 + 活动立减 1.00」算不出实付 2.43
                    // （差的 0.07 是没入账的会员折扣）。划线价必须能减出实付，否则等于虚标原价。
                    memberPrice,
                    qty,
                    sku.getImageUrl(),
                    displayCategory(sku.getCategory(), categoryLabels),
                    sku.getDescription(),
                    applied.map(PromotionPricingService.Applied::unitPriceCents).orElse(null),
                    applied.map(PromotionPricingService.Applied::activityId).orElse(null)
            ));
            }
        }
        result.sort((a, b) -> a.skuName().compareToIgnoreCase(b.skuName()));
        return result;
    }

    /** 附近柜预览结果：整柜可售总件数 + 前 N 个商品（口径与 {@link #listProducts(String, Long)} 逐字一致）。 */
    public record DevicePreview(int itemCount, int skuCount, List<NearbyDeviceDto.PreviewSku> preview) {}

    /**
     * **附近柜列表的批量预览**（2026-11-14）：一次问多台的可售量 + 只给前 N 个商品算价。
     *
     * <p>为什么不是「给 listProducts 套个循环」：原路径每台 6 条通用查询 + 每 SKU 约 4 条价格/批次查询
     * （覆盖价、FEFO 批次、活动候选、限购），50 台 × 8 SKU ≈ 1900 条；而附近页实际只用
     * 「总件数 + 前 3 个商品的名称/件数/单价」。这里把可售量与 SKU 目录按 device 集合一次取，
     * 价格只对前 N 个算（预览不展示活动价字段，故**不需要** resolveBest/限购查询）。
     *
     * <p>🔴 口径等价性由 {@code NearbyPreviewBatchSqlIT}（真 PG）守着：逐台断言
     * 「批量预览 == 由 listProducts 现算出的预览」，含 itemCount 与每个 SKU 的 priceCents。
     * 谁改这里的取数口径（去禁用货道、在售状态、清仓折扣），那份 IT 必红。
     */
    @Transactional(readOnly = true)
    public Map<String, DevicePreview> previewByDevices(java.util.Collection<String> deviceIds, Long userId, int limit) {
        List<String> ids = deviceIds == null ? List.of() : deviceIds.stream()
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        int topN = Math.max(1, Math.min(limit, 10));

        // 1) 可售量：lot 口径按批一次取；没有批次账本的柜回退聚合行（与 listProducts 同一条分支判据）
        Map<String, Map<String, Integer>> qtyByDevice = new HashMap<>();
        java.util.Set<String> withLots = lotRepository.deviceIdsHavingLots(ids);
        for (Object[] row : lotRepository.sumSellableBySkuOnEnabledSlotsByDevices(ids)) {
            if (row == null || row.length < 3 || row[0] == null || row[1] == null || row[2] == null) {
                continue;
            }
            qtyByDevice.computeIfAbsent(String.valueOf(row[0]), k -> new HashMap<>())
                    .merge(String.valueOf(row[1]), ((Number) row[2]).intValue(), Integer::sum);
        }
        List<String> legacy = ids.stream().filter(id -> !withLots.contains(id)).toList();
        if (!legacy.isEmpty()) {
            for (DeviceSkuInventory inv : inventoryRepository.findByIdDeviceIdIn(legacy)) {
                if (inv.getQuantity() > 0) {
                    qtyByDevice.computeIfAbsent(inv.getId().getDeviceId(), k -> new HashMap<>())
                            .put(inv.getId().getSkuId(), inv.getQuantity());
                }
            }
        }

        // 2) SKU 目录一次取（与 listProducts 同一过滤：在架 + 有量）
        java.util.Set<String> allSkuIds = qtyByDevice.values().stream()
                .flatMap(m -> m.keySet().stream())
                .collect(java.util.stream.Collectors.toSet());
        Map<String, SkuCatalog> skuMap = allSkuIds.isEmpty() ? Map.of()
                : skuCatalogRepository.findAllById(allSkuIds).stream()
                        .filter(SkuOnShelf::isOnShelf)
                        .collect(Collectors.toMap(SkuCatalog::getSkuId, s -> s, (a, b) -> a));

        // 3) 价格策略快照一次读（与 listProducts 同：循环内复用，不逐 SKU 查配置）
        PricingPromoPolicy promoPolicy = skuPricingService.loadPromoPolicy();

        Map<String, DevicePreview> out = new HashMap<>();
        for (String dev : ids) {
            Map<String, Integer> qtyBySku = qtyByDevice.getOrDefault(dev, Map.of());
            List<SkuCatalog> onSale = qtyBySku.entrySet().stream()
                    .filter(e -> e.getValue() != null && e.getValue() > 0 && skuMap.containsKey(e.getKey()))
                    .map(e -> skuMap.get(e.getKey()))
                    .sorted((a, b) -> a.getSkuName().compareToIgnoreCase(b.getSkuName())) // listProducts 的排序口径
                    .toList();
            int itemCount = onSale.stream().mapToInt(s -> qtyBySku.getOrDefault(s.getSkuId(), 0)).sum();
            // 与 NearbyDeviceService 的 preview 排序逐字一致：按件数降序（同分保持 listProducts 的名称序）
            List<NearbyDeviceDto.PreviewSku> preview = onSale.stream()
                    .sorted((a, b) -> Integer.compare(
                            qtyBySku.getOrDefault(b.getSkuId(), 0), qtyBySku.getOrDefault(a.getSkuId(), 0)))
                    .limit(topN)
                    .map(sku -> {
                        int base = skuPricingService.resolveUnitPriceCents(dev, sku, promoPolicy);
                        int memberPrice = memberService.applyMemberPriceDiscount(userId, base);
                        return new NearbyDeviceDto.PreviewSku(
                                sku.getSkuId(), sku.getSkuName(),
                                qtyBySku.getOrDefault(sku.getSkuId(), 0), memberPrice);
                    })
                    .toList();
            out.put(dev, new DevicePreview(itemCount, onSale.size(), preview));
        }
        return out;
    }

    /**
     * 消费者口径的可售量：**只统计启用货道**上的批次。
     *
     * <p>刻意不复用 {@code sumSellableBySku}（运营口径的物理库存全量）：那个口径下禁用货道也要算，
     * 拿来当消费者可见量会让「禁用货道」开关失效 —— 禁用后小程序照样显示（线上实测过）。
     */
    private Map<String, Integer> sellableQtyBySkuOnEnabledSlots(String deviceId) {
        Map<String, Integer> map = new HashMap<>();
        for (Object[] row : lotRepository.sumSellableBySkuOnEnabledSlots(deviceId)) {
            if (row == null || row.length < 2 || row[0] == null || row[1] == null) {
                continue;
            }
            map.merge(String.valueOf(row[0]), ((Number) row[1]).intValue(), Integer::sum);
        }
        return map;
    }

    private Map<String, Integer> inventoryQtyBySku(String deviceId) {
        Map<String, Integer> map = new HashMap<>();
        for (DeviceSkuInventory row : inventoryRepository.findByIdDeviceId(deviceId)) {
            if (row.getQuantity() > 0) {
                map.put(row.getId().getSkuId(), row.getQuantity());
            }
        }
        return map;
    }

    /**
     * 分类展示值：字典值翻译成中文标签；空值与字典未收录的值（历史自由文本）原样返回。
     * 查找口径与 {@code SysDictService.labelOf} 一致（value trim+大写后匹配）。
     */
    private static String displayCategory(String raw, Map<String, String> categoryLabels) {
        if (raw == null || raw.isBlank()) {
            return raw;
        }
        return categoryLabels.getOrDefault(raw.trim().toUpperCase(), raw);
    }
}
