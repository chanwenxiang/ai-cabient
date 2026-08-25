package com.aicabinet.trade.api;

import com.aicabinet.common.dto.ApiResponse;
import com.aicabinet.common.dto.OpsExceptionDto;
import com.aicabinet.common.dto.VisionAnomalyEventDto;
import com.aicabinet.trade.service.VisionAnomalyIngestService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 视觉端侧接入（内部 API，由 InternalApiAuthInterceptor 统一鉴权）。
 */
@RestController
@RequestMapping("/internal/v1/vision")
public class VisionInternalController {

    private final VisionAnomalyIngestService visionAnomalyIngestService;

    public VisionInternalController(VisionAnomalyIngestService visionAnomalyIngestService) {
        this.visionAnomalyIngestService = visionAnomalyIngestService;
    }

    /** 端侧（移远 OpenVending / 边缘盒）上报视觉异常事件：错拿、遮挡、防撬、异常开门。 */
    @PostMapping("/anomaly-events")
    public ApiResponse<List<OpsExceptionDto>> anomalyEvents(
            @RequestBody List<VisionAnomalyEventDto> events) {
        return ApiResponse.ok(visionAnomalyIngestService.ingest(events));
    }
}
