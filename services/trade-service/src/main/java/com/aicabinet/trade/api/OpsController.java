package com.aicabinet.trade.api;

import com.aicabinet.common.dto.ApiResponse;
import com.aicabinet.common.dto.OpsOpenDoorRequest;
import com.aicabinet.common.dto.SessionDto;
import com.aicabinet.common.dto.SkuCatalogDto;
import com.aicabinet.trade.auth.AuthInterceptor;
import com.aicabinet.trade.domain.SkuCatalog;
import com.aicabinet.trade.mapper.SkuCatalogMapper;
import com.aicabinet.trade.service.OpsService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v2/ops")
public class OpsController {

    private final OpsService opsService;
    private final SkuCatalogMapper skuCatalogRepository;

    public OpsController(OpsService opsService, SkuCatalogMapper skuCatalogRepository) {
        this.opsService = opsService;
        this.skuCatalogRepository = skuCatalogRepository;
    }

    /** 商品目录（争议审核选品） */
    @GetMapping("/skus")
    public ApiResponse<List<SkuCatalogDto>> listSkus() {
        List<SkuCatalogDto> list = skuCatalogRepository.findAll().stream()
                .map(SkuCatalog::toDto)
                .toList();
        return ApiResponse.ok(list);
    }

    /** 运营补货开门（需运营账号 userId >= 100000000） */
    @PostMapping("/restock/open-door")
    public ApiResponse<SessionDto> openDoorForRestock(
            HttpServletRequest request,
            @Valid @RequestBody OpsOpenDoorRequest body) {
        Long operatorId = (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
        return ApiResponse.ok(opsService.openDoorForRestock(operatorId, body));
    }
}
