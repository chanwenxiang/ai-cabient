package com.aicabinet.trade.api;

import com.aicabinet.common.dto.ApiResponse;
import com.aicabinet.trade.auth.RequiresPermissions;
import com.aicabinet.trade.domain.JiangyiSkuJiangyiLink;
import com.aicabinet.trade.dto.JiangyiGatherDtos.JiangyiCategory;
import com.aicabinet.trade.service.SkuJiangyiLinkService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 将邑商品库挂接运营接口（CB-023 二期，范围 B）：SKU 维度的将邑商品挂接/新增/解挂。
 * <p>独立于 {@link AdminJiangyiController}（后者是设备面），权限沿用 SKU 既有权限位
 * （ops:sku:list 读 / ops:sku:edit 写）——挂接是商品主数据运维的一部分。</p>
 */
@RestController
@RequestMapping("/api/v2/ops/admin")
public class AdminJiangyiCatalogController {

    private final SkuJiangyiLinkService skuJiangyiLinkService;

    public AdminJiangyiCatalogController(SkuJiangyiLinkService skuJiangyiLinkService) {
        this.skuJiangyiLinkService = skuJiangyiLinkService;
    }

    @RequiresPermissions("ops:sku:list")
    @GetMapping("/skus/{skuId}/jiangyi-link")
    public ApiResponse<List<JiangyiSkuJiangyiLink>> list(@PathVariable("skuId") String skuId) {
        return ApiResponse.ok(skuJiangyiLinkService.listLinks(skuId));
    }

    /** 搜索将邑商品库（name/barCode 模糊）；传 skuId 时按我方 SKU 分级并隐藏明显不符。 */
    @RequiresPermissions("ops:sku:list")
    @GetMapping("/jiangyi/std-skus")
    public ApiResponse<SkuJiangyiLinkService.StdSkuSearchResult> search(
            @RequestParam(value = "name", required = false) String name,
            @RequestParam(value = "barCode", required = false) String barCode,
            @RequestParam(value = "skuId", required = false) String skuId,
            @RequestParam(value = "includeAll", defaultValue = "false") boolean includeAll) {
        return ApiResponse.ok(skuJiangyiLinkService.searchJiangyiProducts(name, barCode, skuId, includeAll));
    }

    /** 商品分类下拉（§4.1.4）。 */
    @RequiresPermissions("ops:sku:list")
    @GetMapping("/jiangyi/product-categories")
    public ApiResponse<List<JiangyiCategory>> categories() {
        return ApiResponse.ok(skuJiangyiLinkService.productCategories());
    }

    /**
     * 将邑待采集列表（§4.3.2）：已新增进将邑、还没采集学习过的商品。
     * 这批条目搜商品库是搜不到的，运营判断「将邑库里到底有没有」得看这里。
     */
    @RequiresPermissions("ops:sku:list")
    @GetMapping("/jiangyi/pending-gather")
    public ApiResponse<List<SkuJiangyiLinkService.PendingGatherView>> pendingGather(
            @RequestParam(value = "name", required = false) String name) {
        return ApiResponse.ok(skuJiangyiLinkService.listPendingGather(name));
    }

    /** 挂接既有将邑商品（唯一冲突 409；条码不一致仅记日志/前端提示，不阻断）。 */
    @RequiresPermissions("ops:sku:edit")
    @PutMapping("/skus/{skuId}/jiangyi-link")
    public ApiResponse<JiangyiSkuJiangyiLink> bind(@PathVariable("skuId") String skuId,
                                                   @Valid @RequestBody BindRequest body) {
        return ApiResponse.ok(skuJiangyiLinkService.bind(skuId, body.jiangyiProductId(),
                body.jiangyiName(), body.barCode()));
    }

    /** 我方商品新增到将邑商品库（§4.1.5；成功才落挂接，无半挂态）。 */
    @RequiresPermissions("ops:sku:edit")
    @PostMapping("/skus/{skuId}/jiangyi-link/create")
    public ApiResponse<JiangyiSkuJiangyiLink> create(@PathVariable("skuId") String skuId,
                                                     @Valid @RequestBody CreateRequest body) {
        return ApiResponse.ok(skuJiangyiLinkService.createInJiangyi(skuId, new SkuJiangyiLinkService.ProductCreateSpec(
                body.name(), body.specs(), body.catId(), body.brandName(), body.purchasePrice(),
                body.salePrice(), body.mainImg(), body.color(), body.category(), body.barCode())));
    }

    /** 学习完成后按条码回填 text_name（classes 预生成的商品归属依据）。 */
    @RequiresPermissions("ops:sku:edit")
    @PostMapping("/skus/{skuId}/jiangyi-link/pull-text-name")
    public ApiResponse<Integer> pullTextName(@PathVariable("skuId") String skuId) {
        return ApiResponse.ok(skuJiangyiLinkService.pullTextName(skuId));
    }

    /** 我方新增到将邑的商品从将邑删除（§4.1.6，不可逆；BOUND 一律 409）。 */
    @RequiresPermissions("ops:sku:edit")
    @DeleteMapping("/skus/{skuId}/jiangyi-link/{linkId}/jiangyi-product")
    public ApiResponse<Void> deleteInJiangyi(@PathVariable("skuId") String skuId,
                                             @PathVariable("linkId") long linkId) {
        skuJiangyiLinkService.deleteFromJiangyi(skuId, linkId);
        return ApiResponse.ok(null);
    }

    /** 解挂（RETIRED 留痕）。 */
    @RequiresPermissions("ops:sku:edit")
    @DeleteMapping("/skus/{skuId}/jiangyi-link/{linkId}")
    public ApiResponse<Void> unbind(@PathVariable("skuId") String skuId,
                                    @PathVariable("linkId") long linkId) {
        skuJiangyiLinkService.unbind(skuId, linkId);
        return ApiResponse.ok(null);
    }

    // ---------- 请求体 ----------

    record BindRequest(@NotNull(message = "将邑商品 id 不能为空") Long jiangyiProductId,
                       String jiangyiName,
                       String barCode) {}

    record CreateRequest(@NotBlank(message = "商品名称不能为空") String name,
                         @NotBlank(message = "商品规格不能为空") String specs,
                         @NotNull(message = "分类 id 不能为空") Long catId,
                         @NotBlank(message = "品牌名称不能为空") String brandName,
                         @NotBlank(message = "进货价不能为空") String purchasePrice,
                         @NotBlank(message = "零售价不能为空") String salePrice,
                         // 将邑侧必填，但允许请求留空：服务层回退 SKU 主图后再校验（空串不再是恒 400）
                         String mainImg,
                         Integer color,
                         @NotBlank(message = "包装类型不能为空") String category,
                         String barCode) {}
}
