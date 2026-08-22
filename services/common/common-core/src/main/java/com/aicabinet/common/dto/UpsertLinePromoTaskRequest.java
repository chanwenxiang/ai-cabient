package com.aicabinet.common.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

public record UpsertLinePromoTaskRequest(
        @NotNull Long managerId,
        @NotBlank String title,
        String routeCode,
        int targetQty,
        int bountyCents,
        LocalDate dueDate,
        String status,
        Integer doneQty
) {}
