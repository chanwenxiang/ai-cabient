package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@TableName("invoice_request")
@Getter
@Setter
public class InvoiceRequest {
    @TableId(type = IdType.AUTO)
    private Long invoiceId;
    private String orderId;
    private Long userId;
    private String title;
    private String taxNo;
    private String email;
    private int amountCents;
    private String status = "PENDING";
    private String rejectReason;
    private Instant issuedAt;
    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();
}
