package com.aicabinet.common.dto;



import java.time.Instant;



public record LineWithdrawRequestDto(

        Long requestId,

        String requestNo,

        Long managerId,

        String managerName,

        String phone,

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

        Long feeCents,

        // ============ V308：收款方快照（只读，历史不可变）============

        /** 申请时使用的收款账户 ID */
        Long payoutAccountId,

        /** PAYEE_TYPE_COMPANY（对公）/ PAYEE_TYPE_PERSONAL（对私） */
        String payeeAccountType,

        /** 户名（对私=实名；对公=公司全称） */
        String payeeAccountName,

        /** 账号掩码 —— <b>永远是掩码，绝不是明文</b> */
        String payeeAccountNoMask,

        /** 开户行（BANK 通道用） */
        String payeeBankName,

        /** 打款幂等键 */
        String idemKey,

        /** 渠道单号（对账用） */
        String channelOrderNo
) {

    public LineWithdrawRequestDto(

            Long requestId,

            String requestNo,

            Long managerId,

            String managerName,

            String phone,

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

        this(requestId, requestNo, managerId, managerName, phone, amountCents, status, payChannel,

                reviewerId, reviewRemark, reviewedAt, payoutRef, payoutMessage, paidAt,

                createdAt, updatedAt, 0L, null, null, null, null, null, null, null);

    }

}


