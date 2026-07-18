package com.aicabinet.common.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

public record FileDisputeRequest(
        @NotBlank String sessionId,
        @NotBlank @Size(max = 256) String reason,
        String category,
        String priority,
        /** 已上传的申诉附图 fileId（先调 /disputes/evidence） */
        List<Long> evidenceFileIds
) {
    public FileDisputeRequest(String sessionId, String reason, String category, String priority) {
        this(sessionId, reason, category, priority, List.of());
    }
}
