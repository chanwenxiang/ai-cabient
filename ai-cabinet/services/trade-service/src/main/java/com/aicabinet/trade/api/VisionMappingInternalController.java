package com.aicabinet.trade.api;

import com.aicabinet.common.dto.ApiResponse;
import com.aicabinet.trade.service.VisionMappingService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** vision-service 拉取 YOLO / 阿里云类目 → SKU 映射。 */
@RestController
@RequestMapping("/internal/v1/vision")
public class VisionMappingInternalController {

    private final VisionMappingService visionMappingService;

    public VisionMappingInternalController(VisionMappingService visionMappingService) {
        this.visionMappingService = visionMappingService;
    }

    @GetMapping("/mappings")
    public ApiResponse<VisionMappingService.VisionMappingsDto> mappings() {
        return ApiResponse.ok(visionMappingService.listMappings());
    }
}
