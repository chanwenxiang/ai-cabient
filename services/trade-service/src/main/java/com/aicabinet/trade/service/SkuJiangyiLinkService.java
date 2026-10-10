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
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    private final JiangyiSkuJiangyiLinkMapper linkMapper;
    private final SkuCatalogMapper skuCatalogMapper;
    private final JiangyiGatherClient jiangyiGatherClient;

    public SkuJiangyiLinkService(JiangyiSkuJiangyiLinkMapper linkMapper,
                                 SkuCatalogMapper skuCatalogMapper,
                                 JiangyiGatherClient jiangyiGatherClient) {
        this.linkMapper = linkMapper;
        this.skuCatalogMapper = skuCatalogMapper;
        this.jiangyiGatherClient = jiangyiGatherClient;
    }

    public List<JiangyiSkuJiangyiLink> listLinks(String skuId) {
        requireSku(skuId);
        return linkMapper.listBySku(skuId);
    }

    /** 搜索将邑商品库（透传 §4.1.2）+ 标注已挂接状态。 */
    public List<StdSkuView> searchJiangyiProducts(String name, String barCode) {
        List<JiangyiStdSku> skus = jiangyiGatherClient.stdSkuList(name, barCode);
        List<String> bound = linkMapper.listBoundProductIds(
                skus.stream().map(s -> String.valueOf(s.id())).toList());
        var boundSet = new java.util.HashSet<>(bound);
        return skus.stream()
                .map(s -> new StdSkuView(s.id(), s.name(), s.barCode(), s.specs(), s.brandName(),
                        s.category(), s.mainImg(), s.stdSkuCode(),
                        boundSet.contains(String.valueOf(s.id()))))
                .toList();
    }

    public List<JiangyiCategory> productCategories() {
        return jiangyiGatherClient.productCategories();
    }

    /**
     * 挂接既有将邑商品。jiangyi_product_id 唯一（非 RETIRED 行冲突 409）；
     * 双方 barCode 均非空且不等即拒（防挂错商品——识别错误会直接错误扣款）。
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
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "条形码不一致：我方 " + sku.getBarcode() + " ≠ 将邑 " + barCode + "，请人工核对");
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
     */
    public JiangyiSkuJiangyiLink createInJiangyi(String skuId, ProductCreateSpec spec) {
        requireSku(skuId);
        Map<String, Object> body = new HashMap<>();
        body.put("name", spec.name());
        body.put("specs", spec.specs());
        body.put("canSale", "yes");
        body.put("catId", spec.catId());
        body.put("brandName", spec.brandName());
        body.put("purchasePrice", spec.purchasePrice());
        body.put("salePrice", spec.salePrice());
        body.put("mainImg", spec.mainImg());
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

    private SkuCatalog requireSku(String skuId) {
        SkuCatalog sku = skuCatalogMapper.selectById(skuId);
        if (sku == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "SKU 不存在：" + skuId);
        }
        return sku;
    }

    /** 搜索视图（标注已挂接）。 */
    public record StdSkuView(long id, String name, String barCode, String specs, String brandName,
                             String category, String mainImg, String stdSkuCode, boolean linked) {}

    /** 新增到将邑商品库的入参（§4.1.5 必填集合）。 */
    public record ProductCreateSpec(String name, String specs, long catId, String brandName,
                                    String purchasePrice, String salePrice, String mainImg,
                                    Integer color, String category, String barCode) {}
}
