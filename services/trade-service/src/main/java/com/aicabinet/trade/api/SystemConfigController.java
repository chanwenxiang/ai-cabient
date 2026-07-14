package com.aicabinet.trade.api;

import com.aicabinet.common.dto.ApiResponse;
import com.aicabinet.common.dto.SystemConfigDto;
import com.aicabinet.common.dto.UpsertSystemConfigRequest;
import com.aicabinet.trade.auth.AuthInterceptor;
import com.aicabinet.trade.service.SystemConfigService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v2/ops/admin/system-configs")
public class SystemConfigController {

    private final SystemConfigService systemConfigService;

    public SystemConfigController(SystemConfigService systemConfigService) {
        this.systemConfigService = systemConfigService;
    }

    @GetMapping
    public ApiResponse<List<SystemConfigDto>> list(HttpServletRequest request) {
        requireOperator(request);
        return ApiResponse.ok(systemConfigService.listAll());
    }

    @PutMapping
    public ApiResponse<SystemConfigDto> upsert(
            HttpServletRequest request,
            @Valid @RequestBody UpsertSystemConfigRequest body) {
        requireOperator(request);
        return ApiResponse.ok(systemConfigService.upsert(body));
    }

    private static void requireOperator(HttpServletRequest request) {
        // AuthInterceptor already validates JWT; touch attribute to keep intent explicit.
        request.getAttribute(AuthInterceptor.ATTR_USER_ID);
    }
}
