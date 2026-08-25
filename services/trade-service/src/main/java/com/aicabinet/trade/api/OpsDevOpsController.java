package com.aicabinet.trade.api;

import com.aicabinet.common.dto.ApiResponse;
import com.aicabinet.common.dto.DevOpsHubDto;
import com.aicabinet.trade.auth.RequiresPermissions;
import com.aicabinet.trade.service.DevOpsHubService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
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
}
