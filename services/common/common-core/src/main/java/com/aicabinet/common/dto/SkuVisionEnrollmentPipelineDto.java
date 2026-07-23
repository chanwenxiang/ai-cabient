package com.aicabinet.common.dto;

import java.util.List;

/**
 * 新品视觉入驻管线元数据（无真实训练时 modelPipelineStatus 固定为 WAITING_REAL_MODEL）。
 */
public record SkuVisionEnrollmentPipelineDto(
        String modelPipelineStatus,
        String modelPipelineHint,
        List<String> statusOrder,
        List<StatusStepDto> steps
) {
    public record StatusStepDto(String status, String label, String description) {}
}
