package com.aicabinet.trade.api;

import com.aicabinet.common.dto.ApiResponse;
import com.aicabinet.trade.service.VisionMappingService;
import com.aicabinet.trade.service.DemoDataService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** vision-service 拉取 YOLO / 阿里云类目 → SKU 映射。 */
@RestController
@RequestMapping("/internal/v1/vision")
public class VisionMappingInternalController {

    private final VisionMappingService visionMappingService;
    private final DemoDataService demoDataService;

    public VisionMappingInternalController(VisionMappingService visionMappingService,
                                           DemoDataService demoDataService) {
        this.visionMappingService = visionMappingService;
        this.demoDataService = demoDataService;
    }

    @GetMapping("/mappings")
    public ApiResponse<VisionMappingService.VisionMappingsDto> mappings() {
        return ApiResponse.ok(visionMappingService.listMappings());
    }

    /** vision-service mock 兜底：返回柜内可结算 SKU（来自库存，非硬编码）。 */
    @GetMapping("/default-sku")
    public ApiResponse<DefaultSkuDto> defaultSku(@RequestParam(required = false) String deviceId) {
        String skuId = demoDataService.resolveFallbackSku(deviceId);
        return ApiResponse.ok(new DefaultSkuDto(skuId, deviceId != null ? deviceId : DemoDataService.DEMO_DEVICE_ID));
    }

    public record DefaultSkuDto(String skuId, String deviceId) {}
}
