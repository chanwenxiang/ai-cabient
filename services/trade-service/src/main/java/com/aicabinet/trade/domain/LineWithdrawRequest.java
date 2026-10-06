package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@TableName("line_withdraw_request")
@Getter
@Setter
public class LineWithdrawRequest {

    @TableId(type = IdType.AUTO)
    private Long requestId;
    private String requestNo;
    private Long managerId;
    private Long amountCents;
    /** 提现手续费（分）；演示默认 0 */
    private Long feeCents;
    private String status;
    private String payChannel;
    private Long reviewerId;
    private String reviewRemark;
    private Instant reviewedAt;
    private String payoutRef;
    private String payoutMessage;
    private Instant paidAt;
    private Instant createdAt;
    private Instant updatedAt;

    // ============ 收款方快照（V308，与 MerchantWithdrawRequest 对齐）============

    /** 申请时使用的收款账户 ID；V308 之前的存量单为空 */
    private Long payoutAccountId;

    /**
     * 🔴 以下 5 列为<b>申请时快照，历史不可变</b>，语义与商户侧完全一致。
     * 为什么不打款时实时查账户：账户可能被改户名/停用/删默认，实时查会导致
     * 「申请时承诺打给 A，实际打给 B」。
     */
    /** PAYEE_TYPE_COMPANY（对公）/ PAYEE_TYPE_PERSONAL（对私） */
    private String payeeAccountType;
    /** 户名（对公=公司名；对私=实名） */
    private String payeeAccountName;
    /** 掩码，**永远不是明文** */
    private String payeeAccountNoMask;
    /** 开户行（BANK 通道用） */
    private String payeeBankName;
    /** 纳税人识别号（对公代付用） */
    private String payeeTaxNo;

    /**
     * 打款幂等键：原样传给渠道作商户单号。
     * 「渠道已出款但本地回执丢失」时重试复用同键 ⇒ 渠道侧不重复出款。
     * DB 部分唯一索引 {@code uk_line_withdraw_idem_key} 兜底。
     */
    private String idemKey;
    /** 渠道单号（对账用） */
    private String channelOrderNo;
    /** 渠道批次号（部分渠道一次受理多笔时用） */
    private String channelBatchNo;

}
