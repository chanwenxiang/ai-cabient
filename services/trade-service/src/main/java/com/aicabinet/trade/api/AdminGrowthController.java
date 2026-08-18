package com.aicabinet.trade.api;

import com.aicabinet.common.dto.ApiResponse;
import com.aicabinet.common.dto.AdminManualNotificationRequest;
import com.aicabinet.common.dto.MemberLevelRuleDto;
import com.aicabinet.common.dto.MarketingRoiRowDto;
import com.aicabinet.common.dto.NotificationDto;
import com.aicabinet.common.dto.PointsRedeemItemDto;
import com.aicabinet.common.dto.SkuDelistReviewDto;
import com.aicabinet.common.dto.UserBehaviorSummaryDto;
import com.aicabinet.common.dto.UserRecallResult;
import com.aicabinet.trade.auth.AuthInterceptor;
import com.aicabinet.trade.auth.RequiresPermissions;
import com.aicabinet.trade.service.NotificationService;
import com.aicabinet.trade.service.MemberLevelAdminService;
import com.aicabinet.trade.service.UserRecallService;
import com.aicabinet.trade.service.MarketingRoiService;
import com.aicabinet.trade.service.PointsRedeemService;
import com.aicabinet.trade.service.SkuDelistReviewService;
import com.aicabinet.trade.service.UserBehaviorAnalyticsService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v2/ops/admin/growth")
public class AdminGrowthController {

    private final PointsRedeemService pointsRedeemService;
    private final SkuDelistReviewService skuReviewService;
    private final UserBehaviorAnalyticsService userBehaviorAnalyticsService;
    private final NotificationService notificationService;
    private final MemberLevelAdminService memberLevelAdminService;
    private final UserRecallService userRecallService;
    private final MarketingRoiService marketingRoiService;

    public AdminGrowthController(PointsRedeemService pointsRedeemService,
                                 SkuDelistReviewService skuReviewService,
                                 UserBehaviorAnalyticsService userBehaviorAnalyticsService,
                                 NotificationService notificationService,
                                 MemberLevelAdminService memberLevelAdminService,
                                 UserRecallService userRecallService,
                                 MarketingRoiService marketingRoiService) {
        this.pointsRedeemService = pointsRedeemService;
        this.skuReviewService = skuReviewService;
        this.userBehaviorAnalyticsService = userBehaviorAnalyticsService;
        this.notificationService = notificationService;
        this.memberLevelAdminService = memberLevelAdminService;
        this.userRecallService = userRecallService;
        this.marketingRoiService = marketingRoiService;
    }

    // ---------- 积分兑换管理 ----------

    @RequiresPermissions("ops:points:list")
    @GetMapping("/points-redeem")
    public ApiResponse<List<PointsRedeemItemDto>> redeemItems() {
        return ApiResponse.ok(pointsRedeemService.adminList());
    }

    @RequiresPermissions("ops:points:edit")
    @PutMapping("/points-redeem")
    public ApiResponse<PointsRedeemItemDto> upsertRedeemItem(@RequestBody PointsRedeemItemDto body) {
        return ApiResponse.ok(pointsRedeemService.adminUpsert(body));
    }

    @RequiresPermissions("ops:points:edit")
    @PostMapping("/points-redeem/{itemId}/status")
    public ApiResponse<PointsRedeemItemDto> setRedeemItemStatus(
            @PathVariable Long itemId,
            @RequestBody Map<String, String> body) {
        return ApiResponse.ok(pointsRedeemService.adminSetStatus(itemId, body.getOrDefault("status", "INACTIVE")));
    }

    // ---------- 选品诊断 ----------

    @RequiresPermissions("ops:sku-review:list")
    @GetMapping("/sku-review")
    public ApiResponse<List<SkuDelistReviewDto>> skuReviews() {
        return ApiResponse.ok(skuReviewService.list());
    }

    @RequiresPermissions("ops:sku-review:list")
    @PostMapping("/sku-review/run")
    public ApiResponse<List<SkuDelistReviewDto>> runSkuReview(
            @RequestParam(defaultValue = "30") int days) {
        return ApiResponse.ok(skuReviewService.runReview(days));
    }

    @RequiresPermissions("ops:sku-review:edit")
    @PostMapping("/sku-review/{skuId}/decide")
    public ApiResponse<SkuDelistReviewDto> decideSku(
            HttpServletRequest request,
            @PathVariable String skuId,
            @RequestBody Map<String, String> body) {
        Long operatorId = (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
        return ApiResponse.ok(skuReviewService.decide(
                skuId,
                body.get("action"),
                body.get("reason"),
                body.get("replaceSkuId"),
                operatorId));
    }

    // ---------- 用户分析 ----------

    @RequiresPermissions("ops:user-analysis:view")
    @GetMapping("/user-analysis")
    public ApiResponse<UserBehaviorSummaryDto> userAnalysis(
            @RequestParam(defaultValue = "30") int days) {
        return ApiResponse.ok(userBehaviorAnalyticsService.summary(days));
    }

    @RequiresPermissions(value = {"ops:user-analysis:view", "ops:coupon:create"}, logical = RequiresPermissions.Logical.AND)
    @PostMapping("/user-recall")
    public ApiResponse<UserRecallResult> userRecall(@RequestBody Map<String, Object> body) {
        Long couponDefId = body.get("couponDefId") == null
                ? null
                : Long.valueOf(String.valueOf(body.get("couponDefId")));
        Integer days = body.get("days") == null
                ? null
                : Integer.valueOf(String.valueOf(body.get("days")));
        List<Long> userIds = body.get("userIds") instanceof List<?> list
                ? list.stream().map(String::valueOf).map(Long::valueOf).toList()
                : null;
        return ApiResponse.ok(userRecallService.recall(couponDefId, days, userIds));
    }

    // ---------- 营销活动效果分析 ----------

    @RequiresPermissions("ops:marketing-roi:view")
    @GetMapping("/marketing-roi")
    public ApiResponse<List<MarketingRoiRowDto>> marketingRoi(
            @RequestParam(defaultValue = "30") int days) {
        return ApiResponse.ok(marketingRoiService.list(days));
    }

    // ---------- 消息记录 ----------

    @RequiresPermissions("ops:notify:list")
    @GetMapping("/notifications")
    public ApiResponse<List<NotificationDto>> notifications(
            @RequestParam(defaultValue = "100") int limit) {
        return ApiResponse.ok(notificationService.adminRecent(limit));
    }

    @RequiresPermissions("ops:notify:list")
    @PostMapping("/notifications/send")
    public ApiResponse<NotificationDto> sendNotification(
            HttpServletRequest request,
            @Valid @RequestBody AdminManualNotificationRequest body) {
        return ApiResponse.ok(notificationService.sendManual(operatorId(request), body));
    }

    // ---------- 会员等级规则 ----------

    @RequiresPermissions("ops:member-level:list")
    @GetMapping("/member-levels")
    public ApiResponse<List<MemberLevelRuleDto>> memberLevels() {
        return ApiResponse.ok(memberLevelAdminService.list());
    }

    @RequiresPermissions("ops:member-level:edit")
    @PutMapping("/member-levels")
    public ApiResponse<MemberLevelRuleDto> upsertMemberLevel(@RequestBody MemberLevelRuleDto body) {
        return ApiResponse.ok(memberLevelAdminService.upsert(body));
    }

    @RequiresPermissions("ops:member-level:edit")
    @PostMapping("/member-levels/{id}/status")
    public ApiResponse<MemberLevelRuleDto> setMemberLevelStatus(
            @PathVariable Long id,
            @RequestBody Map<String, String> body) {
        return ApiResponse.ok(memberLevelAdminService.setStatus(id, body.getOrDefault("status", "INACTIVE")));
    }

    private static Long operatorId(HttpServletRequest request) {
        return (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
    }
}
