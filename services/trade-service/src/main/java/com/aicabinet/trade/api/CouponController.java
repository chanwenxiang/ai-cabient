package com.aicabinet.trade.api;

import com.aicabinet.common.dto.*;
import com.aicabinet.trade.auth.AuthInterceptor;
import com.aicabinet.trade.service.CouponService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v2/coupons")
public class CouponController {

    private final CouponService couponService;

    public CouponController(CouponService couponService) {
        this.couponService = couponService;
    }

    @GetMapping
    public ApiResponse<List<CouponDto>> listMyCoupons(
            HttpServletRequest request,
            @RequestParam(required = false) String status) {
        Long userId = (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
        return ApiResponse.ok(couponService.listUserCoupons(userId, status));
    }

    @GetMapping("/count")
    public ApiResponse<Long> countAvailable(HttpServletRequest request) {
        Long userId = (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
        return ApiResponse.ok(couponService.countAvailable(userId));
    }

    @PostMapping("/use")
    public ApiResponse<CouponDto> useCoupon(
            HttpServletRequest request,
            @RequestParam Long couponId,
            @RequestParam String orderId,
            @RequestParam(required = false) String deviceId) {
        Long userId = (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
        return ApiResponse.ok(couponService.useCoupon(userId, couponId, orderId, deviceId));
    }

    @GetMapping("/definitions")
    public ApiResponse<List<CouponDefinitionDto>> listDefinitions() {
        return ApiResponse.ok(couponService.listDefinitions());
    }

    @PostMapping("/definitions")
    public ApiResponse<CouponDefinitionDto> createDefinition(
            @Valid @RequestBody CreateCouponRequest request) {
        return ApiResponse.ok(couponService.createDefinition(request));
    }

    @PostMapping("/issue")
    public ApiResponse<CouponDto> issue(@Valid @RequestBody IssueCouponRequest request) {
        return ApiResponse.ok(couponService.issueToUser(request.userId(), request.couponDefId()));
    }

    @PostMapping("/batch-issue")
    public ApiResponse<List<CouponDto>> batchIssue(@Valid @RequestBody BatchIssueCouponRequest request) {
        return ApiResponse.ok(couponService.batchIssue(request.couponDefId(), request.userIds()));
    }
}
