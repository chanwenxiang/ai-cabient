package com.aicabinet.common.dto;

import java.time.Instant;

/**
 * 场地租金应付账单（按账期出账台账，标记已付不触发打款）。
 */
public record SiteRentBillDto(
        Long billId,
        Long contractId,
        String deviceId,
        String siteName,
        String billMonth,
        String partyType,
        String partyId,
        int shareBps,
        int fixedCents,
        int baseFeeCents,
        int amountCents,
        String status,
        Instant paidAt,
        // ============ V309：付款留痕（对齐 DB 的 paid_by / paid_voucher_no / paid_remark）============
        /** 付款操作人账号 ID（V309 新增；历史账单为 null） */
        Long paidBy,
        /** 付款凭证号（V309 新增；历史账单为 null） */
        String paidVoucherNo,
        /** 付款备注（V309 新增；历史账单为 null） */
        String paidRemark,
        String remark,
        Instant createdAt,
        Instant updatedAt
) {}
