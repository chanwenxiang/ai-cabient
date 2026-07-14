package com.aicabinet.common.dto;

import java.util.List;

public record OpsExceptionDetailDto(
        OpsExceptionDto exception, List<OpsExceptionActionDto> actions
) {}
