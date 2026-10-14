package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@TableName("line_manager")
@Getter
@Setter
public class LineManager {

    @TableId(type = IdType.AUTO)
    private Long managerId;
    private String managerName;
    private String phone;
    private String status;
    private String wxOpenid;
    private Long userId;
    private String orgName;
    private Integer commissionRateBps;
    /** 提成基数：GMV=销售额（默认）/ RESTOCK_QTY=补货数量（对齐老系统两种模式，V357） */
    private String commissionBasis;
    /** RESTOCK_QTY 模式：每件补货佣金（分/件）；GMV 模式忽略 */
    private Long commissionPerUnitCents;
    private Integer commissionFixedCents;
    private Instant createdAt;
    private Instant updatedAt;

    public String getCommissionBasis() { return commissionBasis; }
    public void setCommissionBasis(String v) { this.commissionBasis = v; }
    public Long getCommissionPerUnitCents() { return commissionPerUnitCents; }
    public void setCommissionPerUnitCents(Long v) { this.commissionPerUnitCents = v; }
}
