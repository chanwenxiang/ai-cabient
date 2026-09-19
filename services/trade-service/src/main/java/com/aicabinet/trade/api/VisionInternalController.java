package com.aicabinet.trade.api;

import com.aicabinet.common.dto.ApiResponse;
import com.aicabinet.common.dto.OpsExceptionDto;
import com.aicabinet.common.dto.VisionAnomalyEventDto;
import com.aicabinet.common.dto.VisionRecognitionResultDto;
import com.aicabinet.common.dto.VisionResultIngestResponseDto;
import com.aicabinet.trade.service.VisionAnomalyIngestService;
import com.aicabinet.trade.service.VisionResultIngestService;
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
    private final VisionResultIngestService visionResultIngestService;

    public VisionInternalController(VisionAnomalyIngestService visionAnomalyIngestService,
                                    VisionResultIngestService visionResultIngestService) {
        this.visionAnomalyIngestService = visionAnomalyIngestService;
        this.visionResultIngestService = visionResultIngestService;
    }

    /** 端侧（移远 OpenVending / 边缘盒）上报视觉异常事件：错拿、遮挡、防撬、异常开门。 */
    @PostMapping("/anomaly-events")
    public ApiResponse<List<OpsExceptionDto>> anomalyEvents(
            @RequestBody List<VisionAnomalyEventDto> events) {
        return ApiResponse.ok(visionAnomalyIngestService.ingest(events));
    }

    /**
     * 端侧（移远 OpenVending / 边缘盒）直报<b>识别结果</b>：在端侧完成识别、不上传视频的形态下，
     * 复用既有结算链路完成本次开门会话的结算。
     *
     * <p>路径 {@code /edge-results} 取自路线图 P0-1 的既定契约
     * （`docs/COMPETITOR_BENCHMARK_AND_ROADMAP_2026-09-17.md`：「阶段 A（`/internal/v1/vision/edge-results`
     * 端侧结果接入结算）」），第三方按文档接入即可直连。
     *
     * <p>回执 {@code accepted=false} 不代表失败，而是「本次未采纳」——端侧应按 {@code outcome}
     * 决定重发（TOO_EARLY 可稍后重试；ALREADY_HANDLED / CANCELLED 勿重发）。
     */
    @PostMapping("/edge-results")
    public ApiResponse<VisionResultIngestResponseDto> edgeResults(
            @RequestBody VisionRecognitionResultDto result) {
        return ApiResponse.ok(visionResultIngestService.ingest(result));
    }
}
