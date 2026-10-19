package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 佣金冲回累计行（V361，CB-040 佣金随退款冲正）。按 (owner_type, owner_id, device_id,
 * biz_date) 唯一——每日批重算出「应冲回总额」后与已冲回额取差递增，天然幂等。
 * 原日佣行保持不动（留痕），资金视角见 {@code wallet_ledger}（domain=LINE/STAFF）负向流水。
 */
@TableName("commission_clawback")
@Getter
@Setter
public class CommissionClawback {
    public static final String OWNER_MANAGER = "MANAGER";
    public static final String OWNER_STAFF = "STAFF";

    @TableId(type = IdType.AUTO)
    private Long id;
    private String ownerType;
    private Long ownerId;
    private String deviceId;
    private LocalDate bizDate;
    /** 累计冲回对应的退款 GMV（记录留痕）。 */
    private Long refundGmvCents;
    /** 累计冲回单数（线长侧）。 */
    private Integer orderCount;
    /** 累计已冲回金额（正数）。 */
    private Long clawbackCents;
    private String status;
    private Instant createdAt;
    private Instant updatedAt;
}
