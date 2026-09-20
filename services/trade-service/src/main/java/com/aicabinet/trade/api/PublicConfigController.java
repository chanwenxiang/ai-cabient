package com.aicabinet.trade.api;

import com.aicabinet.common.dto.ApiResponse;
import com.aicabinet.common.dto.OpsBrandDto;
import com.aicabinet.trade.service.SystemConfigService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v2/public")
public class PublicConfigController {

    private final SystemConfigService systemConfigService;

    public PublicConfigController(SystemConfigService systemConfigService) {
        this.systemConfigService = systemConfigService;
    }

    @GetMapping("/consumer-config")
    public ApiResponse<Map<String, String>> consumerConfig() {
        return ApiResponse.ok(systemConfigService.consumerPublicConfig());
    }

    /**
     * 商户端配置（只含非敏感 UI 开关，如经营分析图表）。
     * 与 C 端配置对称放在匿名端点下，便于商户端首屏取用。
     */
    @GetMapping("/merchant-config")
    public ApiResponse<Map<String, String>> merchantConfig() {
        return ApiResponse.ok(systemConfigService.merchantPublicConfig());
    }

    /** 运营后台登录页 / 侧栏品牌（无需登录）。 */
    @GetMapping("/ops-branding")
    public ApiResponse<OpsBrandDto> opsBranding() {
        return ApiResponse.ok(systemConfigService.opsBrandPublic());
    }
}
