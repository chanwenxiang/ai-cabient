package com.aicabinet.common.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record MerchantTaxProfileDto(
        String merchantId,
        @NotBlank @Size(max = 128) String companyName,
        @NotBlank @Size(max = 32) String taxNo,
        @Size(max = 256) String address,
        @Size(max = 128) String bankName,
        @Size(max = 64) String bankAccount,
        @Size(max = 32) String phone
) {}
