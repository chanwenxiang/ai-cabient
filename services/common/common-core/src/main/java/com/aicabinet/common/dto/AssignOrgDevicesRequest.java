package com.aicabinet.common.dto;

import java.util.List;

public record AssignOrgDevicesRequest(
        List<String> deviceIds
) {}
