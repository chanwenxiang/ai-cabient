package com.aicabinet.trade.api;

import com.aicabinet.common.dto.ApiResponse;
import com.aicabinet.trade.auth.AuthInterceptor;
import com.aicabinet.trade.auth.RequiresPermissions;
import com.aicabinet.trade.domain.JiangyiSkuJiangyiLink;
import com.aicabinet.trade.dto.JiangyiGatherDtos.JiangyiCategory;
import com.aicabinet.trade.dto.JiangyiRestockDtos.PhoneCapturePolicy;
import com.aicabinet.trade.dto.JiangyiRestockDtos.ProductCreateCommand;
import com.aicabinet.trade.service.JiangyiPhoneCaptureService;
import com.aicabinet.trade.service.JiangyiProductStateService;
import com.aicabinet.trade.service.SkuJiangyiLinkService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
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
    private final JiangyiProductStateService productStateService;
    private final JiangyiPhoneCaptureService phoneCaptureService;

    public AdminJiangyiCatalogController(SkuJiangyiLinkService skuJiangyiLinkService,
                                         JiangyiProductStateService productStateService,
                                         JiangyiPhoneCaptureService phoneCaptureService) {
        this.skuJiangyiLinkService = skuJiangyiLinkService;
        this.productStateService = productStateService;
        this.phoneCaptureService = phoneCaptureService;
    }

    @RequiresPermissions("ops:sku:list")
    @GetMapping("/skus/{skuId}/jiangyi-link")
    public ApiResponse<List<JiangyiSkuJiangyiLink>> list(@PathVariable("skuId") String skuId) {
        return ApiResponse.ok(skuJiangyiLinkService.listLinks(skuId));
    }

    /** 批量生效挂接（非 RETIRED）：商品管理列表「将邑挂接」状态列一次拉全量 join，避免逐行 N 次请求。 */
    @RequiresPermissions("ops:sku:list")
    @GetMapping("/jiangyi/links")
    public ApiResponse<List<JiangyiSkuJiangyiLink>> listActiveLinks() {
        return ApiResponse.ok(skuJiangyiLinkService.listActiveLinks());
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
    public ApiResponse<JiangyiSkuJiangyiLink> bind(HttpServletRequest request,
                                                   @PathVariable("skuId") String skuId,
                                                   @Valid @RequestBody BindRequest body) {
        return ApiResponse.ok(skuJiangyiLinkService.bind(operatorId(request), skuId,
                body.jiangyiProductId(), body.jiangyiName(), body.barCode()));
    }

    /** 我方商品新增到将邑商品库（§4.1.5；成功才落挂接，无半挂态）。 */
    @RequiresPermissions("ops:sku:edit")
    @PostMapping("/skus/{skuId}/jiangyi-link/create")
    public ApiResponse<JiangyiSkuJiangyiLink> create(HttpServletRequest request,
                                                     @PathVariable("skuId") String skuId,
                                                     @Valid @RequestBody CreateRequest body) {
        return ApiResponse.ok(skuJiangyiLinkService.createInJiangyi(operatorId(request), skuId,
                new SkuJiangyiLinkService.ProductCreateSpec(
                        body.name(), body.specs(), body.catId(), body.brandName(), body.purchasePrice(),
                        body.salePrice(), body.mainImg(), body.color(), body.category(), body.barCode())));
    }

    /** 学习完成后按条码回填 text_name（classes 预生成的商品归属依据）。 */
    @RequiresPermissions("ops:sku:edit")
    @PostMapping("/skus/{skuId}/jiangyi-link/pull-text-name")
    public ApiResponse<Integer> pullTextName(HttpServletRequest request,
                                             @PathVariable("skuId") String skuId) {
        return ApiResponse.ok(skuJiangyiLinkService.pullTextName(operatorId(request), skuId));
    }

    /** 我方新增到将邑的商品从将邑删除（§4.1.6，不可逆；BOUND 一律 409）。 */
    @RequiresPermissions("ops:sku:edit")
    @DeleteMapping("/skus/{skuId}/jiangyi-link/{linkId}/jiangyi-product")
    public ApiResponse<Void> deleteInJiangyi(HttpServletRequest request,
                                             @PathVariable("skuId") String skuId,
                                             @PathVariable("linkId") long linkId) {
        skuJiangyiLinkService.deleteFromJiangyi(operatorId(request), skuId, linkId);
        return ApiResponse.ok(null);
    }

    /**
     * 改将邑商品主图（V1.13 §4.1.7，真实路径大写 I，见 H-24）。
     * 只对我方新增（CREATED）的商品开放；主图取我方 SKU 已登记的图片。
     */
    @RequiresPermissions("ops:sku:edit")
    @PostMapping("/skus/{skuId}/jiangyi-link/{linkId}/main-image")
    public ApiResponse<JiangyiSkuJiangyiLink> updateMainImage(HttpServletRequest request,
                                                              @PathVariable("skuId") String skuId,
                                                              @PathVariable("linkId") long linkId) {
        return ApiResponse.ok(skuJiangyiLinkService.updateMainImage(operatorId(request), skuId, linkId));
    }

    /** 解挂（RETIRED 留痕）。 */
    @RequiresPermissions("ops:sku:edit")
    @DeleteMapping("/skus/{skuId}/jiangyi-link/{linkId}")
    public ApiResponse<Void> unbind(HttpServletRequest request,
                                    @PathVariable("skuId") String skuId,
                                    @PathVariable("linkId") long linkId) {
        skuJiangyiLinkService.unbind(operatorId(request), skuId, linkId);
        return ApiResponse.ok(null);
    }

    // ---------- 第七章：商品状态 / 建档 / 手机采集（V16 2026-10-24） ----------

    /** §7.7 手机采集拍摄要求（张数以 shotCount 为准，禁止前端硬编码）。 */
    @RequiresPermissions("ops:sku:list")
    @GetMapping("/jiangyi/capture/policy")
    public ApiResponse<PhoneCapturePolicy> capturePolicy() {
        return ApiResponse.ok(phoneCaptureService.policy());
    }

    /**
     * §7.5 按条码查询商品状态。
     *
     * <p>🔴 文档写明「首次查询已建档但未完成采集的商品时，会为其建立采集任务」——
     * 该接口有厂商侧写副作用，只供运营显式点击；轮询请用 {@code /jiangyi/products/state/refresh}（§7.10）。</p>
     */
    @RequiresPermissions("ops:sku:list")
    @GetMapping("/jiangyi/products/{barCode}/state")
    public ApiResponse<JiangyiSkuJiangyiLink> productState(@PathVariable("barCode") String barCode) {
        return ApiResponse.ok(productStateService.check(barCode).orElse(null));
    }

    /** §7.10 批量刷新商品状态（无副作用；单次最多 100 个条码，超出由服务层分片）。 */
    @RequiresPermissions("ops:sku:edit")
    @PostMapping("/jiangyi/products/state/refresh")
    public ApiResponse<Integer> refreshProductStates(@Valid @RequestBody StateRefreshRequest body) {
        return ApiResponse.ok(productStateService.refresh(body.barCodes()));
    }

    /** §7.6 按条码新建将邑商品（同条码重复调用幂等）。 */
    @RequiresPermissions("ops:sku:edit")
    @PostMapping("/jiangyi/products/create")
    public ApiResponse<JiangyiSkuJiangyiLink> createProduct(@Valid @RequestBody ProductCreateRequest body) {
        return ApiResponse.ok(productStateService.create(new ProductCreateCommand(
                body.barCode(), body.salePriceCents(), body.purchasePriceCents(),
                body.name(), body.brandName(), body.specs(), body.category(),
                body.categoryId(), body.colorId(), body.mainImg())).orElse(null));
    }

    /**
     * §7.8 + §7.9 提交手机采集（multipart 字段名 {@code files}，按拍摄顺序排列）。
     *
     * <p>照片必须是现场实物拍摄（§7.3 禁止网图/宣传图）；本端只接受上传字节，
     * 由服务层逐张转投 §7.8 后再提交。</p>
     */
    @RequiresPermissions("ops:sku:edit")
    @PostMapping(path = "/jiangyi/products/{barCode}/phone-capture", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<JiangyiSkuJiangyiLink> submitPhoneCapture(
            @PathVariable("barCode") String barCode,
            @RequestPart("files") List<MultipartFile> files) {
        List<JiangyiPhoneCaptureService.Photo> photos = new ArrayList<>();
        try {
            for (MultipartFile file : files) {
                photos.add(new JiangyiPhoneCaptureService.Photo(
                        file.getBytes(), file.getOriginalFilename(), file.getContentType()));
            }
        } catch (IOException e) {
            throw new IllegalStateException("读取上传图片失败", e);
        }
        return ApiResponse.ok(phoneCaptureService.submit(barCode, photos).orElse(null));
    }

    private static Long operatorId(HttpServletRequest request) {
        return (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
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

    /** §7.10 批量刷新：1~100 个条码（服务层按 100 分片）。 */
    record StateRefreshRequest(@NotNull(message = "条码列表不能为空") List<String> barCodes) {}

    /** §7.6 新建商品：金额单位用分，由客户端在出口转元（§3.3）。 */
    record ProductCreateRequest(@NotBlank(message = "条码不能为空") String barCode,
                                @NotNull(message = "零售价不能为空") Long salePriceCents,
                                Long purchasePriceCents,
                                String name,
                                String brandName,
                                String specs,
                                String category,
                                Integer categoryId,
                                Integer colorId,
                                String mainImg) {}
}
