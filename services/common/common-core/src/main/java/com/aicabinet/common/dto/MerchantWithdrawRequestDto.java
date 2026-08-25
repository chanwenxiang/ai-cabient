package com.aicabinet.common.dto;



import java.time.Instant;



public record MerchantWithdrawRequestDto(

        Long requestId,

        String requestNo,

        String merchantId,

        String merchantName,

        Long amountCents,

        String status,

        String payChannel,

        Long reviewerId,

        String reviewRemark,

        Instant reviewedAt,

        String payoutRef,

        String payoutMessage,

        Instant paidAt,

        Instant createdAt,

        Instant updatedAt,

        /** 手续费（分） */

        Long feeCents

) {

    public MerchantWithdrawRequestDto(

            Long requestId,

            String requestNo,

            String merchantId,

            String merchantName,

            Long amountCents,

            String status,

            String payChannel,

            Long reviewerId,

            String reviewRemark,

            Instant reviewedAt,

            String payoutRef,

            String payoutMessage,

            Instant paidAt,

            Instant createdAt,

            Instant updatedAt

    ) {

        this(requestId, requestNo, merchantId, merchantName, amountCents, status, payChannel,

                reviewerId, reviewRemark, reviewedAt, payoutRef, payoutMessage, paidAt,

                createdAt, updatedAt, 0L);

    }

}


