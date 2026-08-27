package com.aicabinet.trade.api;

import com.aicabinet.common.dto.*;
import com.aicabinet.trade.auth.AuthInterceptor;
import com.aicabinet.trade.auth.RequiresPermissions;
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

    @RequiresPermissions("ops:coupon:list")
    @GetMapping("/definitions")
    public ApiResponse<PageResult<CouponDefinitionDto>> listDefinitions(
            HttpServletRequest request,
            @RequestParam(name = "q", required = false) String q,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        return ApiResponse.ok(couponService.listDefinitionsPage(q, status, page, size));
    }

    @RequiresPermissions(value = {"ops:coupon:create", "ops:coupon:import"}, logical = RequiresPermissions.Logical.OR)
    @PostMapping("/definitions")
    public ApiResponse<CouponDefinitionDto> createDefinition(
            HttpServletRequest request,
            @Valid @RequestBody CreateCouponRequest body) {
        return ApiResponse.ok(couponService.createDefinition(body));
    }

    @RequiresPermissions("ops:coupon:edit")
    @PutMapping("/definitions/{id}")
    public ApiResponse<CouponDefinitionDto> updateDefinition(
            HttpServletRequest request,
            @PathVariable("id") Long id,
            @Valid @RequestBody UpdateCouponRequest body) {
        return ApiResponse.ok(couponService.updateDefinition(id, body));
    }

    @RequiresPermissions("ops:coupon:edit")
    @PutMapping("/definitions/{id}/status")
    public ApiResponse<CouponDefinitionDto> setDefinitionStatus(
            HttpServletRequest request,
            @PathVariable("id") Long id,
            @RequestParam("status") String status) {
        return ApiResponse.ok(couponService.setDefinitionStatus(id, status));
    }

    @RequiresPermissions("ops:coupon:create")
    @PostMapping("/issue")
    public ApiResponse<CouponDto> issue(
            HttpServletRequest request,
            @Valid @RequestBody IssueCouponRequest body) {
        return ApiResponse.ok(couponService.issueToUser(body.userId(), body.couponDefId()));
    }

    @RequiresPermissions("ops:coupon:create")
    @PostMapping("/batch-issue")
    public ApiResponse<List<CouponDto>> batchIssue(
            HttpServletRequest request,
            @Valid @RequestBody BatchIssueCouponRequest body) {
        return ApiResponse.ok(couponService.batchIssue(body.couponDefId(), body.userIds()));
    }
}
