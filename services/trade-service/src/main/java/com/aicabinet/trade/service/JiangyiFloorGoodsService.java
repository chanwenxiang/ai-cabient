package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.DeviceSkuInventory;
import com.aicabinet.trade.domain.JiangyiClassMapping;
import com.aicabinet.trade.domain.JiangyiSkuJiangyiLink;
import com.aicabinet.trade.domain.SkuCatalog;
import com.aicabinet.trade.mapper.DeviceSkuInventoryMapper;
import com.aicabinet.trade.mapper.JiangyiSkuJiangyiLinkMapper;
import com.aicabinet.trade.mapper.SkuCatalogMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 设备补货/在售商品表（gateway {@code floor/getFloorByDevice} 的数据源，2026-10-21）。
 *
 * <p><b>为什么存在</b>：APK 反编译实证，固件里往 {@code ReplenishGoodsInfos} 写入的
 * 唯一调用点是本端点响应解析（{@code GetReplenishGoodsInfo$1}），识别上报的
 * {@code thirdPid/price/productName} 全部由这张表按 classId 回填。我方此前 404 该路径
 * ⇒ 设备上报的这三个字段恒 0 ⇒ 只能靠逐设备 classId 映射认 SKU，且设备侧按
 * {@code stock>0}/{@code thirdPid>0} 的识别歧义消解分支永不生效。</p>
 *
 * <p><b>口径</b>：行集 = 本设备 ACTIVE 且已挂 SKU 的识别映射；{@code salePrice} 走
 * {@link MerchantSkuPricingService#resolveUnitPriceCents}（与结算同一个取价函数，
 * A 档下「设备看到的价格」＝「我方扣的价格」）；{@code stock} = 本机货道库存
 * （缺行按 0，固件 {@code UpDownCampare} 只在 {@code >0} 时偏向该类，0 是中性值）；
 * {@code thirdPid} = 该 SKU 的非 RETIRED 挂接行里的数字型将邑商品 id，
 * 无挂接/非数字回 0（设备只是少一个自证 key，不影响 classId 兜底解析）。</p>
 *
 * <p>{@code category}（形态枚举 bag/bottle/bowl/box/egg）我方无判定依据，刻意不下发——
 * 固件各消费点先判 null，留空只关闭形态相关分支。等真机跑通、厂商给出映射口径再补。</p>
 */
@Service
public class JiangyiFloorGoodsService {

    private static final Logger log = LoggerFactory.getLogger(JiangyiFloorGoodsService.class);

    /** 固件 ReplenishGoodsInfo.thirdPid 是 Java int；超界只能回 0（挂接 id 正常是 stdSku 小整数）。 */
    private static final long THIRD_PID_MAX = Integer.MAX_VALUE;

    private final JiangyiClassMappingService jiangyiClassMappingService;
    private final SkuCatalogMapper skuCatalogMapper;
    private final JiangyiSkuJiangyiLinkMapper jiangyiSkuJiangyiLinkMapper;
    private final DeviceSkuInventoryMapper deviceSkuInventoryMapper;
    private final MerchantSkuPricingService merchantSkuPricingService;
    private final JiangyiMappingReviewService mappingReviewService;

    public JiangyiFloorGoodsService(JiangyiClassMappingService jiangyiClassMappingService,
                                    SkuCatalogMapper skuCatalogMapper,
                                    JiangyiSkuJiangyiLinkMapper jiangyiSkuJiangyiLinkMapper,
                                    DeviceSkuInventoryMapper deviceSkuInventoryMapper,
                                    MerchantSkuPricingService merchantSkuPricingService,
                                    JiangyiMappingReviewService mappingReviewService) {
        this.jiangyiClassMappingService = jiangyiClassMappingService;
        this.skuCatalogMapper = skuCatalogMapper;
        this.jiangyiSkuJiangyiLinkMapper = jiangyiSkuJiangyiLinkMapper;
        this.deviceSkuInventoryMapper = deviceSkuInventoryMapper;
        this.merchantSkuPricingService = merchantSkuPricingService;
        this.mappingReviewService = mappingReviewService;
    }

    /**
     * @return 该设备可上报解析的在售行（无 ACTIVE 已挂映射 → 空表，调用方回 {@code products:[]}）
     */
    @Transactional(readOnly = true)
    public List<FloorGoods> listFloorGoods(String deviceId) {
        if (deviceId == null || deviceId.isBlank()) {
            return List.of();
        }
        // 复核闸口（V400）：映射未必跟得上柜机正在跑的模型（重训会整体平移 classId），
        // 未按当前 classes 版本人工复核就不下发——补货表是设备回填 thirdPid/price 的依据，
        // 错一行就是设备拿着错价去识别。空表让识别退回 fail-closed，比错扣钱安全。
        if (!mappingReviewService.isVerified(deviceId)) {
            log.warn("jiangyi floor goods withheld: mapping not verified for current classes deviceId={}",
                    deviceId);
            return List.of();
        }
        List<JiangyiClassMapping> mappings = jiangyiClassMappingService.listByDevice(deviceId).stream()
                .filter(m -> "ACTIVE".equals(m.getStatus()))
                .filter(m -> m.getClassId() != null && m.getClassId() >= 0)
                .filter(m -> m.getSkuId() != null && !m.getSkuId().isBlank())
                .toList();
        if (mappings.isEmpty()) {
            return List.of();
        }
        List<String> skuIds = mappings.stream().map(JiangyiClassMapping::getSkuId).distinct().toList();
        Map<String, SkuCatalog> skus = new LinkedHashMap<>();
        skuCatalogMapper.selectList(Wrappers.<SkuCatalog>lambdaQuery()
                        .in(SkuCatalog::getSkuId, skuIds))
                .forEach(sku -> skus.put(sku.getSkuId(), sku));
        Map<String, Integer> stockBySku = new LinkedHashMap<>();
        for (DeviceSkuInventory inventory : deviceSkuInventoryMapper.findByIdDeviceId(deviceId)) {
            stockBySku.merge(inventory.getSkuId(), inventory.getQuantity(), Integer::sum);
        }
        PricingPromoPolicy promoPolicy = merchantSkuPricingService.loadPromoPolicy();

        List<FloorGoods> rows = new java.util.ArrayList<>(mappings.size());
        for (JiangyiClassMapping mapping : mappings) {
            String skuId = mapping.getSkuId();
            SkuCatalog sku = skus.get(skuId);
            if (sku == null) {
                // 映射指向不存在的 SKU：下发出去只会让设备识别到一个我们认不出的商品
                log.warn("jiangyi floor goods skip mapping without sku deviceId={} classId={} skuId={}",
                        deviceId, mapping.getClassId(), skuId);
                continue;
            }
            if (!"ACTIVE".equals(sku.getStatus())) {
                log.info("jiangyi floor goods skip non-ACTIVE sku deviceId={} classId={} skuId={} status={}",
                        deviceId, mapping.getClassId(), skuId, sku.getStatus());
                continue;
            }
            rows.add(new FloorGoods(
                    mapping.getClassId(),
                    resolveThirdPid(skuId),
                    displayName(sku, skuId),
                    merchantSkuPricingService.resolveUnitPriceCents(deviceId, sku, promoPolicy),
                    stockBySku.getOrDefault(skuId, 0)));
        }
        log.info("jiangyi floor goods deviceId={} rows={} of {} active mappings",
                deviceId, rows.size(), mappings.size());
        return rows;
    }

    /** 挂接行里的数字型将邑商品 id；无挂接/非数字/超 int 范围 → 0。 */
    private int resolveThirdPid(String skuId) {
        Optional<JiangyiSkuJiangyiLink> link = jiangyiSkuJiangyiLinkMapper.listBySku(skuId).stream()
                .findFirst();
        if (link.isEmpty()) {
            return 0;
        }
        String productId = link.get().getJiangyiProductId();
        if (productId == null || productId.isBlank() || !productId.chars().allMatch(Character::isDigit)) {
            log.info("jiangyi floor goods non-numeric product id skuId={} productId={} → thirdPid 0",
                    skuId, productId);
            return 0;
        }
        try {
            long value = Long.parseLong(productId);
            if (value <= 0 || value > THIRD_PID_MAX) {
                log.warn("jiangyi floor goods product id out of firmware int range skuId={} productId={} → thirdPid 0",
                        skuId, productId);
                return 0;
            }
            return (int) value;
        } catch (NumberFormatException e) {
            log.warn("jiangyi floor goods product id unparsable skuId={} productId={} → thirdPid 0",
                    skuId, productId);
            return 0;
        }
    }

    /** 下发给设备的商品名：取我方 SKU 名——与下发的售价同源，缺名回落 skuId 保证非空。 */
    private static String displayName(SkuCatalog sku, String skuId) {
        return sku.getSkuName() == null || sku.getSkuName().isBlank() ? skuId : sku.getSkuName();
    }

    /** 补货表行（字段名对齐固件 ReplenishGoodsInfo）。 */
    public record FloorGoods(Integer classId, Integer thirdPid, String productName,
                             Integer salePrice, Integer stock) {}
}
