package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;

@Data
@TableName("cabinet_order")
public class CabinetOrder {

    @TableId(type = IdType.INPUT)
    private String orderId;

    private String sessionId;

    private Long userId;

    private String deviceId;

    /** 设备名称冗余（结算写入） */
    private String deviceName;

    /** 商户 ID 冗余（结算写入） */
    private String merchantId;

    /** 商户名称冗余（结算写入） */
    private String merchantName;

    private int totalAmountCents;

    private String status;

    private String payChannel = "BALANCE";

    private String payTradeNo;

    private String paymentOperationId;
    private Integer balanceBeforeCents;
    private Integer balanceAfterCents;

    private boolean inventoryDeducted;

    private Instant refundedAt;

    /** 累计已退款金额（分）；部分退可累加 */
    private int refundedCents;

    private Long couponId;

    private int couponDiscountCents;

    private int memberDiscountCents;

    private Long promotionId;

    @TableField(exist = false)
    private int originalAmountCents;

    @TableField(exist = false)
    private List<CabinetOrderLine> lines = new ArrayList<>();

    @TableField(fill = FieldFill.INSERT)
    private Instant createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Instant updatedAt;

    public void addLine(CabinetOrderLine line) {
        line.setOrderId(this.orderId);
        lines.add(line);
    }
}
