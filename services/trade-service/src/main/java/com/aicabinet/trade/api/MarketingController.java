package com.aicabinet.trade.api;

import com.aicabinet.common.dto.ApiResponse;
import com.aicabinet.common.dto.CouponDto;
import com.aicabinet.common.dto.MarketingBannerDto;
import com.aicabinet.common.dto.MarketingCampaignDto;
import com.aicabinet.trade.auth.AuthInterceptor;
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

    private final ConsumerMarketingService marketingService;

    public MarketingController(ConsumerMarketingService marketingService) {
        this.marketingService = marketingService;
    }

    @GetMapping("/banners")
    public ApiResponse<List<MarketingBannerDto>> banners() {
        return ApiResponse.ok(marketingService.banners());
    }

    /** 游客可见：不依赖登录态（AuthInterceptor 公开放行，userId 恒为 null）。 */
    @GetMapping("/campaigns/active")
    public ApiResponse<List<MarketingCampaignDto>> activeCampaigns() {
        return ApiResponse.ok(marketingService.activeCampaigns());
    }

    @PostMapping("/campaigns/{id}/claim")
    public ApiResponse<CouponDto> claim(
            HttpServletRequest request,
            @PathVariable("id") Long id) {
        Long userId = (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
        return ApiResponse.ok(marketingService.claimCampaign(userId, id));
    }
}
