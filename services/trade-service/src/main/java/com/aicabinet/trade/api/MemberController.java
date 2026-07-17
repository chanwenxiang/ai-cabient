package com.aicabinet.trade.api;

import com.aicabinet.common.dto.*;
import com.aicabinet.trade.auth.AuthInterceptor;
import com.aicabinet.trade.service.ConsumerMemberFacade;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v2/member")
public class MemberController {

    private final ConsumerMemberFacade memberFacade;

    public MemberController(ConsumerMemberFacade memberFacade) {
        this.memberFacade = memberFacade;
    }

    @GetMapping("/profile")
    public ApiResponse<MemberProfileDto> profile(HttpServletRequest request) {
        return ApiResponse.ok(memberFacade.profile(userId(request)));
    }

    @GetMapping("/points/summary")
    public ApiResponse<MemberPointsSummaryDto> pointsSummary(HttpServletRequest request) {
        return ApiResponse.ok(memberFacade.pointsSummary(userId(request)));
    }

    @GetMapping("/points")
    public ApiResponse<List<MemberPointsLogDto>> pointsHistory(
            HttpServletRequest request,
            @RequestParam(required = false) String type) {
        return ApiResponse.ok(memberFacade.pointsHistory(userId(request), type));
    }

    @GetMapping("/redeem-items")
    public ApiResponse<List<PointsRedeemItemDto>> redeemItems(HttpServletRequest request) {
        return ApiResponse.ok(memberFacade.listRedeemItems(userId(request)));
    }

    @PostMapping("/redeem")
    public ApiResponse<CouponDto> redeem(
            HttpServletRequest request,
            @Valid @RequestBody RedeemPointsRequest body) {
        return ApiResponse.ok(memberFacade.redeem(userId(request), body.itemId()));
    }

    private static Long userId(HttpServletRequest request) {
        return (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
    }
}
