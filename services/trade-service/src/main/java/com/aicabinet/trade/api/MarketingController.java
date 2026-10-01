package com.aicabinet.trade.api;

import com.aicabinet.common.dto.AdPlayEventRequest;
import com.aicabinet.common.dto.ApiResponse;
import com.aicabinet.common.dto.CouponDto;
import com.aicabinet.common.dto.MarketingBannerDto;
import com.aicabinet.common.dto.MarketingCampaignDto;
import com.aicabinet.trade.auth.AuthInterceptor;
import com.aicabinet.trade.auth.JwtService;
import com.aicabinet.trade.auth.SessionCookieService;
import com.aicabinet.trade.service.AdCampaignService;
import com.aicabinet.trade.service.ConsumerMarketingService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/api/v2/marketing")
public class MarketingController {
    private static final String BEARER = "Bearer ";


    private final ConsumerMarketingService marketingService;
    private final AdCampaignService adCampaignService;
    private final JwtService jwtService;
    private final SessionCookieService sessionCookieService;

    public MarketingController(ConsumerMarketingService marketingService,
                               AdCampaignService adCampaignService,
                               JwtService jwtService,
                               SessionCookieService sessionCookieService) {
        this.marketingService = marketingService;
        this.adCampaignService = adCampaignService;
        this.jwtService = jwtService;
        this.sessionCookieService = sessionCookieService;
    }

    @GetMapping("/banners")
    public ApiResponse<List<MarketingBannerDto>> banners(
            @RequestParam(name = "deviceId", required = false) String deviceId) {
        return ApiResponse.ok(marketingService.banners(deviceId));
    }

    /**
     * P3-6：小程序轮播位曝光/点击留痕。须登录（防匿名刷量）；
     * deviceId 语义 = MP-U{userId}，服务端 60s 窗口去重（复用设备播放打点链路）。
     */
    @PostMapping("/ads/{campaignId}/events")
    public ApiResponse<Void> adEvent(HttpServletRequest request,
                                     @PathVariable("campaignId") Long campaignId,
                                     @Valid @RequestBody AdPlayEventRequest body) {
        Long userId = (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
        if (userId == null || userId <= 0) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录");
        }
        adCampaignService.recordPlayEvent("MP-U" + userId, campaignId,
                body == null ? null : body.assetId(),
                body == null ? null : body.eventType());
        return ApiResponse.ok(null);
    }

    /**
     * 游客可见（AuthInterceptor 放行）。若请求带有效 Bearer/Cookie，则按登录用户返回已领取态。
     */
    @GetMapping("/campaigns/active")
    public ApiResponse<List<MarketingCampaignDto>> activeCampaigns(HttpServletRequest request) {
        return ApiResponse.ok(marketingService.activeCampaigns(resolveOptionalUserId(request)));
    }

    @PostMapping("/campaigns/{id}/claim")
    public ApiResponse<CouponDto> claim(
            HttpServletRequest request,
            @PathVariable("id") Long id) {
        Long userId = (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
        return ApiResponse.ok(marketingService.claimCampaign(userId, id));
    }

    /** 公开接口上的可选登录：有 token 则解析；无效/缺失则按游客，不 401。 */
    private Long resolveOptionalUserId(HttpServletRequest request) {
        String auth = request.getHeader("Authorization");
        if (auth == null || !auth.startsWith(BEARER)) {
            String cookieToken = sessionCookieService.resolveToken(
                    request, SessionCookieService.Realm.CONSUMER);
            if (cookieToken != null && !cookieToken.isBlank()) {
                auth = BEARER + cookieToken;
            }
        }
        if (auth == null || !auth.startsWith(BEARER)) {
            return null;
        }
        try {
            return jwtService.validateAndGetUserId(auth.substring(7));
        } catch (Exception e) {
            return null;
        }
    }
}
