package com.aicabinet.common.dto;

import java.time.LocalDate;
import java.util.List;

public record SubmitReplenishmentLinesRequest(List<ReplenishmentTaskLineDto> lines) {}
