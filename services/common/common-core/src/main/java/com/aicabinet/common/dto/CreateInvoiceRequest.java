package com.aicabinet.common.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateInvoiceRequest(
        @NotBlank @Size(max = 128) String title,
        @Size(max = 32) String taxNo,
        @Size(max = 128) String email
) {}
