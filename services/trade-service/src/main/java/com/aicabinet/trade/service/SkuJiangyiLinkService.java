package com.aicabinet.trade.service;

import com.aicabinet.trade.client.JiangyiGatherClient;
import com.aicabinet.trade.domain.JiangyiSkuJiangyiLink;
import com.aicabinet.trade.domain.SkuCatalog;
import com.aicabinet.trade.dto.JiangyiGatherDtos.JiangyiCategory;
import com.aicabinet.trade.dto.JiangyiGatherDtos.JiangyiStdSku;
import com.aicabinet.trade.dto.JiangyiGatherDtos.TrainedProduct;
import com.aicabinet.trade.mapper.JiangyiSkuJiangyiLinkMapper;
import com.aicabinet.trade.mapper.SkuCatalogMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 将邑商品库挂接（CB-023 二期，范围 B）：我方 SKU ↔ 将邑商品（stdSku）的商品维度关联。
 *
 * <p>设计铁律（CB-023 台账结论 2）：不动 sku_catalog 主数据表；将邑 barCode 可空且
 * barCodeSource=manual 不可靠——仅作 bind 时一致性校验，不作唯一键；jiangyi_product_id
 * 唯一（一个将邑商品至多挂一个我方 SKU，防识别歧义）。</p>
 */
@Service
public class SkuJiangyiLinkService {

    private static final Logger log = LoggerFactory.getLogger(SkuJiangyiLinkService.class);

    /** 规格归一化：330ml / 330毫升 / 1.5L / 1500ml 统一成毫升数。 */
    private static final Pattern VOLUME_PATTERN =
            Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(ml|毫升|l|升)", Pattern.CASE_INSENSITIVE);
    /** 同一商品线内会改变实物身份的版本词：仅出现在一方即判明显不符。 */
    private static final List<String> VARIANT_MARKERS =
            List.of("零卡", "无糖", "纤维", "纪念", "摩登");
    private static final List<String> JUNK_MARKERS = List.of("test", "测试");
    /** 未挂接优先 → 匹配度 → 扫码条码优先于手填 → 编号。 */
    private static final Comparator<Ranked> RESULT_ORDER = Comparator
            .comparing(Ranked::linked)
            .thenComparingInt(r -> r.match().rank())
            .thenComparingInt(r -> "manual".equals(r.sku().barCodeSource()) ? 1 : 0)
            .thenComparingLong(r -> r.sku().id());

    private final JiangyiSkuJiangyiLinkMapper linkMapper;
    private final SkuCatalogMapper skuCatalogMapper;
    private final JiangyiGatherClient jiangyiGatherClient;
    private final String publicBaseUrl;

    public SkuJiangyiLinkService(JiangyiSkuJiangyiLinkMapper linkMapper,
                                 SkuCatalogMapper skuCatalogMapper,
                                 JiangyiGatherClient jiangyiGatherClient,
                                 @Value("${JIANGYI_PUBLIC_BASE_URL:}") String publicBaseUrl) {
        this.linkMapper = linkMapper;
        this.skuCatalogMapper = skuCatalogMapper;
        this.jiangyiGatherClient = jiangyiGatherClient;
        this.publicBaseUrl = publicBaseUrl;
    }

    public List<JiangyiSkuJiangyiLink> listLinks(String skuId) {
        requireSku(skuId);
        return linkMapper.listBySku(skuId);
    }

    /**
     * 上报报文里的 {@code thirdPid}（将邑标准商品 id）→ 我方 skuId（2026-10-21）。
     *
     * <p>识别归一的首选通路：{@code uk_sku_jiangyi_product} 保证一个将邑商品至多挂一个 SKU，
     * 所以挂接一次即全 fleet 生效，不必逐设备配 classId 映射。RETIRED（解挂留痕）不认。</p>
     *
     * <p>刻意<b>不</b>在这里再校验 SKU 上下架：设备级门槛已由
     * {@code jiangyi_class_mapping} 的 ACTIVE 行承担（classId 兜底路径），落架 SKU 该由结算侧
     * 现有的取价/售卖校验拒绝并转人工，两条路径不该各长一套判据。</p>
     */
    public Optional<String> resolveSkuIdByProductId(String jiangyiProductId) {
        if (jiangyiProductId == null || jiangyiProductId.isBlank()) {
            return Optional.empty();
        }
        return linkMapper.byProduct(jiangyiProductId.trim())
                .filter(link -> !"RETIRED".equals(link.getSyncStatus()))
                .map(JiangyiSkuJiangyiLink::getSkuId)
                .filter(skuId -> skuId != null && !skuId.isBlank());
    }

    /**
     * 搜索将邑商品库（透传 §4.1.2）+ 匹配分级。传 skuId 时以我方 SKU 为基准：
     * 条码一致 &gt; 同规格 &gt; 相关；规格/版本词/test 明显不符默认隐藏（includeAll=true 返回并标 SUSPECT）。
     * 排序：未挂接优先 → 匹配度 → 扫码条码优先于手填 → 编号。
     */
    public StdSkuSearchResult searchJiangyiProducts(String name, String barCode,
                                                    String skuId, boolean includeAll) {
        List<JiangyiStdSku> skus = jiangyiGatherClient.stdSkuList(name, barCode);
        SkuCatalog sku = skuId == null || skuId.isBlank() ? null : requireSku(skuId);
        List<String> bound = linkMapper.listBoundProductIds(
                skus.stream().map(s -> String.valueOf(s.id())).toList());
        Set<String> boundSet = new HashSet<>(bound);
        List<Ranked> ranked = skus.stream()
                .map(s -> new Ranked(s, evaluate(s, sku), boundSet.contains(String.valueOf(s.id()))))
                .sorted(RESULT_ORDER)
                .toList();
        List<StdSkuView> items = ranked.stream()
                .filter(r -> includeAll || r.match().mismatchReasons().isEmpty())
                .map(Ranked::toView)
                .toList();
        return new StdSkuSearchResult(items, ranked.size(), ranked.size() - items.size());
    }

    /**
     * 以我方 SKU 评估将邑商品。条码不一致本身不判不符（69 码按包装版本不同，2026-10-21 拍板），
     * 只在条目上如实展示；只有规格/版本词/test 这类实物身份差异才隐藏。
     */
    private Match evaluate(JiangyiStdSku vendor, SkuCatalog ours) {
        if (ours == null) {
            return new Match("UNKNOWN", 3, List.of());
        }
        String ourBarcode = trimToNull(ours.getBarcode());
        if (ourBarcode != null && ourBarcode.equals(trimToNull(vendor.barCode()))) {
            return new Match("EXACT_BARCODE", 0, List.of());
        }
        List<String> reasons = new ArrayList<>();
        Integer ourMl = volumeMl(ours.getSpec(), ours.getSkuName());
        Integer vendorMl = volumeMl(vendor.specs(), vendor.name());
        if (ourMl != null && vendorMl != null && !ourMl.equals(vendorMl)) {
            reasons.add("规格 " + mlText(vendorMl) + " ≠ 我方 " + mlText(ourMl));
        }
        List<String> variantDiff = variantDiff(
                nullToEmpty(ours.getSkuName()) + " " + nullToEmpty(ours.getSpec()),
                nullToEmpty(vendor.name()) + " " + nullToEmpty(vendor.specs()));
        if (!variantDiff.isEmpty()) {
            reasons.add("版本词不一致：" + String.join("、", variantDiff));
        }
        if (containsAny(nullToEmpty(vendor.name()) + " " + nullToEmpty(vendor.specs()), JUNK_MARKERS)) {
            reasons.add("将邑名称含 test/测试");
        }
        if (!reasons.isEmpty()) {
            return new Match("SUSPECT", 3, List.copyOf(reasons));
        }
        if (ourMl != null && vendorMl != null) {
            return new Match("SAME_SPEC", 1, List.of());
        }
        return new Match("RELATED", 2, List.of());
    }

    private static Integer volumeMl(String... texts) {
        for (String text : texts) {
            if (text == null) {
                continue;
            }
            Matcher m = VOLUME_PATTERN.matcher(text);
            if (!m.find()) {
                continue;
            }
            double value = Double.parseDouble(m.group(1));
            String unit = m.group(2).toLowerCase(Locale.ROOT);
            boolean liters = "l".equals(unit) || "升".equals(unit);
            return (int) Math.round(liters ? value * 1000 : value);
        }
        return null;
    }

    private static String mlText(int ml) {
        return ml % 1000 == 0 ? (ml / 1000) + "L" : ml + "ml";
    }

    private static List<String> variantDiff(String ours, String vendor) {
        return VARIANT_MARKERS.stream()
                .filter(m -> ours.contains(m) != vendor.contains(m))
                .toList();
    }

    private static boolean containsAny(String text, List<String> markers) {
        String lower = text.toLowerCase(Locale.ROOT);
        return markers.stream().anyMatch(lower::contains);
    }

    private static String trimToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    public List<JiangyiCategory> productCategories() {
        return jiangyiGatherClient.productCategories();
    }

    /**
     * 挂接既有将邑商品。jiangyi_product_id 唯一（非 RETIRED 行冲突 409）。
     * 双方 barCode 不一致只留痕不阻断——69 码是罐身固有属性，同款不同包装版本条码本就不同
     * （2026-10-21 拍板）；识别扣款走 classId 映射、不读本关联，挂错仍可在识别映射页纠正。
     */
    public JiangyiSkuJiangyiLink bind(String skuId, long jiangyiProductId, String jiangyiName, String barCode) {
        requireSku(skuId);
        linkMapper.byProduct(String.valueOf(jiangyiProductId)).ifPresent(existing -> {
            if (!"RETIRED".equals(existing.getSyncStatus())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "该将邑商品已挂接到其他 SKU（jiangyiProductId=" + jiangyiProductId + "）");
            }
        });
        SkuCatalog sku = requireSku(skuId);
        if (barCode != null && !barCode.isBlank()
                && sku.getBarcode() != null && !sku.getBarcode().isBlank()
                && !sku.getBarcode().equals(barCode)) {
            log.info("jiangyi sku link barCode differs — bind proceeds by operator choice skuId={} skuBarCode={} jiangyiBarCode={}",
                    skuId, sku.getBarcode(), barCode);
        }
        Instant now = Instant.now();
        JiangyiSkuJiangyiLink link = linkMapper.byProduct(String.valueOf(jiangyiProductId))
                .orElseGet(JiangyiSkuJiangyiLink::new);
        link.setSkuId(skuId);
        link.setJiangyiProductId(String.valueOf(jiangyiProductId));
        link.setJiangyiName(jiangyiName);
        link.setBarCode(barCode);
        link.setSyncStatus("BOUND");
        link.setSyncedAt(now);
        link.setUpdatedAt(now);
        if (link.getId() == null) {
            link.setCreatedAt(now);
            linkMapper.insert(link);
        } else {
            linkMapper.updateById(link);
        }
        log.info("jiangyi sku link bound skuId={} jiangyiProductId={}", skuId, jiangyiProductId);
        return link;
    }

    /**
     * 我方商品新增到将邑商品库（§4.1.5）→ 成功才落 link(CREATED)，无半挂状态。
     * color 枚举 0-10 / category ∈ bottle|box|bag|bowl|egg 由服务层缺省兜底（color=0 其他）。
     *
     * <p>mainImg 将邑侧必填（采集文档 §4.1.5 商品主图 M）：请求未带时回退我方 SKU
     * 的商品主图（imageUrl）；两边都没有则 409 提示先登记图片——此前弹窗恒发空串，
     * 把「主图缺失」拖到将邑 500 才暴露，运营无从下手。</p>
     */
    public JiangyiSkuJiangyiLink createInJiangyi(String skuId, ProductCreateSpec spec) {
        SkuCatalog sku = requireSku(skuId);
        String mainImg = absoluteImageUrl(spec.mainImg() != null && !spec.mainImg().isBlank()
                ? spec.mainImg()
                : sku.getImageUrl());
        if (mainImg.isBlank()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "将邑要求商品主图：请先在商品管理登记该商品的图片，再新增到将邑商品库");
        }
        Map<String, Object> body = new HashMap<>();
        body.put("name", spec.name());
        body.put("specs", spec.specs());
        body.put("canSale", "yes");
        body.put("catId", spec.catId());
        body.put("brandName", spec.brandName());
        body.put("purchasePrice", spec.purchasePrice());
        body.put("salePrice", spec.salePrice());
        body.put("mainImg", mainImg);
        body.put("color", spec.color() == null ? 0 : spec.color());
        body.put("applyMachineId", 21);
        body.put("category", spec.category());
        if (spec.barCode() != null && !spec.barCode().isBlank()) {
            body.put("barCode", spec.barCode());
        }
        long productId;
        try {
            productId = jiangyiGatherClient.productCreate(body);
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "将邑新增商品失败：" + e.getMessage());
        }
        Instant now = Instant.now();
        JiangyiSkuJiangyiLink link = new JiangyiSkuJiangyiLink();
        link.setSkuId(skuId);
        link.setJiangyiProductId(String.valueOf(productId));
        link.setJiangyiName(spec.name());
        link.setBarCode(spec.barCode());
        link.setSyncStatus("CREATED");
        link.setSyncedAt(now);
        link.setCreatedAt(now);
        link.setUpdatedAt(now);
        linkMapper.insert(link);
        log.info("jiangyi sku link created-in-jiangyi skuId={} productId={}", skuId, productId);
        return link;
    }

    /** 学习完成后按 barCode 从已学习商品回填 text_name（classes.txt 直接键）。 */
    public int pullTextName(String skuId) {
        SkuCatalog sku = requireSku(skuId);
        if (sku.getBarcode() == null || sku.getBarcode().isBlank()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "我方 SKU 未登记条形码，无法按条码回填");
        }
        List<TrainedProduct> trained = jiangyiGatherClient.trainedProducts();
        int updated = 0;
        for (TrainedProduct p : trained) {
            if (!sku.getBarcode().equals(p.barCode())) {
                continue;
            }
            for (JiangyiSkuJiangyiLink link : linkMapper.listBySku(skuId)) {
                link.setJiangyiTextName(p.textName());
                link.setSyncedAt(Instant.now());
                link.setUpdatedAt(Instant.now());
                linkMapper.updateById(link);
                updated++;
            }
        }
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "已学习商品中未找到条形码 " + sku.getBarcode());
        }
        return updated;
    }

    /** 解挂（RETIRED 留痕，不物理删）。 */
    public void unbind(long linkId) {
        JiangyiSkuJiangyiLink link = linkMapper.selectById(linkId);
        if (link == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "挂接记录不存在：" + linkId);
        }
        link.setSyncStatus("RETIRED");
        link.setUpdatedAt(Instant.now());
        linkMapper.updateById(link);
        log.info("jiangyi sku link unbound linkId={} skuId={} jiangyiProductId={}",
                linkId, link.getSkuId(), link.getJiangyiProductId());
    }

    /**
     * 将邑 mainImg 是给其服务端/端侧直接取图的 URL；我方 SKU 主图存的是站内相对路径
     * （/api/v2/media/sku-images/…），发送前按 JIANGYI_PUBLIC_BASE_URL 补成公网绝对地址。
     * 已是 http(s) 的完整 URL 原样保留；基址未配置时保持原值（由将邑侧决定是否接受）。
     */
    private String absoluteImageUrl(String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        String trimmed = url.trim();
        if (trimmed.startsWith("/") && publicBaseUrl != null && !publicBaseUrl.isBlank()) {
            return publicBaseUrl.replaceAll("/+$", "") + trimmed;
        }
        return trimmed;
    }

    private SkuCatalog requireSku(String skuId) {
        SkuCatalog sku = skuCatalogMapper.selectById(skuId);
        if (sku == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "SKU 不存在：" + skuId);
        }
        return sku;
    }

    /** 单条匹配结果：SUSPECT 才有 mismatchReasons，其余为空。 */
    private record Match(String level, int rank, List<String> mismatchReasons) {}

    private record Ranked(JiangyiStdSku sku, Match match, boolean linked) {
        StdSkuView toView() {
            return new StdSkuView(sku.id(), sku.name(), sku.barCode(), sku.specs(), sku.brandName(),
                    sku.category(), sku.mainImg(), sku.stdSkuCode(), linked,
                    sku.barCodeSource(), match.level(), match.mismatchReasons());
        }
    }

    /**
     * 搜索视图。barCodeSource 原值 app/manual/null；matchLevel ∈
     * EXACT_BARCODE|SAME_SPEC|RELATED|SUSPECT|UNKNOWN（未传 skuId 时不比对）。
     */
    public record StdSkuView(long id, String name, String barCode, String specs, String brandName,
                             String category, String mainImg, String stdSkuCode, boolean linked,
                             String barCodeSource, String matchLevel, List<String> mismatchReasons) {}

    /** items=当前返回项；total=将邑原始条数；hidden=被隐藏的明显不符条数（includeAll 时 0）。 */
    public record StdSkuSearchResult(List<StdSkuView> items, int total, int hidden) {}

    /** 新增到将邑商品库的入参（§4.1.5 必填集合）。 */
    public record ProductCreateSpec(String name, String specs, long catId, String brandName,
                                    String purchasePrice, String salePrice, String mainImg,
                                    Integer color, String category, String barCode) {}
}
