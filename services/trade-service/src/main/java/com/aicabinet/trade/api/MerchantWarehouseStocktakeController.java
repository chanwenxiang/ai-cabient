package com.aicabinet.trade.api;

import com.aicabinet.common.dto.ApiResponse;
import com.aicabinet.common.dto.CreateStocktakeRequest;
import com.aicabinet.common.dto.FileAttachmentDto;
import com.aicabinet.common.dto.PageResult;
import com.aicabinet.common.dto.StocktakeDto;
import com.aicabinet.common.dto.StocktakeLineDto;
import com.aicabinet.common.dto.UpdateStocktakeLineRequest;
import com.aicabinet.common.dto.WarehouseDto;
import com.aicabinet.trade.auth.AuthInterceptor;
import com.aicabinet.trade.auth.RequiresPermissions;
import com.aicabinet.trade.service.FileAttachmentService;
import com.aicabinet.trade.service.ProcurementService;
import com.aicabinet.trade.service.WarehouseStocktakeService;
import com.aicabinet.trade.support.MerchantPortalGuard;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
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
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

/**
 * 商户小程序「仓库盘点」（V349）：复用整仓盘点流程（{@link WarehouseStocktakeService}），
 * 服务层授权为「ops 权限 OR 分仓负责人」双通道——商户用户走负责人通道，
 * 范围天然限定本人负责的分仓（与采购入库「只进您负责的分仓」同口径）。
 * 拍照凭证走 {@link FileAttachmentService#uploadStocktakeEvidence}（refType=STOCKTAKE）。
 */
@RestController
@RequestMapping("/api/v2/merchant/warehouse/stocktakes")
public class MerchantWarehouseStocktakeController {

    private final WarehouseStocktakeService stocktakeService;
    private final ProcurementService procurementService;
    private final FileAttachmentService fileAttachmentService;
    private final MerchantPortalGuard portalGuard;

    public MerchantWarehouseStocktakeController(WarehouseStocktakeService stocktakeService,
                                                ProcurementService procurementService,
                                                FileAttachmentService fileAttachmentService,
                                                MerchantPortalGuard portalGuard) {
        this.stocktakeService = stocktakeService;
        this.procurementService = procurementService;
        this.fileAttachmentService = fileAttachmentService;
        this.portalGuard = portalGuard;
    }

    /** 本人的分仓盘点单列表（服务端锁定仓库=本人负责的分仓）。 */
    @RequiresPermissions("merchant:replenishment:view")
    @GetMapping
    public ApiResponse<PageResult<StocktakeDto>> list(
            HttpServletRequest request,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        Long userId = userId(request);
        portalGuard.requireAccess(userId);
        return ApiResponse.ok(stocktakeService.listPage(
                userId, blankToNull(status), myWarehouseId(userId), page, size));
    }

    /** 建单：仓库一律服务端解析为本人分仓（客户端传值仅做一致性校验）。 */
    @RequiresPermissions("merchant:replenishment:request")
    @PostMapping
    public ApiResponse<StocktakeDto> create(
            HttpServletRequest request,
            @RequestBody CreateStocktakeRequest body) {
        Long userId = userId(request);
        portalGuard.requireAccess(userId);
        String mine = myWarehouseId(userId);
        if (body != null && body.warehouseId() != null && !body.warehouseId().isBlank()
                && !mine.equals(body.warehouseId().trim())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "只能对本人负责的分仓发起盘点");
        }
        return ApiResponse.ok(stocktakeService.create(userId,
                new CreateStocktakeRequest(mine, body == null ? null : body.mode(),
                        body == null ? null : body.notes())));
    }

    @RequiresPermissions("merchant:replenishment:view")
    @GetMapping("/{stocktakeId}")
    public ApiResponse<StocktakeDto> detail(
            HttpServletRequest request, @PathVariable Long stocktakeId) {
        Long userId = userId(request);
        portalGuard.requireAccess(userId);
        return ApiResponse.ok(stocktakeService.get(userId, stocktakeId));
    }

    /** 录入实盘（diffReason 可选但填了会校验方向一致性——见 UpdateStocktakeLineRequest）。 */
    @RequiresPermissions("merchant:replenishment:request")
    @PutMapping("/{stocktakeId}/lines/{lineId}")
    public ApiResponse<StocktakeLineDto> updateLine(
            HttpServletRequest request,
            @PathVariable Long stocktakeId,
            @PathVariable Long lineId,
            @RequestBody UpdateStocktakeLineRequest body) {
        Long userId = userId(request);
        portalGuard.requireAccess(userId);
        stocktakeService.requireManagedWarehouseId(userId, stocktakeId);
        return ApiResponse.ok(stocktakeService.updateLine(userId, stocktakeId, lineId, body));
    }

    @RequiresPermissions("merchant:replenishment:request")
    @PostMapping("/{stocktakeId}/complete")
    public ApiResponse<StocktakeDto> complete(
            HttpServletRequest request, @PathVariable Long stocktakeId) {
        Long userId = userId(request);
        portalGuard.requireAccess(userId);
        stocktakeService.requireManagedWarehouseId(userId, stocktakeId);
        return ApiResponse.ok(stocktakeService.complete(userId, stocktakeId));
    }

    /** 完成并过账（同锁同事务，无「已完未调」中间态）。 */
    @RequiresPermissions("merchant:replenishment:request")
    @PostMapping("/{stocktakeId}/complete-and-adjust")
    public ApiResponse<StocktakeDto> completeAndAdjust(
            HttpServletRequest request, @PathVariable Long stocktakeId) {
        Long userId = userId(request);
        portalGuard.requireAccess(userId);
        stocktakeService.requireManagedWarehouseId(userId, stocktakeId);
        return ApiResponse.ok(stocktakeService.completeAndAdjust(userId, stocktakeId));
    }

    @RequiresPermissions("merchant:replenishment:request")
    @PostMapping("/{stocktakeId}/cancel")
    public ApiResponse<StocktakeDto> cancel(
            HttpServletRequest request, @PathVariable Long stocktakeId) {
        Long userId = userId(request);
        portalGuard.requireAccess(userId);
        stocktakeService.requireManagedWarehouseId(userId, stocktakeId);
        return ApiResponse.ok(stocktakeService.cancel(userId, stocktakeId));
    }

    // ── 现场照片（refType=STOCKTAKE，上限 5 张，jpg/png/webp/gif ≤5MB）──

    @RequiresPermissions("merchant:replenishment:request")
    @PostMapping(value = "/{stocktakeId}/evidence", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<FileAttachmentDto> uploadEvidence(
            HttpServletRequest request,
            @PathVariable Long stocktakeId,
            @RequestPart("file") MultipartFile file) {
        Long userId = userId(request);
        portalGuard.requireAccess(userId);
        stocktakeService.requireManagedWarehouseId(userId, stocktakeId);
        return ApiResponse.ok(fileAttachmentService.uploadStocktakeEvidence(userId, stocktakeId, file));
    }

    @RequiresPermissions("merchant:replenishment:view")
    @GetMapping("/{stocktakeId}/evidence")
    public ApiResponse<java.util.List<FileAttachmentDto>> listEvidence(
            HttpServletRequest request, @PathVariable Long stocktakeId) {
        Long userId = userId(request);
        portalGuard.requireAccess(userId);
        stocktakeService.requireManagedWarehouseId(userId, stocktakeId);
        return ApiResponse.ok(fileAttachmentService.listStocktakeEvidence(stocktakeId));
    }

    @RequiresPermissions("merchant:replenishment:view")
    @GetMapping("/{stocktakeId}/evidence/{fileId}")
    public void streamEvidence(
            HttpServletRequest request,
            @PathVariable Long stocktakeId,
            @PathVariable Long fileId,
            jakarta.servlet.http.HttpServletResponse response) throws java.io.IOException {
        Long userId = userId(request);
        portalGuard.requireAccess(userId);
        stocktakeService.requireManagedWarehouseId(userId, stocktakeId);
        fileAttachmentService.stream(
                fileAttachmentService.requireStocktakeEvidence(stocktakeId, fileId), response);
    }

    private String myWarehouseId(Long userId) {
        WarehouseDto mine = procurementService.getSatelliteWarehouse(userId);
        return mine.warehouseId();
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank() || "ALL".equalsIgnoreCase(value.trim())) {
            return null;
        }
        return value.trim();
    }

    private static Long userId(HttpServletRequest request) {
        return (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
    }
}
