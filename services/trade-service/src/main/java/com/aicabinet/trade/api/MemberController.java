package com.aicabinet.trade.api;

import com.aicabinet.common.dto.ApiResponse;
import com.aicabinet.common.dto.MemberProfileDto;
import com.aicabinet.common.dto.MemberPointsLogDto;
import com.aicabinet.common.dto.MemberPointsSummaryDto;
import com.aicabinet.common.dto.PointsRedeemItemDto;
import com.aicabinet.trade.service.PointsRedeemService;
import com.aicabinet.trade.auth.AuthInterceptor;
import com.aicabinet.trade.service.ConsumerMemberFacade;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v2/member")
public class MemberController {

    private final ConsumerMemberFacade memberFacade;
    private final PointsRedeemService pointsRedeemService;

    public MemberController(ConsumerMemberFacade memberFacade,
                            PointsRedeemService pointsRedeemService) {
        this.memberFacade = memberFacade;
        this.pointsRedeemService = pointsRedeemService;
    }

    @GetMapping("/profile")
    public ApiResponse<MemberProfileDto> profile(HttpServletRequest request) {
        return ApiResponse.ok(memberFacade.profile(userId(request)));
    }

    @GetMapping("/points")
    public ApiResponse<MemberPointsSummaryDto> points(HttpServletRequest request) {
        return ApiResponse.ok(pointsRedeemService.summary(userId(request)));
    }

    @GetMapping("/points/log")
    public ApiResponse<List<MemberPointsLogDto>> pointsLog(
            HttpServletRequest request,
            @RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.ok(pointsRedeemService.pointsLog(userId(request), limit));
    }

    @GetMapping("/redeem/items")
    public ApiResponse<List<PointsRedeemItemDto>> redeemItems() {
        return ApiResponse.ok(pointsRedeemService.redeemItems());
    }

    @PostMapping("/redeem")
    public ApiResponse<com.aicabinet.common.dto.CouponDto> redeem(
            HttpServletRequest request,
            @RequestBody Map<String, Long> body) {
        Long itemId = body == null ? null : body.get("itemId");
        if (itemId == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "缺少 itemId");
        }
        return ApiResponse.ok(pointsRedeemService.redeem(userId(request), itemId));
    }

    private static Long userId(HttpServletRequest request) {
        return (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
    }
}
