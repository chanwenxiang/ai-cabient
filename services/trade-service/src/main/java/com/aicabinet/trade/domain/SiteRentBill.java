package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@TableName("site_rent_bill")
@Getter
@Setter
public class SiteRentBill {

    @TableId(type = IdType.AUTO)
    private Long billId;
    private Long contractId;
    private String deviceId;
    private String siteName;
    private String billMonth;
    private String partyType;
    private String partyId;
    private int shareBps;
    private int fixedCents;
    private int baseFeeCents;
    private int amountCents;
    private String status = "UNPAID";
    private Instant paidAt;
    /**
     * V309：付款操作人（运营/财务账号 ID）。
     * 🔴 场地租金是<b>对外付款</b>，原先只有 status + paidAt ——「已付」不可复核（谁付的？）。
     */
    private Long paidBy;
    /** V309：付款凭证号（银行流水号/发票号/线下单号），审计追溯用。 */
    private String paidVoucherNo;
    /** V309：付款备注（如「XX银行 2026-09 月租」）。 */
    private String paidRemark;
    private String remark;
    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();
}
