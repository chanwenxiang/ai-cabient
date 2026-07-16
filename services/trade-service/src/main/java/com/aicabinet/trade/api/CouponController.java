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
    private final com.aicabinet.trade.service.PermissionService permissionService;

    public CouponController(
            CouponService couponService,
            com.aicabinet.trade.service.PermissionService permissionService) {
        this.couponService = couponService;
        this.permissionService = permissionService;
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
    public ApiResponse<List<CouponDefinitionDto>> listDefinitions(HttpServletRequest request) {
        permissionService.requirePermission(operatorId(request), "ops:coupon:list");
        return ApiResponse.ok(couponService.listDefinitions());
    }

    @PostMapping("/definitions")
    public ApiResponse<CouponDefinitionDto> createDefinition(
            HttpServletRequest request,
            @Valid @RequestBody CreateCouponRequest body) {
        permissionService.requirePermission(operatorId(request), "ops:coupon:create");
        return ApiResponse.ok(couponService.createDefinition(body));
    }

    @PutMapping("/definitions/{id}/status")
    public ApiResponse<CouponDefinitionDto> setDefinitionStatus(
            HttpServletRequest request,
            @PathVariable("id") Long id,
            @RequestParam("status") String status) {
        permissionService.requirePermission(operatorId(request), "ops:coupon:edit");
        return ApiResponse.ok(couponService.setDefinitionStatus(id, status));
    }

    @PostMapping("/issue")
    public ApiResponse<CouponDto> issue(
            HttpServletRequest request,
            @Valid @RequestBody IssueCouponRequest body) {
        permissionService.requirePermission(operatorId(request), "ops:coupon:create");
        return ApiResponse.ok(couponService.issueToUser(body.userId(), body.couponDefId()));
    }

    @PostMapping("/batch-issue")
    public ApiResponse<List<CouponDto>> batchIssue(
            HttpServletRequest request,
            @Valid @RequestBody BatchIssueCouponRequest body) {
        permissionService.requirePermission(operatorId(request), "ops:coupon:create");
        return ApiResponse.ok(couponService.batchIssue(body.couponDefId(), body.userIds()));
    }

    private static Long operatorId(HttpServletRequest request) {
        return (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
    }
}
