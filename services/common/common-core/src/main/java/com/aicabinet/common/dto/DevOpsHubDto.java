package com.aicabinet.common.dto;

import java.util.List;

public record DevOpsHubDto(
        List<DevOpsToolDto> tools,
        String githubUrl,
        String grafanaEmbedPath
) {}
