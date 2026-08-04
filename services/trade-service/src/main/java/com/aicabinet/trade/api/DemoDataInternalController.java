package com.aicabinet.trade.api;

import com.aicabinet.common.dto.ApiResponse;
import com.aicabinet.trade.service.DemoDataService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** E2E / 联调：从数据库读取或补齐演示业务上下文。 */
@RestController
@RequestMapping("/internal/v1/demo")
public class DemoDataInternalController {

    private final DemoDataService demoDataService;

    public DemoDataInternalController(DemoDataService demoDataService) {
        this.demoDataService = demoDataService;
    }

    @GetMapping("/context")
    public ApiResponse<DemoDataService.DemoContext> context() {
        return ApiResponse.ok(demoDataService.getContext());
    }

    @PostMapping("/ensure")
    public ApiResponse<DemoDataService.DemoContext> ensure() {
        return ApiResponse.ok(demoDataService.ensureDemoData());
    }
}
