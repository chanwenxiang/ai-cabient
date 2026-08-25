package com.aicabinet.trade.api;

import com.aicabinet.common.dto.ApiResponse;
import com.aicabinet.common.dto.SiteRentSplitRuleDto;
import com.aicabinet.common.dto.UpsertSiteRentSplitRulesRequest;
import com.aicabinet.trade.auth.AuthInterceptor;
import com.aicabinet.trade.auth.RequiresPermissions;
import com.aicabinet.trade.service.SiteRentSplitService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v2/ops/admin/site-contracts/{contractId}/rent-split-rules")
public class SiteRentSplitController {

    private final SiteRentSplitService rentSplitService;

    public SiteRentSplitController(SiteRentSplitService rentSplitService) {
        this.rentSplitService = rentSplitService;
    }

    @RequiresPermissions("ops:org:list")
    @GetMapping
    public ApiResponse<List<SiteRentSplitRuleDto>> list(
            HttpServletRequest request, @PathVariable Long contractId) {
        return ApiResponse.ok(rentSplitService.listByContract(operatorId(request), contractId));
    }

    @RequiresPermissions("ops:org:edit")
    @PutMapping
    public ApiResponse<List<SiteRentSplitRuleDto>> replace(
            HttpServletRequest request,
            @PathVariable Long contractId,
            @Valid @RequestBody UpsertSiteRentSplitRulesRequest body) {
        return ApiResponse.ok(rentSplitService.replaceRules(operatorId(request), contractId, body));
    }

    private static Long operatorId(HttpServletRequest request) {
        return (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
    }
}
