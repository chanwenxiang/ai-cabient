package com.aicabinet.trade.api;

import com.aicabinet.common.dto.ApiResponse;
import com.aicabinet.common.dto.DevOpsHubDto;
import com.aicabinet.common.dto.DevOpsSonarScanDto;
import com.aicabinet.trade.auth.AuthInterceptor;
import com.aicabinet.trade.auth.RequiresPermissions;
import com.aicabinet.trade.service.DevOpsHubService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v2/ops/admin/devops")
public class OpsDevOpsController {

    private final DevOpsHubService devOpsHubService;

    public OpsDevOpsController(DevOpsHubService devOpsHubService) {
        this.devOpsHubService = devOpsHubService;
    }

    @RequiresPermissions("ops:devops:view")
    @GetMapping("/hub")
    public ApiResponse<DevOpsHubDto> hub(HttpServletRequest request) {
        return ApiResponse.ok(devOpsHubService.getHub());
    }

    /**
     * 触发 GitHub Actions Sonar 工作流（异步排队）。
     */
    @RequiresPermissions("ops:devops:scan")
    @PostMapping("/sonar/scan")
    public ApiResponse<DevOpsSonarScanDto> triggerSonarScan(HttpServletRequest request) {
        return ApiResponse.ok(devOpsHubService.triggerSonarScan(operatorId(request)));
    }

    private static Long operatorId(HttpServletRequest request) {
        Object v = request.getAttribute(AuthInterceptor.ATTR_USER_ID);
        return v instanceof Long id ? id : null;
    }
}
