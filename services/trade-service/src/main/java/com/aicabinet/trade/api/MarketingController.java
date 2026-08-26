package com.aicabinet.trade.api;

import com.aicabinet.common.dto.ApiResponse;
import com.aicabinet.common.dto.CouponDto;
import com.aicabinet.common.dto.MarketingBannerDto;
import com.aicabinet.common.dto.MarketingCampaignDto;
import com.aicabinet.trade.auth.AuthInterceptor;
import com.aicabinet.trade.auth.JwtService;
import com.aicabinet.trade.auth.SessionCookieService;
import com.aicabinet.trade.service.ConsumerMarketingService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v2/marketing")
public class MarketingController {
    private static final String BEARER = "Bearer ";


    private final ConsumerMarketingService marketingService;
    private final JwtService jwtService;
    private final SessionCookieService sessionCookieService;

    public MarketingController(ConsumerMarketingService marketingService,
                               JwtService jwtService,
                               SessionCookieService sessionCookieService) {
        this.marketingService = marketingService;
        this.jwtService = jwtService;
        this.sessionCookieService = sessionCookieService;
    }

    @GetMapping("/banners")
    public ApiResponse<List<MarketingBannerDto>> banners() {
        return ApiResponse.ok(marketingService.banners());
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
            String cookieToken = sessionCookieService.resolveToken(request);
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
