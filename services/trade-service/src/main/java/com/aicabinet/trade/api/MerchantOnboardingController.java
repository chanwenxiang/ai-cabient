package com.aicabinet.trade.api;

import com.aicabinet.common.dto.ApiResponse;
import com.aicabinet.common.dto.MerchantPaymentOnboardingDto;
import com.aicabinet.common.dto.UpsertMerchantOnboardingRequest;
import com.aicabinet.trade.auth.AuthInterceptor;
import com.aicabinet.trade.auth.RequiresPermissions;
import com.aicabinet.trade.service.MerchantOnboardingService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v2/ops/admin/merchant-onboarding")
public class MerchantOnboardingController {

    private final MerchantOnboardingService onboardingService;

    public MerchantOnboardingController(MerchantOnboardingService onboardingService) {
        this.onboardingService = onboardingService;
    }

    @RequiresPermissions("ops:merchant:onboard:list")
    @GetMapping
    public ApiResponse<List<MerchantPaymentOnboardingDto>> list(
            HttpServletRequest request,
            @RequestParam(required = false) String merchantId,
            @RequestParam(required = false) String channel,
            @RequestParam(required = false) String status) {
        return ApiResponse.ok(onboardingService.list(operatorId(request), merchantId, channel, status));
    }

    @RequiresPermissions("ops:merchant:onboard:list")
    @GetMapping("/live-hints")
    public ApiResponse<Map<String, Object>> liveHints() {
        return ApiResponse.ok(onboardingService.liveHints());
    }

    @RequiresPermissions("ops:merchant:onboard:edit")
    @PostMapping
    public ApiResponse<MerchantPaymentOnboardingDto> create(
            HttpServletRequest request,
            @Valid @RequestBody UpsertMerchantOnboardingRequest body) {
        return ApiResponse.ok(onboardingService.upsert(operatorId(request), null, body));
    }

    @RequiresPermissions("ops:merchant:onboard:edit")
    @PutMapping("/{onboardingId}")
    public ApiResponse<MerchantPaymentOnboardingDto> update(
            HttpServletRequest request,
            @PathVariable Long onboardingId,
            @Valid @RequestBody UpsertMerchantOnboardingRequest body) {
        return ApiResponse.ok(onboardingService.upsert(operatorId(request), onboardingId, body));
    }

    private static Long operatorId(HttpServletRequest request) {
        return (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
    }
}
