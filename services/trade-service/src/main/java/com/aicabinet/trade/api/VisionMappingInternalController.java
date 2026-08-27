package com.aicabinet.trade.api;

import com.aicabinet.common.dto.ApiResponse;
import com.aicabinet.common.dto.DeviceVisionContextDto;
import com.aicabinet.trade.service.DemoDataService;
import com.aicabinet.trade.service.SkuVisionEnrollmentService;
import com.aicabinet.trade.service.VisionMappingService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** vision-service 拉取端侧类名 / 阿里云类目 → SKU 映射。 */
@RestController
@RequestMapping("/internal/v1/vision")
public class VisionMappingInternalController {

    private final VisionMappingService visionMappingService;
    private final DemoDataService demoDataService;
    private final SkuVisionEnrollmentService skuVisionEnrollmentService;

    public VisionMappingInternalController(VisionMappingService visionMappingService,
                                           DemoDataService demoDataService,
                                           SkuVisionEnrollmentService skuVisionEnrollmentService) {
        this.visionMappingService = visionMappingService;
        this.demoDataService = demoDataService;
        this.skuVisionEnrollmentService = skuVisionEnrollmentService;
    }

    @GetMapping("/mappings")
    public ApiResponse<VisionMappingService.VisionMappingsDto> mappings() {
        return ApiResponse.ok(visionMappingService.listMappings());
    }

    /** 柜机在售 SKU 白名单 + 视觉字段，供 DeepSeek / 训练脚本约束识别范围。 */
    @GetMapping("/device/{deviceId}/context")
    public ApiResponse<DeviceVisionContextDto> deviceContext(@PathVariable("deviceId") String deviceId) {
        return ApiResponse.ok(skuVisionEnrollmentService.deviceVisionContext(deviceId));
    }

    /** 已录入 YOLO 类名的 SKU 列表，供 collect_sku_dataset 拉取。 */
    @GetMapping("/catalog-classes")
    public ApiResponse<List<SkuVisionEnrollmentService.CatalogClassRow>> catalogClasses() {
        return ApiResponse.ok(skuVisionEnrollmentService.listCatalogClassesForTraining());
    }

    /** vision-service mock 兜底：返回柜内可结算 SKU（来自库存，非硬编码）。 */
    @GetMapping("/default-sku")
    public ApiResponse<DefaultSkuDto> defaultSku(@RequestParam(required = false) String deviceId) {
        String skuId = demoDataService.resolveFallbackSku(deviceId);
        return ApiResponse.ok(new DefaultSkuDto(skuId, deviceId != null ? deviceId : DemoDataService.DEMO_DEVICE_ID));
    }

    public record DefaultSkuDto(String skuId, String deviceId) {}
}
