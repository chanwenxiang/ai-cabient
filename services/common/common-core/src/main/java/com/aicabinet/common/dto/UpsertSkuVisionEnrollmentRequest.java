package com.aicabinet.common.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record UpsertSkuVisionEnrollmentRequest(
        @Valid @NotNull UpsertSkuRequest sku,
        String yoloClassName,
        @Pattern(regexp = "DRAFT|MAPPING|TESTED|PRODUCTION", message = "invalid visionEnrollmentStatus")
        String visionEnrollmentStatus,
        @DecimalMin("0.1") @DecimalMax("1.0")
        Float detectionMinConfidence,
        String referenceImageUrlsJson,
        @Pattern(regexp = "YOLO_SKU|YOLO_COCO|YOLO_RETAIL")
        String mappingSource
) {
    public UpsertSkuVisionEnrollmentRequest {
        if (visionEnrollmentStatus == null || visionEnrollmentStatus.isBlank()) {
            visionEnrollmentStatus = "MAPPING";
        }
        if (detectionMinConfidence == null) {
            detectionMinConfidence = 0.5f;
        }
        if (mappingSource == null || mappingSource.isBlank()) {
            mappingSource = "YOLO_SKU";
        }
    }
}
