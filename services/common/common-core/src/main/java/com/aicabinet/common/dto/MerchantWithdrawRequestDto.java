package com.aicabinet.common.dto;

import java.time.Instant;

/**
 * 商户提现单。
 *
 * <p><b>V307 新增 8 个收款方/打款字段</b>（payee* 快照 + idemKey + channel 单号）。
 * 全部为<b>只读展示</b>用途：运营/商户看「这单打给谁、渠道回了什么单号」。
 *
 * <p>🔴 <b>纪律</b>：DTO 里<b>只有掩码没有明文账号</b>。若将来需要展示完整账号，
 * 必须另开带权限校验的端点，不是在这里加字段。
 */
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
        Long feeCents,

        // ============ V307：收款方快照（只读，历史不可变）============

        /** 申请时使用的收款账户 ID */
        Long payoutAccountId,

        /** PAYEE_TYPE_COMPANY（对公）/ PAYEE_TYPE_PERSONAL（对私） */
        String payeeAccountType,

        /** 户名（对公=公司全称） */
        String payeeAccountName,

        /** 账号掩码 —— <b>永远是掩码，绝不是明文</b> */
        String payeeAccountNoMask,

        /** 开户银行（对公） */
        String payeeBankName,

        /** 打款幂等键（原样传渠道作商户单号，对账用） */
        String idemKey,

        /** 渠道单号（真实出款后回填） */
        String channelOrderNo,

        /** 渠道批次号（部分渠道一次受理多笔时用） */
        String channelBatchNo

) {

    /** 向后兼容构造器：V307 之前的调用点只需传前 16 个参数，收款方字段全为 null。 */
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
            Instant updatedAt,
            Long feeCents
    ) {
        this(requestId, requestNo, merchantId, merchantName, amountCents, status, payChannel,
                reviewerId, reviewRemark, reviewedAt, payoutRef, payoutMessage, paidAt,
                createdAt, updatedAt, feeCents,
                null, null, null, null, null, null, null, null);
    }

    /** 更早的 15 参构造器（无 feeCents）。 */
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
