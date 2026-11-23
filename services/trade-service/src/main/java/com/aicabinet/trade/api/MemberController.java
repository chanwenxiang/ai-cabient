package com.aicabinet.trade.api;

import com.aicabinet.common.dto.ApiResponse;
import com.aicabinet.common.dto.BindInviteRequest;
import com.aicabinet.common.dto.MemberInviteSummaryDto;
import com.aicabinet.common.dto.MemberProfileDto;
import com.aicabinet.common.dto.MemberPointsLogDto;
import com.aicabinet.common.dto.MemberPointsSummaryDto;
import com.aicabinet.common.dto.PointsRedeemItemDto;
import com.aicabinet.trade.service.MemberInviteService;
import com.aicabinet.trade.service.MemberService;
import com.aicabinet.trade.service.PointsRedeemService;
import com.aicabinet.trade.auth.AuthInterceptor;
import com.aicabinet.trade.service.ConsumerMemberFacade;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
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
    private final MemberInviteService inviteService;
    private final MemberService memberService;

    public MemberController(ConsumerMemberFacade memberFacade,
                            PointsRedeemService pointsRedeemService,
                            MemberInviteService inviteService,
                            MemberService memberService) {
        this.memberFacade = memberFacade;
        this.pointsRedeemService = pointsRedeemService;
        this.inviteService = inviteService;
        this.memberService = memberService;
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

    // ── 邀请（营销 2） ─────────────────────────────────────

    /**
     * 我的邀请码 + 邀请进度。首次访问的会员在这里补建 member 行（没有行就拿不到码）。
     */
    @GetMapping("/invite/me")
    public ApiResponse<MemberInviteSummaryDto> inviteMe(HttpServletRequest request) {
        Long userId = userId(request);
        memberService.createMember(userId);
        return ApiResponse.ok(inviteService.summary(userId));
    }

    /**
     * 绑定邀请人。错误口径：码无效 404、已绑/已有消费记录 409、绑自己被 400 —— 前端据此分文案。
     */
    // authz: self-service —— 邀请人由请求体里的码反查，被写的是调用者自己那一行 member；
    // 不存在 ops:/merchant: 权限码这一层。未登录由 AuthInterceptor 前置拦成 401（实测无 token = 401）。
    @PostMapping("/invite/bind")
    public ApiResponse<MemberInviteSummaryDto> bindInvite(HttpServletRequest request,
                                                          @Valid @RequestBody BindInviteRequest body) {
        Long userId = userId(request);
        memberService.createMember(userId);
        return ApiResponse.ok(inviteService.bind(userId, body.inviteCode()));
    }
}
