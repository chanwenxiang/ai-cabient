package com.aicabinet.common.dto;

import java.util.List;

public record SubmitReplenishmentLinesRequest(List<ReplenishmentTaskLineDto> lines) {}
